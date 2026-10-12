package com.linkr.backend.event;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;

/** Converte l'entita' Event nei DTO delle risposte. Le date UTC diventano date con offset di Milano. */
@Component
public class EventMapper {

	private final Clock clock;

	public EventMapper(Clock clock) {
		this.clock = clock;
	}

	public EventSummaryResponse toSummary(Event event) {
		return new EventSummaryResponse(
				event.getId(),
				event.getTitle(),
				event.getCategory(),
				toLocal(event.getStartsAt()),
				toLocal(event.getEndsAt()),
				event.getVenueName(),
				event.getNeighbourhood(),
				event.getLatitude(),
				event.getLongitude(),
				event.getImageUrl(),
				event.getImageAlt(),
				event.isDemo());
	}

	public EventDetailResponse toDetail(Event event) {
		return new EventDetailResponse(
				event.getId(),
				event.getTitle(),
				event.getDescription(),
				event.getCategory(),
				toLocal(event.getStartsAt()),
				toLocal(event.getEndsAt()),
				event.getVenueName(),
				event.getAddress(),
				event.getNeighbourhood(),
				event.getLatitude(),
				event.getLongitude(),
				event.getImageUrl(),
				event.getImageAlt(),
				event.getImageCredit(),
				event.isDemo());
	}

	private OffsetDateTime toLocal(Instant instant) {
		return instant == null ? null : instant.atZone(clock.getZone()).toOffsetDateTime();
	}

}
