package com.linkr.backend.event.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.linkr.backend.event.EventCategory;

/** Dati completi di un evento per la pagina di dettaglio. */
public record EventDetailResponse(
		UUID id,
		String title,
		String description,
		EventCategory category,
		OffsetDateTime startsAt,
		OffsetDateTime endsAt,
		String venueName,
		String address,
		String neighbourhood,
		double latitude,
		double longitude,
		String imageUrl,
		String imageAlt,
		String imageCredit,
		boolean demo) {
}
