package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.KEY;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.MISC_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.NOW;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.SPORTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.ARTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import tools.jackson.databind.json.JsonMapper;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.ticketmaster.ExistingEventsSource.ExistingEvent;
import com.linkr.backend.ticketmaster.TicketmasterApi.EventSearch;
import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Status;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;
import com.linkr.backend.ticketmaster.TicketmasterTestData.EventJson;

/** Il servizio con un client finto e una sorgente finta degli eventi esistenti: nessuna rete, nessun database. */
class TicketmasterDryRunServiceTest {

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	/** Risponde con un copione: ogni elemento e' una pagina oppure un errore da lanciare. */
	private static final class FakeApi implements TicketmasterApi {

		final Deque<Object> script = new ArrayDeque<>();
		final List<Integer> requestedPages = new ArrayList<>();
		final List<EventSearch> searches = new ArrayList<>();
		/** Simula le statistiche che il vero client aggiorna. */
		int rateLimitedResponses;
		Duration retryAfter;

		FakeApi then(String pageJson) {
			script.add(TicketmasterResponseParser.parse(pageJson));
			return this;
		}

		FakeApi thenFail(Kind kind, Integer httpStatus) {
			script.add(new TicketmasterException(kind, httpStatus, retryAfter).withAttempts(3));
			return this;
		}

		@Override
		public EventsPage fetchEvents(EventSearch search, int page, CallStats stats) {
			requestedPages.add(page);
			searches.add(search);
			Object next = script.poll();
			if (next == null) {
				throw new AssertionError("unexpected request for page " + page);
			}
			stats.calls++;
			stats.rateLimitedResponses += rateLimitedResponses;
			stats.longestRetryAfter = retryAfter;
			if (next instanceof RuntimeException failure) {
				throw failure;
			}
			stats.pagesFetched++;
			return (EventsPage) next;
		}

	}

	private static final class FakeExisting implements ExistingEventsSource {

		Set<String> imported = Set.of();
		List<ExistingEvent> events = List.of();
		RuntimeException failure;
		int calls;
		Instant from;
		Instant to;

		@Override
		public Set<String> importedExternalIds(Collection<String> externalIds) {
			calls++;
			if (failure != null) {
				throw failure;
			}
			return imported;
		}

		@Override
		public List<ExistingEvent> eventsStartingBetween(Instant from, Instant to) {
			calls++;
			this.from = from;
			this.to = to;
			return events;
		}

	}

	private final FakeApi api = new FakeApi();
	private final FakeExisting existing = new FakeExisting();

	private TicketmasterDryRunService service(String... properties) {
		return new TicketmasterDryRunService(TicketmasterTestData.properties(properties), api, existing, CLOCK);
	}

	private static EventJson valid(String id) {
		return event(id).title("Evento " + id.replace("-", " ")); // titoli diversi: nessun sospetto di duplicato
	}

	// ---------- spento / non configurato ----------

	@Test
	void whenDisabledItAnswersWithoutTouchingAnything() {
		TicketmasterDryRunReport report = service("enabled", "false").run(null, null);

		assertThat(report.status()).isEqualTo(Status.DISABLED);
		assertThat(report.dryRun()).isTrue();
		assertThat(report.message()).contains("disabled").contains("LINKR_TICKETMASTER_ENABLED").doesNotContain(KEY);
		assertThat(report.counts().received()).isZero();
		assertThat(report.api().calls()).isZero();
		assertThat(report.api().stoppedBy()).isEqualTo("NOT_RUN");
		assertThat(report.errors()).isEmpty();
		assertThat(report.categoryDistribution()).hasSize(EventCategory.values().length).containsValue(0);
		assertThat(api.requestedPages).isEmpty();
		assertThat(existing.calls).isZero();
	}

	@Test
	void theSwitchAnswersFirstEvenIfTheCallerOrTheTuningIsWrong() {
		// spento: la risposta e' sempre quella controllata, anche con parametri fuori limite
		assertThat(service("enabled", "false").run(999, 999).status()).isEqualTo(Status.DISABLED);
		assertThat(service("enabled", "false", "window-days", "0").run(null, null).status()).isEqualTo(Status.DISABLED);
		// acceso ma senza chiave: idem
		assertThat(service("api-key", "").run(999, 999).status()).isEqualTo(Status.NOT_CONFIGURED);
		assertThat(api.requestedPages).isEmpty();
		assertThat(existing.calls).isZero();
	}

