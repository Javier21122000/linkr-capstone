package com.linkr.backend.people;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.linkrequest.LinkRequestRepository;
import com.linkr.backend.linkrequest.LinkState;
import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.people.PeopleRepository.DiscoverRow;
import com.linkr.backend.people.dto.DiscoverPersonResponse;
import com.linkr.backend.people.dto.SharedInterestResponse;
import com.linkr.backend.user.UserNotFoundException;

/**
 * Discover People: persone che hanno scelto di essere visibili (opt-in) e condividono almeno un interesse con te.
 *
 * Regole: RECIPROCITA' (se tu non sei visibile ottieni 403), mai te stesso, mai persone senza interessi in comune,
 * ordine per interessi condivisi. Se non hai interessi la lista e' vuota (non si inventano dati sociali).
 */
@Service
public class DiscoverPeopleService {

	private final PeopleRepository peopleRepository;
	private final LinkRequestRepository linkRequestRepository;

	public DiscoverPeopleService(PeopleRepository peopleRepository, LinkRequestRepository linkRequestRepository) {
		this.peopleRepository = peopleRepository;
		this.linkRequestRepository = linkRequestRepository;
	}

	/** REPEATABLE_READ come PeopleService: le 4 query della pagina vedono la stessa istantanea dei dati. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public PageResponse<DiscoverPersonResponse> findPeople(UUID me, int page, int size) {
		if (page < 0) {
			throw new InvalidEventFilterException("'page' must be 0 or greater");
		}
		if (size < 1 || size > PageResponse.MAX_SIZE) {
			throw new InvalidEventFilterException("'size' must be between 1 and " + PageResponse.MAX_SIZE);
		}
		if (!peopleRepository.isDiscoverable(me).orElseThrow(UserNotFoundException::new)) {
			throw new DiscoveryRequiredException();
		}

		long total = peopleRepository.countDiscoverable(me);
		List<DiscoverRow> rows = peopleRepository.findDiscoverablePage(me, size, (long) page * size);
		List<UUID> userIds = rows.stream().map(DiscoverRow::userId).toList();
		var shared = peopleRepository.findSharedInterests(me, userIds);
		var links = linkRequestRepository.findStates(me, userIds);

		List<DiscoverPersonResponse> items = rows.stream().map(row -> {
			List<SharedInterestResponse> interests = shared.getOrDefault(row.userId(), List.of());
			LinkState link = links.get(row.userId());
			return new DiscoverPersonResponse(row.userId(), row.username(), row.displayName(), row.avatarUrl(),
					interests.size(), interests, link == null ? LinkStatus.NONE : link.status(),
					link == null ? null : link.requestId());
		}).toList();
		return PageResponse.of(items, page, size, total);
	}

}
