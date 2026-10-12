package com.linkr.backend.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.event.EventCategory;

/** Dati di un evento per la lista (Discover) e per la mappa. */
public record EventSummaryResponse(
		UUID id,
		String title,
		EventCategory category,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		String venueName,
		String neighbourhood,
		double latitude,
		double longitude,
		String imageUrl,
		String imageAlt,
		boolean demo) {
}
