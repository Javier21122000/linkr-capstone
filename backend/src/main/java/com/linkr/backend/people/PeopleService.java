package com.linkr.backend.people;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.event.Event;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.EventRepository;
import com.linkr.backend.event.EventService;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.linkrequest.LinkRequestRepository;
import com.linkr.backend.linkrequest.LinkState;
import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.participation.EventEndedException;
import com.linkr.backend.participation.EventParticipationRepository;
import com.linkr.backend.people.PeopleRepository.PersonRow;
import com.linkr.backend.people.dto.PersonResponse;
import com.linkr.backend.people.dto.SharedInterestResponse;

/**
 * Persone interessate a un evento. Ordine dei controlli di accesso:
 *   evento inesistente/non pubblicato -> 404, evento terminato -> 409, nessuna propria partecipazione -> 403.
 * (Il JWT mancante o invalido -> 401 e' gestito prima, da Spring Security.)
 * Si mostrano solo persone con visible_to_others = true, mai l'utente stesso.
 */
@Service
public class PeopleService {

	public static final int DEFAULT_SIZE = PageResponse.DEFAULT_SIZE;
	public static final int MAX_SIZE = PageResponse.MAX_SIZE;

	private final EventRepository eventRepository;
	private final EventParticipationRepository participationRepository;
	private final PeopleRepository peopleRepository;
	private final LinkRequestRepository linkRequestRepository;
	private final Clock clock;

	public PeopleService(EventRepository eventRepository, EventParticipationRepository participationRepository,
			PeopleRepository peopleRepository, LinkRequestRepository linkRequestRepository, Clock clock) {
		this.eventRepository = eventRepository;
		this.participationRepository = participationRepository;
		this.peopleRepository = peopleRepository;
		this.linkRequestRepository = linkRequestRepository;
		this.clock = clock;
	}

	/**
	 * REPEATABLE_READ: le 4 query della pagina (totale, persone, interessi, stato dei Link) vedono lo stesso istantanea dei dati,
	 * quindi il risultato e' coerente anche se qualcuno cambia visibilita' o interessi nel frattempo.
	 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<PersonResponse> findPeople(UUID me, UUID eventId, PeopleSort sort, int page, int size) {
		if (page < 0) {
			throw new InvalidEventFilterException("'page' must be 0 or greater");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new InvalidEventFilterException("'size' must be between 1 and " + MAX_SIZE);
		}

		Event event = eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
				.orElseThrow(() -> new EventNotFoundException(eventId));
		if (EventService.hasEnded(event, clock.instant())) {
			throw new EventEndedException();
		}
		if (participationRepository.findByUserIdAndEventId(me, eventId).isEmpty()) {
			throw new ParticipationRequiredException();
		}

		long total = peopleRepository.countVisible(eventId, me);
		List<PersonRow> rows = peopleRepository.findPage(eventId, me, sort, size, (long) page * size);
		List<UUID> userIds = rows.stream().map(PersonRow::userId).toList();
		var shared = peopleRepository.findSharedInterests(me, userIds);
		var links = linkRequestRepository.findStates(me, userIds); // 4a query della pagina: stato del Link con tutti

		List<PersonResponse> items = rows.stream().map(row -> {
			List<SharedInterestResponse> interests = shared.getOrDefault(row.userId(), List.of());
			LinkState link = links.get(row.userId());
			return new PersonResponse(row.userId(), row.username(), row.displayName(), row.avatarUrl(), row.status(),
					interests.size(), interests, link == null ? LinkStatus.NONE : link.status(),
					link == null ? null : link.requestId());
		}).toList();
		return PageResponse.of(items, page, size, total);
	}

}