	@Test
	void whenEnabledWithoutAKeyItSaysSoAndSendsNothing() {
		TicketmasterDryRunReport report = service("api-key", "").run(null, null);

		assertThat(report.status()).isEqualTo(Status.NOT_CONFIGURED);
		assertThat(report.message()).contains("LINKR_TICKETMASTER_API_KEY");
		assertThat(api.requestedPages).isEmpty();
		assertThat(existing.calls).isZero();
	}

	// ---------- percorso normale ----------

	@Test
	void aTwoPageRunProducesTheAggregatedReport() {
		String unreadable = "{\"id\":\"broken\",\"dates\":\"not-an-object\"}";
		api.then(page(0, 2, 12,
				valid("a1").json(),
				valid("a2").segment(SPORTS_ID, "Sports").json(),
				valid("a3").status("canceled").json(),
				valid("a4").noLocation().json(),
				valid("a5").coordinates("41.9028", "12.4964").json(),
				valid("a6").segment(MISC_ID, "Miscellaneous").json()));
		api.then(page(1, 2, 12,
				valid("b1").segment(ARTS_ID, "Arts & Theatre").json(),
				valid("b2").status("postponed").json(),
				valid("b3").start("dateTBD", true).json(),
				valid("a1").json(), // ripetuto in un'altra pagina
				valid("b4").put("test", true).json(),
				valid("b5").start("dateTime", "2026-12-30T19:00:00Z").start("localDate", "2026-12-30").json(),
				unreadable));

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.OK);
		assertThat(report.message()).contains("Nothing was imported");
		assertThat(report.counts().received()).isEqualTo(13);
		assertThat(report.counts().validCandidates()).isEqualTo(3);
		assertThat(report.counts().excluded()).isEqualTo(10);
		assertThat(report.counts().skippedAfterLimit()).isZero();
		// i conti tornano sempre
		assertThat(report.counts().received()).isEqualTo(report.counts().validCandidates() + report.counts().excluded()
				+ report.counts().skippedAfterLimit());

		assertThat(report.excluded().total()).isEqualTo(10);
		assertThat(report.excluded().coordinates()).isEqualTo(2);
		assertThat(report.excluded().coordinatesMissingOrInvalid()).isEqualTo(1);
		assertThat(report.excluded().outsideArea()).isEqualTo(1);
		assertThat(report.excluded().date()).isEqualTo(2);
		assertThat(report.excluded().category()).isEqualTo(1);
		assertThat(report.excluded().status()).isEqualTo(2);
		assertThat(report.excluded().other()).isEqualTo(3);
		assertThat(report.excluded().byReason()).containsEntry("STATUS_CANCELLED", 1).containsEntry("STATUS_POSTPONED", 1)
				.containsEntry("COORDINATES_MISSING", 1).containsEntry("OUTSIDE_AREA", 1).containsEntry("CATEGORY_UNRECOGNIZED", 1)
				.containsEntry("DATE_UNRELIABLE", 1).containsEntry("DATE_BEYOND_WINDOW", 1).containsEntry("TEST_EVENT", 1)
				.containsEntry("DUPLICATE_EXTERNAL_ID", 1).containsEntry("UNREADABLE", 1).containsEntry("DATE_PAST", 0);
		assertThat(report.excludedBySegment()).containsExactly(java.util.Map.entry("Miscellaneous", 1));

		assertThat(report.categoryDistribution()).containsEntry(EventCategory.MUSIC, 1).containsEntry(EventCategory.SPORT, 1)
				.containsEntry(EventCategory.CULTURE, 1).containsEntry(EventCategory.FOOD, 0);
		assertThat(report.quality().withDescription()).isEqualTo(3);
		assertThat(report.quality().withImage()).isEqualTo(3);
		assertThat(report.quality().withVenueName()).isEqualTo(3);
		assertThat(report.quality().withSourceUrl()).isEqualTo(3);
		assertThat(report.quality().withEndTime()).isZero();

		assertThat(report.api().calls()).isEqualTo(2);
		assertThat(report.api().pagesFetched()).isEqualTo(2);
		assertThat(report.api().totalAvailableAtSource()).isEqualTo(12);
		assertThat(report.api().truncated()).isFalse();
		assertThat(report.api().stoppedBy()).isEqualTo("SOURCE_EXHAUSTED");
		assertThat(report.api().rateLimited()).isFalse();
		assertThat(report.api().lastEventStartsAt()).isNotNull();
		assertThat(api.requestedPages).containsExactly(0, 1);

