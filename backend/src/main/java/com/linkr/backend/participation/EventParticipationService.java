package com.linkr.backend.participation;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.event.Event;
import com.linkr.backend.event.EventMapper;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.EventRepository;
import com.linkr.backend.event.EventService;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.participation.dto.ParticipationResponse;
import com.linkr.backend.participation.dto.UserParticipationResponse;
import com.linkr.backend.participation.dto.UserParticipationsResponse;
import com.linkr.backend.user.UserNotFoundException;
import com.linkr.backend.user.UserRepository;

/**
 * Partecipazioni agli eventi. Regole degli eventi (le stesse delle API Event gia' esistenti):
 *  - un evento esiste per le API solo se e' PUBLISHED, altrimenti 404;
 *  - un evento TERMINATO non accetta nuove dichiarazioni ne' modifiche (PUT -> 409). La fine e' endsAt se c'e',
 *    altrimenti startsAt + EventService.DEFAULT_DURATION: la STESSA regola con cui Discover nasconde gli eventi;
 *  - GET e DELETE restano consentiti anche su eventi terminati (storico).
 * L'utente arriva sempre dal JWT, mai dalla richiesta.
 */
@Service
public class EventParticipationService {

	private final EventParticipationRepository participationRepository;
	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final EventMapper eventMapper;
	private final Clock clock;

	public EventParticipationService(EventParticipationRepository participationRepository,
			EventRepository eventRepository, UserRepository userRepository, EventMapper eventMapper, Clock clock) {
		this.participationRepository = participationRepository;
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.eventMapper = eventMapper;
		this.clock = clock;
	}

	/** Crea o aggiorna la partecipazione (idempotente: lo stesso PUT ripetuto da' lo stesso risultato). */
	@Transactional
	public ParticipationResponse declare(UUID userId, UUID eventId, ParticipationStatus status, boolean visibleToOthers) {
		requireUser(userId);
		Event event = requirePublishedEvent(eventId);
		if (hasEnded(event)) {
			throw new EventEndedException();
		}
		participationRepository.upsert(userId, eventId, status.name(), visibleToOthers);
		return participationRepository.findByUserIdAndEventId(userId, eventId)
				.map(this::toResponse)
				.orElseThrow(ParticipationNotFoundException::new);
	}

	@Transactional(readOnly = true)
	public ParticipationResponse get(UUID userId, UUID eventId) {
		requirePublishedEvent(eventId);
		return participationRepository.findByUserIdAndEventId(userId, eventId)
				.map(this::toResponse)
				.orElseThrow(ParticipationNotFoundException::new);
	}

	/**
	 * Rimuove la partecipazione dell'utente. E' idempotente: se non c'era, non succede nulla (nessun errore).
	 * Non controlla lo stato dell'evento, cosi' l'utente puo' sempre ripulire anche eventi poi annullati.
	 */
	@Transactional
	public void remove(UUID userId, UUID eventId) {
		participationRepository.deleteByUserAndEvent(userId, eventId);
	}

	/** Eventi salvati dell'utente: una sola query con JOIN FETCH, solo eventi PUBLISHED. */
	@Transactional(readOnly = true)
	public UserParticipationsResponse list(UUID userId) {
		return new UserParticipationsResponse(
				participationRepository.findByUserWithEvent(userId, EventStatus.PUBLISHED).stream()
						.map(p -> new UserParticipationResponse(p.getStatus(), p.isVisibleToOthers(),
								toLocal(p.getUpdatedAt()), eventMapper.toSummary(p.getEvent())))
						.toList());
	}

	private void requireUser(UUID userId) {
		if (!userRepository.existsById(userId)) {
			throw new UserNotFoundException();
		}
	}

	private Event requirePublishedEvent(UUID eventId) {
		return eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
				.orElseThrow(() -> new EventNotFoundException(eventId));
	}

	/**
	 * Terminato quando l'ora attuale ha raggiunto la fine: ora >= fine. Orologio iniettato, quindi testabile.
	 * Senza endsAt vale la durata presunta di Discover (EventService.DEFAULT_DURATION, un solo valore per tutti).
	 */
	private boolean hasEnded(Event event) {
		Instant end = event.getEndsAt() != null
				? event.getEndsAt()
				: event.getStartsAt().plus(EventService.DEFAULT_DURATION);
		return !clock.instant().isBefore(end);
	}

	private ParticipationResponse toResponse(EventParticipation p) {
		return new ParticipationResponse(p.getEventId(), p.getStatus(), p.isVisibleToOthers(),
				toLocal(p.getCreatedAt()), toLocal(p.getUpdatedAt()));
	}

	private OffsetDateTime toLocal(Instant instant) {
		return instant.atZone(clock.getZone()).toOffsetDateTime();
	}

}
