package com.linkr.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.linkr.backend.config.TimeConfig;
import com.linkr.backend.event.dto.EventDetailResponse;

/** Test unitari del service: repository finto, nessun database. */
class EventServiceTest {

	private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:00Z"), TimeConfig.MILAN_ZONE);
	private final EventRepository repository = mock(EventRepository.class);
	private final EventService service = new EventService(repository, new EventMapper(clock), clock);

	@Test
	void fromAfterToIsRejectedBeforeQueryingTheDatabase() {
		assertThatThrownBy(() -> service.findEvents(null, LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 15)))
				.isInstanceOf(InvalidEventFilterException.class);

		verifyNoInteractions(repository);
	}

	@Test
	void unknownOrUnpublishedEventThrowsNotFound() {
		UUID id = UUID.randomUUID();
		when(repository.findByIdAndStatus(id, EventStatus.PUBLISHED)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getEvent(id)).isInstanceOf(EventNotFoundException.class);
	}

	@Test
	void detailDatesUseMilanOffsetIncludingDaylightSavingTime() {
		UUID summerId = UUID.randomUUID();
		UUID winterId = UUID.randomUUID();
		when(repository.findByIdAndStatus(summerId, EventStatus.PUBLISHED))
				.thenReturn(Optional.of(event(summerId, Instant.parse("2026-07-15T17:30:00Z"))));
		when(repository.findByIdAndStatus(winterId, EventStatus.PUBLISHED))
				.thenReturn(Optional.of(event(winterId, Instant.parse("2027-01-15T18:30:00Z"))));

		EventDetailResponse summer = service.getEvent(summerId);
		EventDetailResponse winter = service.getEvent(winterId);

		assertThat(summer.startsAt().toString()).isEqualTo("2026-07-15T19:30+02:00"); // ora legale
		assertThat(winter.startsAt().toString()).isEqualTo("2027-01-15T19:30+01:00"); // ora solare
		assertThat(summer.endsAt()).isNull();
	}

	private Event event(UUID id, Instant startsAt) {
		return Event.builder()
				.id(id).title("Title").description("Description")
				.category(EventCategory.MUSIC).status(EventStatus.PUBLISHED)
				.startsAt(startsAt).venueName("Venue").address("Address").neighbourhood("Isola")
				.latitude(45.4).longitude(9.1).demo(true)
				.build();
	}

}
