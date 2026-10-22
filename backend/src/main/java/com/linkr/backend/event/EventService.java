package com.linkr.backend.event;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;

/**
 * Logica di lettura degli eventi.
 *
 * Regole della lista:
 *  - si vedono solo eventi PUBLISHED (DRAFT e CANCELLED mai);
 *  - gli eventi terminati non compaiono: restano quelli futuri e quelli in corso;
 *  - un evento SENZA endsAt si considera in corso per DEFAULT_DURATION (3 ore) dopo l'inizio;
 *  - "from" e "to" sono date di calendario (Europe/Rome), entrambe incluse;
 *  - "from" nel passato non fa riapparire eventi gia' terminati: il limite inferiore e' max(adesso, inizio di "from");
 *  - un evento e' incluso se e' attivo in almeno un momento dell'intervallo richiesto.
 *
 * Il mapping verso i DTO avviene qui, dentro la transazione (open-in-view e' disattivato).
 */
@Service
@Transactional(readOnly = true)
public class EventService {

	/** Durata presunta degli eventi senza endsAt. */
	public static final Duration DEFAULT_DURATION = Duration.ofHours(3);

	private static final Sort ORDER = Sort.by("startsAt").ascending().and(Sort.by("title").ascending());

	private final EventRepository eventRepository;
	private final EventMapper eventMapper;
	private final Clock clock;

	public EventService(EventRepository eventRepository, EventMapper eventMapper, Clock clock) {
		this.eventRepository = eventRepository;
		this.eventMapper = eventMapper;
		this.clock = clock;
	}

	/**
	 * Un evento e' terminato quando now >= fine, dove fine = endsAt oppure startsAt + DEFAULT_DURATION.
	 * E' LA regola usata da Discover (lista), dalle partecipazioni e dalla discovery persone.
	 */
	public static boolean hasEnded(Event event, Instant now) {
		Instant end = event.getEndsAt() != null ? event.getEndsAt() : event.getStartsAt().plus(DEFAULT_DURATION);
		return !now.isBefore(end);
	}

	public List<EventSummaryResponse> findEvents(EventCategory category, LocalDate from, LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw new InvalidEventFilterException("'from' must not be after 'to'");
		}

		ZoneId zone = clock.getZone();
		Instant now = clock.instant();

		// Limite inferiore: mai prima di adesso, cosi' gli eventi terminati restano fuori.
		Instant lowerBound = now;
		if (from != null) {
			Instant startOfFrom = from.atStartOfDay(zone).toInstant();
			if (startOfFrom.isAfter(now)) {
				lowerBound = startOfFrom;
			}
		}

		Specification<Event> spec = EventSpecifications.hasStatus(EventStatus.PUBLISHED)
				.and(EventSpecifications.notEndedAt(lowerBound, DEFAULT_DURATION));
		if (to != null) {
			// "to" e' incluso: si arriva fino a mezzanotte del giorno dopo (esclusa).
			spec = spec.and(EventSpecifications.startsBefore(to.plusDays(1).atStartOfDay(zone).toInstant()));
		}
		if (category != null) {
			spec = spec.and(EventSpecifications.hasCategory(category));
		}

		return eventMapper.toSummaries(eventRepository.findAll(spec, ORDER));
	}

	public EventDetailResponse getEvent(UUID id) {
		return eventRepository.findByIdAndStatus(id, EventStatus.PUBLISHED)
				.map(eventMapper::toDetail)
				.orElseThrow(() -> new EventNotFoundException(id));
	}

}
