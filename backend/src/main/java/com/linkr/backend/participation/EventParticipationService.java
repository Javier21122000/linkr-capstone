package com.linkr.backend.participation;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.event.dto.EventSummaryResponse;
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
		if (EventService.hasEnded(event, clock.instant())) {
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

	/**
	 * Eventi salvati dell'utente: una sola query con JOIN FETCH. Sono inclusi gli eventi ANNULLATI (CANCELLED), che il
	 * DTO segnala con status: chi vi partecipava li riconosce ancora. Le bozze non compaiono mai.
	 */
	@Transactional(readOnly = true)
	public UserParticipationsResponse list(UUID userId) {
		List<EventParticipation> rows = participationRepository.findByUserWithEvent(userId, List.of(EventStatus.PUBLISHED, EventStatus.CANCELLED));
		// Un solo passaggio per i riepiloghi (e una sola lettura dell'origine esterna), nello stesso ordine delle partecipazioni.
		List<EventSummaryResponse> summaries = eventMapper.toSummaries(rows.stream().map(EventParticipation::getEvent).toList());
		List<UserParticipationResponse> response = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			EventParticipation p = rows.get(i);
			response.add(new UserParticipationResponse(p.getStatus(), p.isVisibleToOthers(), toLocal(p.getUpdatedAt()), summaries.get(i)));
		}
		return new UserParticipationsResponse(response);
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


	private ParticipationResponse toResponse(EventParticipation p) {
		return new ParticipationResponse(p.getEventId(), p.getStatus(), p.isVisibleToOthers(),
				toLocal(p.getCreatedAt()), toLocal(p.getUpdatedAt()));
	}

	private OffsetDateTime toLocal(Instant instant) {
		return instant.atZone(clock.getZone()).toOffsetDateTime();
	}

}