		assertThat(report.window().days()).isEqualTo(30);
		assertThat(report.window().from().toInstant()).isEqualTo(NOW);
		assertThat(report.window().to().toInstant()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		assertThat(report.window().from().getOffset().getTotalSeconds()).isEqualTo(2 * 3600); // Europe/Rome in ora legale
		assertThat(report.window().area().minLatitude()).isEqualTo(45.35);
		assertThat(report.limits().maxCandidates()).isEqualTo(200);

		assertThat(report.validSamples()).hasSize(3).extracting(TicketmasterDryRunReport.Sample::category)
				.containsExactlyInAnyOrder("MUSIC", "SPORT", "CULTURE");
		assertThat(report.validSamples().get(0).startsAt().getOffset().getTotalSeconds()).isEqualTo(3600); // 5 novembre: ora solare
		assertThat(report.excludedSamples()).isNotEmpty().allSatisfy(sample -> assertThat(sample.excludedReason()).isNotNull());
		assertThat(report.excludedSamples()).extracting(TicketmasterDryRunReport.Sample::excludedReason)
				.contains("STATUS_CANCELLED", "OUTSIDE_AREA", "CATEGORY_UNRECOGNIZED", "DATE_UNRELIABLE");
	}

	@Test
	void theSerializedReportNeverContainsUrlsOrTheKey() {
		api.then(page(0, 1, 2, valid("a1").json(), valid("a2").status("canceled").json()));

		String json = JsonMapper.builder().build().writeValueAsString(service().run(null, null));

		assertThat(json).doesNotContain(KEY).doesNotContain("http").doesNotContain("ticketm").doesNotContain("apikey").doesNotContain("utm=");
		assertThat(json).contains("\"dryRun\":true").contains("\"status\":\"OK\"");
	}

