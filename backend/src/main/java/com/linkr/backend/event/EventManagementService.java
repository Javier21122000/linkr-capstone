package com.linkr.backend.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventRequest;
import com.linkr.backend.event.dto.UserEventsResponse;
import com.linkr.backend.participation.EventParticipationRepository;
import com.linkr.backend.upload.EventImageStorage;
import com.linkr.backend.user.User;
import com.linkr.backend.user.UserNotFoundException;
import com.linkr.backend.user.UserRepository;
import com.linkr.backend.user.UserRole;

import jakarta.persistence.EntityManager;

/**
 * Creazione e gestione degli eventi da parte degli utenti.
 *
 * Regole:
 *  - chi e' autenticato puo' creare; l'evento nasce sempre PUBLISHED (niente bozze) e non demo; il creatore e' l'utente del JWT;
 *  - solo il creatore o un ADMIN puo' modificare o annullare. Gli eventi senza creatore (demo/ufficiali storici) solo l'ADMIN;
 *  - il ruolo si legge dal DATABASE, non dal token: un admin declassato perde subito i permessi;
 *  - l'annullamento e' logico (CANCELLED): l'evento resta nel database e chi vi partecipava lo riconosce ancora;
 *  - un evento annullato o terminato non si modifica (409); annullare due volte e' idempotente (204);
 *  - un evento annullato lo vedono solo creatore, admin e partecipanti: per tutti gli altri e' 404.
 *
 * Il mapping verso i DTO avviene qui, dentro la transazione (open-in-view e' disattivato).
 */
@Service
public class EventManagementService {

	/** Eventi che un utente normale puo' creare in 24 ore. */
	public static final int MAX_CREATED_PER_DAY = 5;
	/** Durata massima di un evento (fine - inizio). */
	public static final Duration MAX_DURATION = Duration.ofDays(7);

	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final EventParticipationRepository participationRepository;
	private final EventMapper eventMapper;
	private final EventImageStorage imageStorage;
	private final EntityManager entityManager;
	private final Clock clock;

	public EventManagementService(EventRepository eventRepository, UserRepository userRepository,
			EventParticipationRepository participationRepository, EventMapper eventMapper, EventImageStorage imageStorage,
			EntityManager entityManager, Clock clock) {
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.participationRepository = participationRepository;
		this.eventMapper = eventMapper;
		this.imageStorage = imageStorage;
		this.entityManager = entityManager;
		this.clock = clock;
	}

	@Transactional
	public EventDetailResponse create(UUID userId, EventRequest request) {
		// Blocca la riga dell'utente: le sue creazioni simultanee vengono messe in fila e il limite giornaliero non si aggira.
		User user = userRepository.findByIdForUpdate(userId).orElseThrow(UserNotFoundException::new);
		boolean admin = isAdmin(user);

		EventType type = request.type() != null ? request.type() : EventType.COMMUNITY;
		if (type == EventType.OFFICIAL && !admin) {
			throw new EventForbiddenException("Only administrators can create official events.");
		}
		Instant now = clock.instant();
		if (!admin && eventRepository.countByCreatedByAndCreatedAtAfter(userId, now.minus(Duration.ofHours(24))) >= MAX_CREATED_PER_DAY) {
			throw new EventLimitExceededException();
		}
		validateContent(request, now, true);

		Event event = eventRepository.saveAndFlush(Event.create(userId, type, request));
		// Ricarica dal database: porta con se' le date generate e il creatore (relazione in sola lettura).
		entityManager.refresh(event);
		return eventMapper.toDetail(event, true);
	}

	@Transactional
	public EventDetailResponse update(UUID userId, UUID eventId, EventRequest request) {
		User actor = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
		boolean admin = isAdmin(actor);
		Event event = requireManageable(eventId, userId, admin);

		if (event.getStatus() == EventStatus.CANCELLED) {
			throw EventNotEditableException.cancelled();
		}
		Instant now = clock.instant();
		if (EventService.hasEnded(event, now)) {
			throw EventNotEditableException.ended();
		}
		if (request.type() != null && request.type() != event.getType() && !admin) {
			throw new EventForbiddenException("Only administrators can change the type of an event.");
		}
		// L'inizio deve essere futuro solo se cambia: un evento in corso resta modificabile (titolo, descrizione...).
		boolean startChanged = !request.startsAt().toInstant().equals(event.getStartsAt());
		validateContent(request, now, startChanged);

		event.update(request, admin ? request.type() : null);
		eventRepository.saveAndFlush(event);
		entityManager.refresh(event);
		return eventMapper.toDetail(event, canEdit(event, userId, admin, now));
	}

