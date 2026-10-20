package com.linkr.backend.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.linkr.backend.event.dto.EventDetailResponse;
import com.linkr.backend.event.dto.EventSummaryResponse;

// Questi test provano solo il controller/CORS: la sicurezza e' provata in AuthIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(EventController.class)
class EventControllerTest {

	private static final UUID ID = UUID.fromString("018f0000-0000-7000-8000-000000000001");
	private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-15T19:30:00+02:00");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private EventService eventService;

	@MockitoBean
	private EventManagementService managementService;

	@Test
	void listReturnsSummariesWithIsoDatesAndOffset() throws Exception {
		when(eventService.findEvents(null, null, null)).thenReturn(List.of(summary()));

		mockMvc.perform(get("/api/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(ID.toString()))
				.andExpect(jsonPath("$[0].title").value("Courtyard Sessions"))
				.andExpect(jsonPath("$[0].category").value("MUSIC"))
				.andExpect(jsonPath("$[0].startsAt").value("2026-10-15T19:30:00+02:00"))
				.andExpect(jsonPath("$[0].endsAt").doesNotExist())
				.andExpect(jsonPath("$[0].demo").value(true))
				// il riepilogo non contiene i campi del dettaglio
				.andExpect(jsonPath("$[0].description").doesNotExist());
	}

	@Test
	void listPassesFiltersToService() throws Exception {
		when(eventService.findEvents(any(), any(), any())).thenReturn(List.of());

		mockMvc.perform(get("/api/events")
						.param("category", "MUSIC")
						.param("from", "2026-10-15")
						.param("to", "2026-10-18"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));

		verify(eventService).findEvents(EventCategory.MUSIC, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 18));
	}

	@Test
	void invalidCategoryReturns400ProblemDetail() throws Exception {
		mockMvc.perform(get("/api/events").param("category", "NOT_A_CATEGORY"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	@Test
	void invalidDateReturns400ProblemDetail() throws Exception {
		mockMvc.perform(get("/api/events").param("from", "15-10-2026"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void inconsistentFiltersReturn400() throws Exception {
		when(eventService.findEvents(any(), eq(LocalDate.of(2026, 10, 18)), eq(LocalDate.of(2026, 10, 15))))
				.thenThrow(new InvalidEventFilterException("'from' must not be after 'to'"));

		mockMvc.perform(get("/api/events").param("from", "2026-10-18").param("to", "2026-10-15"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid filters"));
	}

	@Test
	void detailReturnsFullEvent() throws Exception {
		when(eventService.getEvent(ID)).thenReturn(new EventDetailResponse(ID, "Courtyard Sessions", "Long text",
				EventCategory.MUSIC, START, START.plusHours(3), "Cortile 68 (demo venue)", "Demo address",
				"Isola", 45.4878, 9.189, "https://example.com/i.jpg", "alt", "Unsplash demo image", true,
				EventStatus.PUBLISHED, EventType.OFFICIAL, null, false));

		mockMvc.perform(get("/api/events/{id}", ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Long text"))
				.andExpect(jsonPath("$.address").value("Demo address"))
				.andExpect(jsonPath("$.endsAt").value("2026-10-15T22:30:00+02:00"))
				.andExpect(jsonPath("$.imageCredit").value("Unsplash demo image"))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.type").value("OFFICIAL"))
				.andExpect(jsonPath("$.canEdit").value(false));
	}

	@Test
	void unknownEventReturns404ProblemDetail() throws Exception {
		when(eventService.getEvent(ID)).thenThrow(new EventNotFoundException(ID));

		mockMvc.perform(get("/api/events/{id}", ID))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.title").value("Event not found"));
	}

	@Test
	void malformedIdReturns400() throws Exception {
		mockMvc.perform(get("/api/events/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void unexpectedErrorReturns500WithoutInternalDetails() throws Exception {
		when(eventService.getEvent(ID)).thenThrow(new IllegalStateException("db password is hunter2"));

		mockMvc.perform(get("/api/events/{id}", ID))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
				.andExpect(jsonPath("$.trace").doesNotExist());
	}

	private EventSummaryResponse summary() {
		return new EventSummaryResponse(ID, "Courtyard Sessions", EventCategory.MUSIC, START, null,
				"Cortile 68 (demo venue)", "Isola", 45.4878, 9.189, null, null, true, EventStatus.PUBLISHED, EventType.OFFICIAL);
	}

}
