package com.linkr.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;
import com.linkr.backend.event.dto.EventSummaryResponse;

/**
 * Prova le regole di ricerca con SQL vero sul database di test (linkr_test_db) (cosi' si verificano anche la migrazione Flyway
 * e la validazione di Hibernate). Ogni test e' @Transactional: a fine test tutto viene annullato (rollback),
 * quindi nessun dato di prova resta nel database. I controlli guardano solo gli eventi creati dal test.
 */
@IntegrationTest
@Transactional
class EventQueryIntegrationTest {

	@Autowired
	private EventService service;

	@Autowired
	private EventRepository repository;

	@Autowired
	private Clock clock;

	private final Instant now = Instant.now();

	@Test
	void onlyPublishedNotEndedEventsAreListed() {
		Event future = save(EventCategory.MUSIC, EventStatus.PUBLISHED, now.plus(Duration.ofDays(2)), null);
		Event draft = save(EventCategory.MUSIC, EventStatus.DRAFT, now.plus(Duration.ofDays(2)), null);
		Event cancelled = save(EventCategory.MUSIC, EventStatus.CANCELLED, now.plus(Duration.ofDays(2)), null);
		Event ended = save(EventCategory.MUSIC, EventStatus.PUBLISHED,
				now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(2)));
		Event inProgress = save(EventCategory.MUSIC, EventStatus.PUBLISHED,
				now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(2)));

		assertThat(listedIds(null, null, null)).contains(future.getId(), inProgress.getId())
				.doesNotContain(draft.getId(), cancelled.getId(), ended.getId());
	}

	@Test
	void eventWithoutEndIsConsideredOngoingForThreeHours() {
		Event startedOneHourAgo = save(EventCategory.FOOD, EventStatus.PUBLISHED, now.minus(Duration.ofHours(1)), null);
		Event startedFourHoursAgo = save(EventCategory.FOOD, EventStatus.PUBLISHED, now.minus(Duration.ofHours(4)), null);

		assertThat(listedIds(null, null, null)).contains(startedOneHourAgo.getId())
				.doesNotContain(startedFourHoursAgo.getId());
	}

	@Test
	void categoryFilterReturnsOnlyThatCategory() {
		Event music = save(EventCategory.MUSIC, EventStatus.PUBLISHED, now.plus(Duration.ofDays(1)), null);
		Event sport = save(EventCategory.SPORT, EventStatus.PUBLISHED, now.plus(Duration.ofDays(1)), null);

		List<EventSummaryResponse> sportOnly = service.findEvents(EventCategory.SPORT, null, null);

		assertThat(sportOnly).extracting(EventSummaryResponse::id).contains(sport.getId()).doesNotContain(music.getId());
		assertThat(sportOnly).allMatch(e -> e.category() == EventCategory.SPORT);
	}

	@Test
	void dateFiltersUseMilanCalendarDaysAndAreInclusive() {
		Instant startsAt = now.plus(Duration.ofDays(5));
		LocalDate day = startsAt.atZone(clock.getZone()).toLocalDate();
		Event event = save(EventCategory.CULTURE, EventStatus.PUBLISHED, startsAt, startsAt.plus(Duration.ofHours(1)));

		assertThat(listedIds(null, day, day)).contains(event.getId());
		assertThat(listedIds(null, day.minusDays(1), day.plusDays(1))).contains(event.getId());
		assertThat(listedIds(null, day.plusDays(1), null)).doesNotContain(event.getId());
		assertThat(listedIds(null, null, day.minusDays(1))).doesNotContain(event.getId());
	}

	@Test
	void pastFromDoesNotBringBackEndedEvents() {
		Event ended = save(EventCategory.NIGHTLIFE, EventStatus.PUBLISHED,
				now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(2)).plus(Duration.ofHours(2)));
		LocalDate longAgo = LocalDate.now(clock).minusDays(30);

		assertThat(listedIds(null, longAgo, null)).doesNotContain(ended.getId());
	}

	@Test
	void resultsAreOrderedByStartTime() {
		Event later = save(EventCategory.SOCIAL, EventStatus.PUBLISHED, now.plus(Duration.ofDays(20)), null);
		Event sooner = save(EventCategory.SOCIAL, EventStatus.PUBLISHED, now.plus(Duration.ofDays(10)), null);

		List<UUID> ids = listedIds(EventCategory.SOCIAL, null, null);

		assertThat(ids.indexOf(sooner.getId())).isLessThan(ids.indexOf(later.getId()));
	}

	@Test
	void detailIsAvailableOnlyForPublishedEvents() {
		Event published = save(EventCategory.OUTDOORS, EventStatus.PUBLISHED, now.plus(Duration.ofDays(1)), null);
		Event draft = save(EventCategory.OUTDOORS, EventStatus.DRAFT, now.plus(Duration.ofDays(1)), null);
		Event cancelled = save(EventCategory.OUTDOORS, EventStatus.CANCELLED, now.plus(Duration.ofDays(1)), null);

		assertThat(service.getEvent(published.getId()).title()).isEqualTo("ITEST event");
		assertThatThrownBy(() -> service.getEvent(draft.getId())).isInstanceOf(EventNotFoundException.class);
		assertThatThrownBy(() -> service.getEvent(cancelled.getId())).isInstanceOf(EventNotFoundException.class);
	}

	private List<UUID> listedIds(EventCategory category, LocalDate from, LocalDate to) {
		return service.findEvents(category, from, to).stream().map(EventSummaryResponse::id).toList();
	}

	private Event save(EventCategory category, EventStatus status, Instant startsAt, Instant endsAt) {
		return repository.saveAndFlush(Event.builder()
				.title("ITEST event").description("Created by a test, rolled back at the end")
				.category(category).status(status).startsAt(startsAt).endsAt(endsAt)
				.venueName("Test venue").address("Test address").neighbourhood("Test")
				.latitude(45.46).longitude(9.19).demo(true)
				.build());
	}

}