	/** Annullamento logico, idempotente: annullare un evento gia' annullato non e' un errore. */
	@Transactional
	public void cancel(UUID userId, UUID eventId) {
		User actor = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
		Event event = requireManageable(eventId, userId, isAdmin(actor));
		if (event.getStatus() == EventStatus.CANCELLED) {
			return;
		}
		if (EventService.hasEnded(event, clock.instant())) {
			throw EventNotEditableException.ended();
		}
		event.cancel();
		eventRepository.saveAndFlush(event);
	}

	/**
	 * Dettaglio per un utente autenticato. Come quello pubblico, ma: aggiunge canEdit e mostra anche gli eventi ANNULLATI
	 * a chi puo' gestirli o vi partecipava (per tutti gli altri restano 404).
	 */
	@Transactional(readOnly = true)
	public EventDetailResponse detailFor(UUID viewerId, UUID eventId) {
		Event event = eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
		// Un token valido di un utente poi eliminato si tratta come anonimo: l'endpoint e' pubblico.
		User viewer = userRepository.findById(viewerId).orElse(null);
		boolean admin = viewer != null && isAdmin(viewer);
		boolean manager = viewer != null && canManage(event, viewerId, admin);

		switch (event.getStatus()) {
			case PUBLISHED -> {
			}
			case CANCELLED -> {
				boolean participant = viewer != null && participationRepository.existsByUserIdAndEventId(viewerId, eventId);
				if (!manager && !participant) {
					throw new EventNotFoundException(eventId);
				}
			}
			default -> throw new EventNotFoundException(eventId);
		}
		return eventMapper.toDetail(event, viewer != null && canEdit(event, viewerId, admin, clock.instant()));
	}

	@Transactional(readOnly = true)
	public UserEventsResponse myEvents(UUID userId) {
		return new UserEventsResponse(
				eventMapper.toSummaries(eventRepository.findByCreatedByAndStatusInOrderByStartsAtDescTitleAsc(userId,
						List.of(EventStatus.PUBLISHED, EventStatus.CANCELLED))));
	}

	// ---------- regole ----------

	/**
	 * Evento su cui l'utente puo' operare. Inesistente o bozza = 404; annullato e non gestibile = 404 (non si rivela);
	 * esistente ma non suo = 403.
	 */
	private Event requireManageable(UUID eventId, UUID userId, boolean admin) {
		Event event = eventRepository.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
		if (event.getStatus() == EventStatus.DRAFT) {
			throw new EventNotFoundException(eventId);
		}
		if (!canManage(event, userId, admin)) {
			if (event.getStatus() == EventStatus.CANCELLED) {
				throw new EventNotFoundException(eventId);
			}
			throw EventForbiddenException.notOwner();
		}
		return event;
	}

	private boolean canManage(Event event, UUID userId, boolean admin) {
		return admin || userId.equals(event.getCreatedBy());
	}

	private boolean canEdit(Event event, UUID userId, boolean admin, Instant now) {
		return event.getStatus() == EventStatus.PUBLISHED && !EventService.hasEnded(event, now)
				&& canManage(event, userId, admin);
	}

	private boolean isAdmin(User user) {
		return user.getRole() == UserRole.ADMIN;
	}

	/** Regole tra piu' campi, che le annotazioni di EventRequest non possono esprimere. */
	private void validateContent(EventRequest r, Instant now, boolean requireFutureStart) {
		Map<String, String> errors = new LinkedHashMap<>();
		Instant start = r.startsAt().toInstant();
		if (requireFutureStart && !start.isAfter(now)) {
			errors.put("startsAt", "must be in the future");
		}
		if (r.endsAt() != null) {
			Instant end = r.endsAt().toInstant();
			if (!end.isAfter(start)) {
				errors.put("endsAt", "must be after startsAt");
			}
			else if (Duration.between(start, end).compareTo(MAX_DURATION) > 0) {
				errors.put("endsAt", "must be at most " + MAX_DURATION.toDays() + " days after startsAt");
			}
		}
		if (r.imageUrl() != null && !r.imageUrl().isBlank() && (r.imageAlt() == null || r.imageAlt().isBlank())) {
			errors.put("imageAlt", "is required when imageUrl is set");
		}
		// Un'immagine "interna" deve essere un file caricato davvero (il formato del nome e' gia' garantito da EventRequest).
		EventImageStorage.filenameOf(r.imageUrl()).ifPresent(filename -> {
			if (!imageStorage.exists(filename)) {
				errors.put("imageUrl", "must reference an image uploaded through the upload endpoint");
			}
		});
		if (!errors.isEmpty()) {
			throw new InvalidEventException(errors);
		}
	}

}
