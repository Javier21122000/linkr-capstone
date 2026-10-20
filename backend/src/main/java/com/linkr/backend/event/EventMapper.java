package com.linkr.backend.event;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Component;

import com.linkr.backend.event.dto.EventCreatorResponse;
import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;
import com.linkr.backend.user.User;

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
				event.isDemo(),
				event.getStatus(),
				event.getType());
	}

	/** Dettaglio per chi non puo' modificare l'evento (anonimi inclusi). */
	public EventDetailResponse toDetail(Event event) {
		return toDetail(event, false);
	}

	/** Va chiamato dentro la transazione: il creatore e' una relazione LAZY. */
	public EventDetailResponse toDetail(Event event, boolean canEdit) {
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
				event.isDemo(),
				event.getStatus(),
				event.getType(),
				toCreator(event.getCreator()),
				canEdit);
	}

	private EventCreatorResponse toCreator(User creator) {
		return creator == null ? null
				: new EventCreatorResponse(creator.getId(), creator.getUsername(), creator.getDisplayName(), creator.getAvatarUrl());
	}

	private OffsetDateTime toLocal(Instant instant) {
		return instant == null ? null : instant.atZone(clock.getZone()).toOffsetDateTime();
	}

}