	@Test
	void sampleTextsFromTheSourceAreCleanedAndTruncated() {
		api.then(page(0, 1, 1, valid("a1").title("x".repeat(500) + "\u0000‮ apikey=SECRET").json()));

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.validSamples().get(0).title()).hasSize(120).endsWith("…").doesNotContain("SECRET");
		assertThat(report.quality().titleLongerThanLimit()).isEqualTo(1);
	}

	@Test
	void sampleCoordinatesAreRoundedToFourDecimals() {
		api.then(page(0, 1, 1, valid("a1").coordinates("45.4350123456", "9.2315987654").json()));

		TicketmasterDryRunReport.Sample sample = service().run(null, null).validSamples().get(0);

		assertThat(sample.latitude()).isEqualTo(45.435);
		assertThat(sample.longitude()).isEqualTo(9.2316);
	}

	@Test
	void anEmptyResultIsAnOkReportAndNeverTouchesTheDatabase() {
		api.then(page(0, 0, 0));

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.OK);
		assertThat(report.counts().received()).isZero();
		assertThat(report.api().stoppedBy()).isEqualTo("SOURCE_EXHAUSTED");
		assertThat(report.potentialDuplicates().checked()).isTrue();
		assertThat(existing.calls).isZero();
	}

	// ---------- limiti ----------

	@Test
	void theCandidateLimitStopsReadingAndReportsTruncation() {
		api.then(page(0, 3, 150, valid("c1").json(), valid("c2").json(), valid("c3").json(), valid("c4").json(), valid("c5").json()));

		TicketmasterDryRunReport report = service("max-candidates", "2").run(null, null);

		assertThat(report.counts().received()).isEqualTo(5);
		assertThat(report.counts().validCandidates()).isEqualTo(2);
		assertThat(report.counts().skippedAfterLimit()).isEqualTo(3);
		assertThat(report.counts().received()).isEqualTo(report.counts().validCandidates() + report.counts().excluded()
				+ report.counts().skippedAfterLimit());
		assertThat(report.api().truncated()).isTrue();
		assertThat(report.api().stoppedBy()).isEqualTo("CANDIDATE_LIMIT");
		assertThat(report.api().totalAvailableAtSource()).isEqualTo(150);
		assertThat(api.requestedPages).containsExactly(0); // non si chiede una seconda pagina inutile
	}

	@Test
	void reachingTheLimitExactlyOnTheLastPageIsNotATruncation() {
		api.then(page(0, 1, 2, valid("c1").json(), valid("c2").json()));

		TicketmasterDryRunReport report = service("max-candidates", "2").run(null, null);

		assertThat(report.counts().validCandidates()).isEqualTo(2);
		assertThat(report.counts().skippedAfterLimit()).isZero();
		assertThat(report.api().truncated()).isFalse();
		assertThat(report.api().stoppedBy()).isEqualTo("SOURCE_EXHAUSTED");
	}

	@Test
	void thePageLimitIsAHardCeiling() {
		for (int i = 0; i < 5; i++) {
			api.then(page(i, 10, 500, valid("p" + i).json()));
		}

		TicketmasterDryRunReport report = service("max-pages", "2").run(null, null);

		assertThat(api.requestedPages).containsExactly(0, 1);
		assertThat(report.api().stoppedBy()).isEqualTo("PAGE_LIMIT");
		assertThat(report.api().truncated()).isTrue();
		assertThat(report.api().pagesFetched()).isEqualTo(2);
	}

	@Test
	void callerCanOnlyLowerTheLimits() {
		api.then(page(0, 1, 1, valid("a1").json()));

		TicketmasterDryRunReport report = service().run(7, 20);

		assertThat(report.window().days()).isEqualTo(7);
		assertThat(report.window().to().toInstant()).isEqualTo(NOW.plus(Duration.ofDays(7)));
		assertThat(report.limits().maxCandidates()).isEqualTo(20);
		assertThat(api.searches).singleElement().satisfies(search -> {
			assertThat(search.from()).isEqualTo(NOW);
			assertThat(search.to()).isEqualTo(NOW.plus(Duration.ofDays(7)));
			assertThat(search.pageSize()).isEqualTo(50);
		});
	}

	@Test
	void parametersOutsideTheConfiguredLimitsAreRejected() {
		TicketmasterDryRunService service = service();

		assertThatThrownBy(() -> service.run(0, null)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("days");
		assertThatThrownBy(() -> service.run(31, null)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("30");
		assertThatThrownBy(() -> service.run(-5, null)).isInstanceOf(InvalidImportParameterException.class);
		assertThatThrownBy(() -> service.run(null, 0)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("maxCandidates");
		assertThatThrownBy(() -> service.run(null, 201)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("200");
		assertThat(api.requestedPages).isEmpty();
	}

	// ---------- errori ----------

	@Test
	void anErrorOnTheFirstPageGivesAFailedReportWithASafeMessage() {
		api.thenFail(Kind.UNAUTHORIZED, 401);

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.FAILED);
		assertThat(report.errors()).singleElement().satisfies(error -> {
			assertThat(error.code()).isEqualTo("UNAUTHORIZED");
			assertThat(error.httpStatus()).isEqualTo(401);
			assertThat(error.page()).isZero();
			assertThat(error.attempts()).isEqualTo(3);
			assertThat(error.message()).isEqualTo(Kind.UNAUTHORIZED.safeMessage()).doesNotContain(KEY);
		});
		assertThat(report.counts().received()).isZero();
		assertThat(report.api().stoppedBy()).isEqualTo("ERROR");
		assertThat(report.message()).contains("failed").contains("Nothing was imported");
		assertThat(existing.calls).isZero();
	}

	@Test
	void anErrorAfterTheFirstPageGivesAPartialReportKeepingWhatWasRead() {
		api.then(page(0, 3, 150, valid("a1").json(), valid("a2").json()));
		api.thenFail(Kind.SERVER_ERROR, 503);

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.PARTIAL);
		assertThat(report.counts().validCandidates()).isEqualTo(2);
		assertThat(report.errors()).singleElement().satisfies(error -> {
			assertThat(error.code()).isEqualTo("SERVER_ERROR");
			assertThat(error.page()).isEqualTo(1);
		});
		assertThat(report.api().stoppedBy()).isEqualTo("ERROR");
		assertThat(report.api().truncated()).isFalse(); // la causa e' l'errore, non un limite
	}

	@Test
	void rateLimitingIsReported() {
		api.rateLimitedResponses = 2;
		api.retryAfter = Duration.ofSeconds(30);
		api.thenFail(Kind.RATE_LIMITED, 429);

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.FAILED);
		assertThat(report.api().rateLimited()).isTrue();
		assertThat(report.api().rateLimitedResponses()).isEqualTo(2);
		assertThat(report.api().retryAfterSeconds()).isEqualTo(30);
		assertThat(report.errors()).extracting(TicketmasterDryRunReport.ApiError::code).containsExactly("RATE_LIMITED");
	}

	@Test
	void anUnexpectedExceptionPropagatesButTheNextRunStillWorks() {
		api.script.add(new IllegalStateException("bug"));
		api.then(page(0, 1, 1, valid("a1").json()));
		TicketmasterDryRunService service = service();

		assertThatThrownBy(() -> service.run(null, null)).isInstanceOf(IllegalStateException.class);
		assertThat(service.run(null, null).status()).isEqualTo(Status.OK); // il blocco e' stato rilasciato
	}

	// ---------- un solo dry-run alla volta ----------

	@Test
	void aSecondRunWhileOneIsInProgressIsRefused() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TicketmasterApi blocking = (search, page, stats) -> {
			inside.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return TicketmasterResponseParser.parse(page(0, 1, 1, valid("a1").json()));
		};
		TicketmasterDryRunService service = new TicketmasterDryRunService(TicketmasterTestData.properties(), blocking, existing, CLOCK);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<TicketmasterDryRunReport> first = executor.submit(() -> service.run(null, null));
			assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> service.run(null, null)).isInstanceOf(ImportAlreadyRunningException.class);

			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.OK);
			assertThat(service.run(null, null).status()).isEqualTo(Status.OK); // finito il primo, si puo' ripartire
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	// ---------- potenziali duplicati ----------

	@Test
	void potentialDuplicatesAreFlaggedButNeverMerged() {
		UUID existingId = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");
		Instant start = Instant.parse("2026-11-05T19:00:00Z");
		existing.events = List.of(
				new ExistingEvent(existingId, "Concerto di Mahmood", 45.4351, 9.2316, start.plusSeconds(1800)),
				new ExistingEvent(UUID.randomUUID(), "Yoga all'alba", 45.4350, 9.2315, start));
		existing.imported = Set.of("d2");
		api.then(page(0, 1, 5,
				valid("d1").title("Mahmood live").json(),
				valid("d2").title("Cena sociale").json(),
				valid("d3").title("Jazz Night Blue Note").json(),
				valid("d4").title("Blue Note: Jazz Night").json(),
				valid("d5").title("Torneo di scacchi").json()));

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.counts().validCandidates()).isEqualTo(5); // segnalati, non esclusi
		assertThat(report.potentialDuplicates().checked()).isTrue();
		assertThat(report.potentialDuplicates().withExistingEvents()).isEqualTo(1);
		assertThat(report.potentialDuplicates().alreadyImported()).isEqualTo(1);
		assertThat(report.potentialDuplicates().withinCandidates()).isEqualTo(1);
		assertThat(report.duplicateSamples()).extracting(TicketmasterDryRunReport.Sample::externalId, TicketmasterDryRunReport.Sample::potentialDuplicateOf)
				.containsExactlyInAnyOrder(
						org.assertj.core.groups.Tuple.tuple("d1", "existing:" + existingId),
						org.assertj.core.groups.Tuple.tuple("d2", "already-imported"),
						org.assertj.core.groups.Tuple.tuple("d4", "candidate:d3"));
		// la ricerca nel database copre l'intera fascia dei candidati, piu' il margine di 3 ore
		assertThat(existing.from).isEqualTo(start.minus(Duration.ofHours(3)));
		assertThat(existing.to).isEqualTo(start.plus(Duration.ofHours(3)));
	}

	@Test
	void aFailedDatabaseCheckDegradesToAPartialReport() {
		existing.failure = new DataAccessResourceFailureException("connection to db-host:5432 refused");
		api.then(page(0, 1, 1, valid("a1").json()));

		TicketmasterDryRunReport report = service().run(null, null);

		assertThat(report.status()).isEqualTo(Status.PARTIAL);
		assertThat(report.counts().validCandidates()).isEqualTo(1);
		assertThat(report.potentialDuplicates().checked()).isFalse();
		assertThat(report.errors()).singleElement().satisfies(error -> {
			assertThat(error.code()).isEqualTo("DUPLICATE_CHECK_FAILED");
			assertThat(error.message()).doesNotContain("db-host"); // nessun dettaglio del database nella risposta
		});
	}

}
