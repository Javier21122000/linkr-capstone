package com.linkr.backend.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;

@RestController
@RequestMapping("/api/events")
public class EventController {

	private final EventService eventService;

	public EventController(EventService eventService) {
		this.eventService = eventService;
	}

	/** Esempio: GET /api/events?category=MUSIC&from=2026-10-15&to=2026-10-18 (tutti i parametri sono opzionali). */
	@GetMapping
	public List<EventSummaryResponse> list(
			@RequestParam(required = false) EventCategory category,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return eventService.findEvents(category, from, to);
	}

	@GetMapping("/{id}")
	public EventDetailResponse detail(@PathVariable UUID id) {
		return eventService.getEvent(id);
	}

}
