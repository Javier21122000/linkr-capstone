package com.linkr.backend.event;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.linkr.backend.event.dto.EventCreatorResponse;
import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;
import com.linkr.backend.event.dto.EventSourceResponse;
import com.linkr.backend.user.User;

/** Converte l'entita' Event nei DTO delle risposte. Le date UTC diventano date con offset di Milano. */
@Component
public class EventMapper {

	private final Clock clock;
	/** null nei test unitari: senza lettore l'origine esterna non si cerca e source resta null. */
	private final EventSourceReader sourceReader;

	public EventMapper(Clock clock) {
		this(clock, null);
	}

	@Autowired
	public EventMapper(Clock clock, EventSourceReader sourceReader) {
		this.clock = clock;
		this.sourceReader = sourceReader;
	}

	/** Una lista di eventi con UNA sola lettura dell'origine esterna (non una per evento). */
	public List<EventSummaryResponse> toSummaries(Collection<Event> events) {
		Map<UUID, EventSourceResponse> sources = sourcesOf(events);
		return events.stream().map(event -> toSummary(event, sources.get(event.getId()))).toList();
	}

	public EventSummaryResponse toSummary(Event event) {
		return toSummary(event, sourcesOf(List.of(event)).get(event.getId()));
	}

	private Map<UUID, EventSourceResponse> sourcesOf(Collection<Event> events) {
		return sourceReader == null || events.isEmpty() ? Map.of() : sourceReader.findByEventIds(events.stream().map(Event::getId).toList());
	}

	private EventSummaryResponse toSummary(Event event, EventSourceResponse source) {
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
				event.getType(),
				source);
	}

	/** Dettaglio per chi non puo' modificare l'evento (anonimi inclusi). */
	public EventDetailResponse toDetail(Event event) {
		return toDetail(event, false);
	}

	/** Va chiamato dentro la transazione: il creatore e' una relazione LAZY. */
	public EventDetailResponse toDetail(Event event, boolean canEdit) {
		EventSourceResponse source = sourcesOf(List.of(event)).get(event.getId());
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
				canEdit,
				source);
	}

	private EventCreatorResponse toCreator(User creator) {
		return creator == null ? null
				: new EventCreatorResponse(creator.getId(), creator.getUsername(), creator.getDisplayName(), creator.getAvatarUrl());
	}

	private OffsetDateTime toLocal(Instant instant) {
		return instant == null ? null : instant.atZone(clock.getZone()).toOffsetDateTime();
	}

}
