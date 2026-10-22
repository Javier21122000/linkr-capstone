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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import tools.jackson.databind.json.JsonMapper;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.ticketmaster.ExistingEventsSource.ExistingEvent;
import com.linkr.backend.ticketmaster.TicketmasterApi.EventSearch;
import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Action;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Item;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Status;
import com.linkr.backend.ticketmaster.TicketmasterImportStore.Linked;
import com.linkr.backend.ticketmaster.TicketmasterTestData.EventJson;

/**
 * La logica dell'importazione senza rete e senza database: client Ticketmaster finto, eventi esistenti finti e uno store IN MEMORIA che
 * replica le regole dello store vero (le stesse regole sono provate sul database da TicketmasterImportIntegrationTest).
 */
class TicketmasterImportServiceTest {

	/** Orologio che si puo' far avanzare: la seconda importazione deve avvenire DOPO la prima. */
	private static final class StepClock extends Clock {

		Instant now = NOW;

		@Override
		public ZoneId getZone() {
			return ZoneId.of("Europe/Rome");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

	}

	private static final class FakeApi implements TicketmasterApi {

		final Deque<Object> script = new ArrayDeque<>();
		final List<EventSearch> searches = new ArrayList<>();
		int requests;

		FakeApi then(String pageJson) {
			script.add(TicketmasterResponseParser.parse(pageJson));
			return this;
		}

		FakeApi thenFail(Kind kind, Integer httpStatus) {
			script.add(new TicketmasterException(kind, httpStatus, null));
			return this;
		}

		@Override
		public EventsPage fetchEvents(EventSearch search, int page, CallStats stats) {
			requests++;
			searches.add(search);
			Object next = script.poll();
			if (next == null) {
				throw new AssertionError("unexpected request for page " + page);
			}
			stats.calls++;
			if (next instanceof RuntimeException failure) {
				throw failure;
			}
			stats.pagesFetched++;
			return (EventsPage) next;
		}

	}

	private static final class FakeExisting implements ExistingEventsSource {

		List<ExistingEvent> events = List.of();
		RuntimeException failure;

		@Override
		public Set<String> importedExternalIds(Collection<String> externalIds) {
			return Set.of();
		}

		@Override
		public List<ExistingEvent> eventsStartingBetween(Instant from, Instant to) {
			if (failure != null) {
				throw failure;
			}
			return events;
		}

	}

	/** Riga di event + event_source; i campi sono quelli che contano per le regole. */
	private static final class Row {

		UUID eventId = UUID.randomUUID();
		ImportedContent content;
		String status = "PUBLISHED";
		String type = "OFFICIAL";
		boolean demo;
		UUID createdBy;
		Instant eventUpdatedAt;
		Instant contentSyncedAt;
		String sourceUrl;
		String sourceStatus;
		Instant lastSeenAt;

	}

	private static final class InMemoryStore implements TicketmasterImportStore {

		final Map<String, Row> rows = new LinkedHashMap<>();
		final List<String> calls = new ArrayList<>();
		String failInsertFor;

		@Override
		public Optional<Linked> findLinked(String externalId) {
			Row r = rows.get(externalId);
			return r == null ? Optional.empty()
					: Optional.of(new Linked(r.eventId, r.content, r.status, r.type, r.demo, r.createdBy, r.eventUpdatedAt, r.contentSyncedAt));
		}

		@Override
		public UUID insertNew(ImportedContent content, String externalId, String sourceUrl, String sourceStatus, Instant syncAt) {
			calls.add("insert:" + externalId);
			if (externalId.equals(failInsertFor)) {
				throw new DataAccessResourceFailureException("connection to db-host lost");
			}
			if (rows.containsKey(externalId)) {
				throw new IllegalStateException("UNIQUE(source, external_id) violated");
			}
			Row r = new Row();
			r.content = content;
			r.eventUpdatedAt = syncAt;
			r.contentSyncedAt = syncAt;
			r.sourceUrl = sourceUrl;
			r.sourceStatus = sourceStatus;
			r.lastSeenAt = syncAt;
			rows.put(externalId, r);
			return r.eventId;
		}

		@Override
		public boolean updateContent(Linked current, ImportedContent content, String sourceUrl, String sourceStatus, Instant syncAt) {
			calls.add("update:" + externalIdOf(current.eventId()));
			Row r = rows.get(externalIdOf(current.eventId()));
			// stesse condizioni della UPDATE vera
			if (!r.eventUpdatedAt.equals(current.eventUpdatedAt()) || !"PUBLISHED".equals(r.status) || !"OFFICIAL".equals(r.type) || r.demo
					|| r.createdBy != null) {
				return false;
			}
			r.content = content;
			r.sourceUrl = sourceUrl;
			r.sourceStatus = sourceStatus;
			r.lastSeenAt = syncAt;
			r.eventUpdatedAt = syncAt;
			r.contentSyncedAt = syncAt;
			return true;
		}

		@Override
		public boolean recordSeen(String externalId, String sourceUrl, String sourceStatus, Instant seenAt) {
			calls.add("seen:" + externalId);
			Row r = rows.get(externalId);
			if (r == null) {
				return false;
			}
			if (sourceUrl != null) {
				r.sourceUrl = sourceUrl;
			}
			if (sourceStatus != null) {
				r.sourceStatus = sourceStatus;
			}
			r.lastSeenAt = seenAt;
			return true;
		}

		private String externalIdOf(UUID eventId) {
			return rows.entrySet().stream().filter(e -> e.getValue().eventId.equals(eventId)).findFirst().orElseThrow().getKey();
		}

		// --- cio' che fanno le persone ---

		void editLocally(String externalId, Instant at, String newTitle) {
			Row r = rows.get(externalId);
			ImportedContent c = r.content;
			r.content = new ImportedContent(newTitle, c.description(), c.category(), c.startsAt(), c.endsAt(), c.venueName(), c.address(),
					c.neighbourhood(), c.latitude(), c.longitude());
			r.eventUpdatedAt = at;
		}

		void cancelLocally(String externalId, Instant at) {
			Row r = rows.get(externalId);
			r.status = "CANCELLED";
			r.eventUpdatedAt = at;
		}

		long countOf(String prefix) {
			return calls.stream().filter(c -> c.startsWith(prefix)).count();
		}

	}

	/** Gestore di transazioni che non fa nulla: qui non c'e' database. */
	private static final class NoTransactions implements PlatformTransactionManager {

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}

	}

	private final StepClock clock = new StepClock();
	private final FakeApi api = new FakeApi();
	private final FakeExisting existing = new FakeExisting();
	private final InMemoryStore store = new InMemoryStore();

	private TicketmasterImportService service(String... properties) {
		TicketmasterProperties props = TicketmasterTestData.properties(properties);
		TicketmasterDryRunService analysis = new TicketmasterDryRunService(props, api, existing, clock);
		return new TicketmasterImportService(props, analysis, store, existing, new NoTransactions(), clock);
	}

	private TicketmasterImportService importer() {
		return service("import-enabled", "true");
	}

	private static String pageOf(EventJson... events) {
		String[] jsons = new String[events.length];
		for (int i = 0; i < events.length; i++) {
			jsons[i] = events[i].json();
		}
		return page(0, 1, events.length, jsons);
	}

	private static EventJson show(String id, String title) {
		return event(id).title(title);
	}

	private static Item item(TicketmasterImportReport report, String externalId) {
		return report.items().stream().filter(i -> externalId.equals(i.externalId())).findFirst().orElseThrow();
	}

	// ---------- interruttori ----------

	@Test
	void whenTheIntegrationIsOffNothingHappens() {
		TicketmasterImportReport report = service("enabled", "false", "import-enabled", "true").run(999, 999);

		assertThat(report.status()).isEqualTo(Status.DISABLED);
		assertThat(report.dryRun()).isFalse();
		assertThat(report.message()).contains("LINKR_TICKETMASTER_ENABLED").contains("nothing was written");
		assertThat(api.requests).isZero();
		assertThat(store.calls).isEmpty();
	}

	@Test
	void withoutAnApiKeyNothingHappens() {
		TicketmasterImportReport report = service("api-key", "", "import-enabled", "true").run(null, null);

		assertThat(report.status()).isEqualTo(Status.NOT_CONFIGURED);
		assertThat(api.requests).isZero();
		assertThat(store.calls).isEmpty();
	}

	@Test
	void theImportHasItsOwnSwitchAndTheDryRunStillWorksWithItOff() {
		api.then(pageOf(show("a1", "Concerto Alfa")));
		TicketmasterImportService service = service();   // enabled + key, ma import-enabled non impostato

		TicketmasterImportReport report = service.run(null, null);

		assertThat(report.status()).isEqualTo(Status.IMPORT_DISABLED);
		assertThat(report.message()).contains("LINKR_TICKETMASTER_IMPORT_ENABLED").contains("dry-run is still available");
		assertThat(api.requests).as("nessuna richiesta a Ticketmaster").isZero();
		assertThat(store.calls).isEmpty();

		// il dry-run (che non scrive) funziona con l'importazione spenta
		TicketmasterDryRunService dryRun = new TicketmasterDryRunService(TicketmasterTestData.properties(), api, existing, clock);
		assertThat(dryRun.run(null, null).status()).isEqualTo(TicketmasterDryRunReport.Status.OK);
		assertThat(store.calls).isEmpty();
	}

	@Test
	void theSwitchesAnswerBeforeParametersAreChecked() {
		assertThat(service().run(0, 0).status()).isEqualTo(Status.IMPORT_DISABLED);
		assertThat(store.calls).isEmpty();
	}

	// ---------- parametri ----------

	@Test
	void parametersAreConservativeAndValidated() {
		TicketmasterImportService service = importer();

		assertThatThrownBy(() -> service.run(0, null)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("days");
		assertThatThrownBy(() -> service.run(31, null)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("30");
		assertThatThrownBy(() -> service.run(null, 0)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("maxCandidates");
		assertThatThrownBy(() -> service.run(null, 51)).isInstanceOf(InvalidImportParameterException.class).hasMessageContaining("50");
		assertThatThrownBy(() -> service.run(null, 200)).isInstanceOf(InvalidImportParameterException.class);   // 200 vale per il dry-run, non qui
		assertThat(api.requests).isZero();
		assertThat(store.calls).isEmpty();
	}

	@Test
	void withoutParametersTheDefaultsAre30DaysAndTwentyEvents() {
		List<EventJson> many = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			many.add(show("e" + i, "Spettacolo numero " + i).startsAt(NOW.plus(Duration.ofDays(2)).plusSeconds(i * 3600L)));
		}
		api.then(pageOf(many.toArray(new EventJson[0])));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.params().days()).isEqualTo(30);
		assertThat(report.params().maxCandidates()).isEqualTo(20);
		assertThat(report.result().created()).isEqualTo(20);
		assertThat(store.rows).hasSize(20);
		assertThat(report.source().counts().skippedAfterLimit()).isEqualTo(5);
		assertThat(api.searches.get(0).to()).isEqualTo(NOW.plus(Duration.ofDays(30)));
	}

	@Test
	void theCallerCanOnlyNarrowTheRun() {
		api.then(pageOf(
				show("a1", "Concerto Alfa").startsAt(NOW.plus(Duration.ofDays(2))),
				show("a2", "Concerto Beta").startsAt(NOW.plus(Duration.ofDays(3))),
				show("a3", "Concerto Gamma").startsAt(NOW.plus(Duration.ofDays(4))),
				show("a4", "Concerto Delta").startsAt(NOW.plus(Duration.ofDays(10)))));

		TicketmasterImportReport report = importer().run(7, 2);

		assertThat(report.params()).isEqualTo(new TicketmasterImportReport.Params(7, 2));
		assertThat(report.result().created()).isEqualTo(2);
		assertThat(store.rows).containsOnlyKeys("a1", "a2");
		assertThat(api.searches.get(0).to()).isEqualTo(NOW.plus(Duration.ofDays(7)));
		// a4 e' oltre i 7 giorni; a3 viene dopo il limite di 2 candidati
		assertThat(report.source().counts().skippedAfterLimit()).isEqualTo(2);
	}

	// ---------- creazione ----------

	@Test
	void validEventsAreCreatedAsOfficialTicketmasterEventsWithoutImagesAndWithANeutralDescription() {
		api.then(pageOf(
				show("m1", "Concerto Alfa"),
				show("s1", "Olimpia Milano - Virtus").segment(SPORTS_ID, "Sports").startsAt(NOW.plus(Duration.ofDays(3))),
				show("c1", "Mostra Beta").segment(ARTS_ID, "Arts & Theatre").startsAt(NOW.plus(Duration.ofDays(4))).city("Rho").noAddress()));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.status()).isEqualTo(Status.OK);
		assertThat(report.result().created()).isEqualTo(3);
		assertThat(report.items()).extracting(Item::action).containsOnly(Action.CREATED);
		Row concert = store.rows.get("m1");
		assertThat(concert.type).isEqualTo("OFFICIAL");
		assertThat(concert.demo).isFalse();
		assertThat(concert.createdBy).isNull();
		assertThat(concert.status).isEqualTo("PUBLISHED");
		assertThat(concert.sourceUrl).isEqualTo("https://www.ticketmaster.it/event/m1?utm=secret-looking");
		assertThat(concert.sourceStatus).isEqualTo("onsale");
		assertThat(concert.lastSeenAt).isEqualTo(NOW);
		assertThat(concert.content.title()).isEqualTo("Concerto Alfa");
		assertThat(concert.content.category()).isEqualTo(EventCategory.MUSIC);
		assertThat(concert.content.startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));
		assertThat(concert.content.venueName()).isEqualTo("Fabrique");
		assertThat(concert.content.address()).isEqualTo("Via Fantoli 9");
		assertThat(concert.content.neighbourhood()).isEqualTo("Milano");
		// descrizione neutra: dice dove e dove trovare le informazioni, non riporta il testo della fonte ne' inventa dettagli
		assertThat(concert.content.description()).isEqualTo("Evento in programma presso Fabrique. Per programma, prezzi e disponibilità consulta la pagina ufficiale su Ticketmaster.")
				.doesNotContain("musica dal vivo");
		assertThat(store.rows.get("s1").content.category()).isEqualTo(EventCategory.SPORT);
		// indirizzo mancante: si ripiega sulla citta' (dato vero), e il "quartiere" e' la citta' della sede
		assertThat(store.rows.get("c1").content.address()).isEqualTo("Rho");
		assertThat(store.rows.get("c1").content.neighbourhood()).isEqualTo("Rho");
	}

	@Test
	void longTextsAreShortenedToTheLinkrColumnLimits() {
		api.then(pageOf(show("a1", "T".repeat(300)).venueName("V".repeat(300)).put("info", "testo della fonte che non si ripubblica")));

		importer().run(null, null);

		ImportedContent c = store.rows.get("a1").content;
		assertThat(c.title()).hasSize(120).endsWith("…");
		assertThat(c.venueName()).hasSize(120);
		assertThat(c.description()).hasSizeLessThan(300).doesNotContain("testo della fonte");
	}

	@Test
	void excludedAndIncompleteEventsAreNeverCreated() {
		api.then(pageOf(
				show("ok", "Concerto Valido"),
				show("canc", "Concerto Annullato").status("canceled"),
				show("post", "Concerto Rinviato").status("postponed"),
				show("resc", "Concerto Ripianificato").status("rescheduled"),
				show("abb", "Abbonamento Stagione 2026"),
				show("pack", "Pacchetto VIP"),
				show("nogeo", "Senza Posizione").noLocation(),
				show("rome", "Concerto a Roma").coordinates("41.9028", "12.4964"),
				show("tbd", "Data da Definire").start("dateTBD", true),
				show("misc", "Fiera Varia").segment(MISC_ID, "Miscellaneous"),
				show("http", "Link Non Sicuro").put("url", "http://www.ticketmaster.it/e"),
				show("nourl", "Senza Link").removeKey("url"),
				show("novenue", "Senza Sede").venueName(null)));

		TicketmasterImportReport report = importer().run(null, 50);

		assertThat(store.rows).containsOnlyKeys("ok");
		assertThat(report.result().created()).isEqualTo(1);
		assertThat(report.result().skippedIncomplete()).isEqualTo(3);
		assertThat(item(report, "http").reason()).isEqualTo("NO_VALID_SOURCE_URL");
		assertThat(item(report, "nourl").reason()).isEqualTo("NO_VALID_SOURCE_URL");
		assertThat(item(report, "novenue").reason()).isEqualTo("NO_VENUE_NAME");
		// conti della fonte: i 13 eventi ricevuti sono 3 incompleti + 1 creato + 9 esclusi dal normalizzatore
		assertThat(report.source().counts().received()).isEqualTo(13);
		assertThat(report.source().counts().validCandidates()).isEqualTo(4);
		assertThat(report.source().excluded().status()).isEqualTo(3);
		assertThat(report.source().excluded().byReason()).containsEntry("NOT_SINGLE_EVENT", 2);
	}

	// ---------- idempotenza ----------

	@Test
	void runningTheSameImportTwiceCreatesNoDuplicates() {
		String same = pageOf(show("a1", "Concerto Alfa"), show("a2", "Concerto Beta").startsAt(NOW.plus(Duration.ofDays(5))));
		api.then(same).then(same);
		TicketmasterImportService service = importer();

		TicketmasterImportReport first = service.run(null, null);
		UUID firstId = store.rows.get("a1").eventId;
		clock.advance(Duration.ofHours(1));
		TicketmasterImportReport second = service.run(null, null);

		assertThat(first.result().created()).isEqualTo(2);
		assertThat(second.result().created()).isZero();
		assertThat(second.result().unchanged()).isEqualTo(2);
		assertThat(second.items()).extracting(Item::action).containsOnly(Action.UNCHANGED);
		assertThat(store.rows).hasSize(2);
		assertThat(store.countOf("insert:")).isEqualTo(2);
		assertThat(store.countOf("update:")).isZero();
		assertThat(store.rows.get("a1").eventId).isEqualTo(firstId);
		// "visto ora" si aggiorna, ma il momento dell'ultima scrittura del contenuto no (serve a riconoscere le modifiche a mano)
		assertThat(store.rows.get("a1").lastSeenAt).isEqualTo(NOW.plus(Duration.ofHours(1)));
		assertThat(store.rows.get("a1").contentSyncedAt).isEqualTo(NOW);
		assertThat(store.rows.get("a1").eventUpdatedAt).isEqualTo(NOW);
	}

	@Test
	void anImportedEventFollowsTheSourceWhileNobodyHasTouchedIt() {
		api.then(pageOf(show("a1", "Concerto Alfa"))).then(pageOf(show("a1", "Concerto Alfa - Nuova Data").startsAt(NOW.plus(Duration.ofDays(9)))));
		TicketmasterImportService service = importer();
		service.run(null, null);
		clock.advance(Duration.ofHours(2));

		TicketmasterImportReport second = service.run(null, null);

		assertThat(second.result().updated()).isEqualTo(1);
		Row row = store.rows.get("a1");
		assertThat(row.content.title()).isEqualTo("Concerto Alfa - Nuova Data");
		assertThat(row.content.startsAt()).isEqualTo(NOW.plus(Duration.ofDays(9)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
		// i campi che l'importazione non possiede restano quelli di prima
		assertThat(row.status).isEqualTo("PUBLISHED");
		assertThat(row.type).isEqualTo("OFFICIAL");
		assertThat(row.demo).isFalse();
		assertThat(row.createdBy).isNull();
		assertThat(row.contentSyncedAt).isEqualTo(NOW.plus(Duration.ofHours(2)));
		assertThat(row.eventUpdatedAt).isEqualTo(row.contentSyncedAt);
		assertThat(store.rows).hasSize(1);
	}

	// ---------- protezione di cio' che e' stato modificato o annullato a mano ----------

	@Test
	void localEditsAreNeverOverwritten() {
		api.then(pageOf(show("a1", "Concerto Alfa"))).then(pageOf(show("a1", "Titolo cambiato dalla fonte").startsAt(NOW.plus(Duration.ofDays(8)))));
		TicketmasterImportService service = importer();
		service.run(null, null);
		store.editLocally("a1", NOW.plus(Duration.ofMinutes(30)), "Titolo scelto da un admin");
		clock.advance(Duration.ofHours(1));

		TicketmasterImportReport second = service.run(null, null);

		assertThat(second.result().keptLocalEdits()).isEqualTo(1);
		assertThat(item(second, "a1").action()).isEqualTo(Action.KEPT_LOCAL_EDITS);
		assertThat(item(second, "a1").reason()).isEqualTo("EDITED_IN_LINKR");
		assertThat(store.rows.get("a1").content.title()).isEqualTo("Titolo scelto da un admin");
		assertThat(store.rows.get("a1").content.startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));   // data della fonte NON applicata
		assertThat(store.countOf("update:")).isZero();
		assertThat(store.rows.get("a1").lastSeenAt).isEqualTo(NOW.plus(Duration.ofHours(1)));                  // ma "visto ora" si
		// e resta protetto anche alle importazioni successive: il marcatore di sincronizzazione non e' avanzato
		api.then(pageOf(show("a1", "Ancora un altro titolo")));
		clock.advance(Duration.ofHours(1));
		assertThat(service.run(null, null).result().keptLocalEdits()).isEqualTo(1);
		assertThat(store.rows.get("a1").content.title()).isEqualTo("Titolo scelto da un admin");
	}

	@Test
	void aCancelledEventIsNeverReactivated() {
		String listing = pageOf(show("a1", "Concerto Alfa"));
		api.then(listing).then(listing);
		TicketmasterImportService service = importer();
		service.run(null, null);
		store.cancelLocally("a1", NOW.plus(Duration.ofMinutes(10)));
		clock.advance(Duration.ofHours(1));

		TicketmasterImportReport second = service.run(null, null);

		assertThat(item(second, "a1").action()).isEqualTo(Action.KEPT_LOCAL_EDITS);
		assertThat(item(second, "a1").reason()).isEqualTo("CANCELLED_IN_LINKR");
		assertThat(store.rows.get("a1").status).isEqualTo("CANCELLED");
		assertThat(store.countOf("update:")).isZero();
		assertThat(store.countOf("insert:")).isEqualTo(1);   // e non ne crea un'altra copia
	}

	@Test
	void aLinkToAnEventThatIsNotAnImportedOneIsNeverTouched() {
		for (String type : List.of("COMMUNITY", "OFFICIAL")) {
			Row row = new Row();
			row.content = new ImportedContent("Evento della community", "Descrizione scritta da una persona", EventCategory.SOCIAL, NOW.plusSeconds(1000),
					null, "Bar", "Via X", "Isola", 45.46, 9.19);
			row.type = type;
			row.createdBy = type.equals("COMMUNITY") ? UUID.randomUUID() : null;
			row.demo = type.equals("OFFICIAL");     // un evento demo ufficiale
			row.eventUpdatedAt = NOW;
			row.contentSyncedAt = NOW;
			store.rows.put("x-" + type, row);
		}
		api.then(pageOf(show("x-COMMUNITY", "Nome diverso"), show("x-OFFICIAL", "Altro nome diverso")));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.items()).extracting(Item::action).containsOnly(Action.KEPT_LOCAL_EDITS);
		assertThat(report.items()).extracting(Item::reason).containsOnly("NOT_AN_IMPORTED_EVENT");
		assertThat(store.countOf("update:")).isZero();
		assertThat(store.countOf("insert:")).isZero();
		assertThat(store.rows.get("x-COMMUNITY").content.title()).isEqualTo("Evento della community");
		assertThat(store.rows.get("x-OFFICIAL").content.title()).isEqualTo("Evento della community");
	}

	@Test
	void nothingIsEverDeletedByDesign() throws Exception {
		assertThat(TicketmasterImportStore.class.getMethods()).extracting(m -> m.getName().toLowerCase())
				.noneMatch(name -> name.contains("delete") || name.contains("remove") || name.contains("purge") || name.contains("cancel"));
		String sql = Files.readString(Path.of("src/main/java/com/linkr/backend/ticketmaster/JdbcTicketmasterImportStore.java")).toUpperCase();
		assertThat(sql).doesNotContain("DELETE").doesNotContain("TRUNCATE").doesNotContain("DROP ").doesNotContain("ALTER ");
		// le uniche UPDATE su event sono limitate a eventi collegati a Ticketmaster e ancora "nostri"
		assertThat(sql).contains("EVENT_TYPE = 'OFFICIAL' AND DEMO = FALSE AND CREATED_BY IS NULL").contains("STATUS = 'PUBLISHED'");
		assertThat(sql).doesNotContain("SET STATUS").doesNotContain(", STATUS =").doesNotContain("IMAGE_URL =").doesNotContain("CREATED_BY =");
	}

	// ---------- duplicati evidenti ----------

	@Test
	void anObviousDuplicateOfAnotherCandidateIsSkippedButSimilarEventsAreKept() {
		api.then(pageOf(
				show("d1", "Jazz Night Blue Note"),
				show("d2", "Jazz Night Blue Note"),                                              // identico: stesso titolo, orario e sede
				show("d3", "Jazz Night Blue Note").startsAt(NOW.plus(Duration.ofDays(20)).plusSeconds(7200)),    // stesso titolo e sede, altro giorno
				show("d4", "Altro spettacolo nella stessa sede"),                               // stessa sede e orario, titolo diverso
				show("d5", "Jazz Night Blue Note").coordinates("45.5000", "9.2315"),            // stesso titolo e orario, altra sede (4 km)
				show("d6", "Jazz Night - Blue Note")));                                         // titolo simile ma non identico... normalizzato e' uguale: duplicato
		TicketmasterImportReport report = importer().run(null, 50);

		assertThat(store.rows).containsOnlyKeys("d1", "d3", "d4", "d5");
		assertThat(item(report, "d2").action()).isEqualTo(Action.SKIPPED_DUPLICATE);
		assertThat(item(report, "d2").reason()).isEqualTo("DUPLICATE_OF_CANDIDATE:d1");
		assertThat(item(report, "d6").action()).isEqualTo(Action.SKIPPED_DUPLICATE);
		assertThat(report.result().created()).isEqualTo(4);
		assertThat(report.result().skippedDuplicates()).isEqualTo(2);
	}

	@Test
	void twoDifferentEventsInTheSameVenueAtTheSameTimeAreBothImported() {
		api.then(pageOf(show("v1", "Concerto del pomeriggio"), show("v2", "Concerto della sera"), show("v3", "Concerto di mezzanotte")));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.result().created()).isEqualTo(3);
		assertThat(report.result().skippedDuplicates()).isZero();
	}

	@Test
	void anObviousDuplicateOfAnExistingLinkrEventIsSkippedButNeverMerged() {
		existing.events = List.of(
				new ExistingEvent(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"), "Concerto Alfa", 45.4350, 9.2315, Instant.parse("2026-11-05T19:00:00Z")),
				new ExistingEvent(UUID.randomUUID(), "Yoga all'alba", 45.4350, 9.2315, Instant.parse("2026-11-05T19:00:00Z")));
		api.then(pageOf(show("e1", "Concerto Alfa"), show("e2", "Concerto Alfa 2")));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(item(report, "e1").action()).isEqualTo(Action.SKIPPED_DUPLICATE);
		assertThat(item(report, "e1").reason()).isEqualTo("DUPLICATE_OF_EXISTING_EVENT");
		assertThat(item(report, "e1").eventId()).isEqualTo(UUID.fromString("018f0000-0000-7000-8000-0000000000aa"));
		assertThat(item(report, "e2").action()).isEqualTo(Action.CREATED);   // simile ma non identico: resta
		assertThat(store.rows).containsOnlyKeys("e2");
	}

	// ---------- errori ----------

	@Test
	void aFailureOnOneEventDoesNotStopTheOthers() {
		api.then(pageOf(show("a1", "Concerto Alfa"), show("a2", "Concerto Beta"), show("a3", "Concerto Gamma")));
		store.failInsertFor = "a2";

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.status()).isEqualTo(Status.PARTIAL);
		assertThat(report.result().created()).isEqualTo(2);
		assertThat(report.result().failed()).isEqualTo(1);
		assertThat(item(report, "a2").action()).isEqualTo(Action.FAILED);
		assertThat(item(report, "a2").reason()).isEqualTo("DATABASE_ERROR").doesNotContain("db-host");
		assertThat(store.rows).containsOnlyKeys("a1", "a3");
	}

	@Test
	void anIncompleteOrFailedReadWritesNothing() {
		api.thenFail(Kind.UNAUTHORIZED, 401);
		TicketmasterImportReport failed = importer().run(null, null);
		assertThat(failed.status()).isEqualTo(Status.FAILED);
		assertThat(failed.message()).contains("Nothing was written");
		assertThat(failed.source().errors()).singleElement().satisfies(e -> assertThat(e.code()).isEqualTo("UNAUTHORIZED"));

		// errore alla seconda pagina: la prima era riuscita, ma si scrive solo con una lettura completa
		api.then(page(0, 2, 60, show("a1", "Concerto Alfa").json())).thenFail(Kind.SERVER_ERROR, 503);
		TicketmasterImportReport partial = importer().run(null, null);
		assertThat(partial.status()).isEqualTo(Status.FAILED);
		assertThat(partial.items()).isEmpty();

		assertThat(store.calls).isEmpty();
		assertThat(store.rows).isEmpty();
	}

	@Test
	void ifExistingEventsCannotBeReadNothingIsWritten() {
		existing.failure = new DataAccessResourceFailureException("connection to db-host:5432 refused");
		api.then(pageOf(show("a1", "Concerto Alfa")));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.status()).isEqualTo(Status.FAILED);
		assertThat(report.source().errors()).extracting(TicketmasterDryRunReport.ApiError::code).contains("DUPLICATE_CHECK_FAILED");
		assertThat(report.source().errors().toString()).doesNotContain("db-host");
		assertThat(store.calls).isEmpty();
	}

	// ---------- stato dell'annuncio sulla fonte ----------

	@Test
	void whenTheSourceCancelsAnImportedEventTheStatusIsRecordedAndTheLinkrEventIsNotTouched() {
		api.then(pageOf(show("a1", "Concerto Alfa"), show("a2", "Concerto Beta")))
				.then(pageOf(show("a1", "Concerto Alfa").status("canceled"), show("a2", "Concerto Beta").status("postponed"),
						show("zz", "Mai importato").status("canceled")));
		TicketmasterImportService service = importer();
		service.run(null, null);
		Row before = store.rows.get("a1");
		Instant contentSyncedBefore = before.contentSyncedAt;
		clock.advance(Duration.ofHours(1));

		TicketmasterImportReport second = service.run(null, null);

		assertThat(second.result().sourceStatusRecorded()).isEqualTo(2);   // "zz" non e' collegato: ignorato
		assertThat(second.items()).extracting(Item::action).containsOnly(Action.SOURCE_STATUS_RECORDED);
		assertThat(store.rows.get("a1").sourceStatus).isEqualTo("canceled");
		assertThat(store.rows.get("a2").sourceStatus).isEqualTo("postponed");
		// l'evento LINKR non e' cambiato: ne' stato, ne' contenuto, ne' marcatore di sincronizzazione
		assertThat(store.rows.get("a1").status).isEqualTo("PUBLISHED");
		assertThat(store.rows.get("a1").contentSyncedAt).isEqualTo(contentSyncedBefore);
		assertThat(store.countOf("update:")).isZero();
		assertThat(store.rows).doesNotContainKey("zz");
	}

	// ---------- un'importazione alla volta, report ----------

	@Test
	void aSecondImportWhileOneIsRunningIsRefused() throws Exception {
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TicketmasterApi blocking = (search, pageNumber, stats) -> {
			inside.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return TicketmasterResponseParser.parse(pageOf(show("a1", "Concerto Alfa")));
		};
		TicketmasterProperties props = TicketmasterTestData.properties("import-enabled", "true");
		TicketmasterImportService service = new TicketmasterImportService(props, new TicketmasterDryRunService(props, blocking, existing, clock), store,
				existing, new NoTransactions(), clock);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<TicketmasterImportReport> first = executor.submit(() -> service.run(null, null));
			assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> service.run(null, null)).isInstanceOf(ImportAlreadyRunningException.class);

			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).status()).isEqualTo(Status.OK);
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void theReportNeverContainsUrlsImagesOrTheKey() {
		api.then(pageOf(show("a1", "Concerto Alfa"), show("a2", "Abbonamento 2026")));

		String json = JsonMapper.builder().build().writeValueAsString(importer().run(null, null));

		assertThat(json).doesNotContain(KEY).doesNotContain("http").doesNotContain("ticketm").doesNotContain("utm=").doesNotContain("apikey");
		assertThat(json).contains("\"status\":\"OK\"").contains("\"dryRun\":false").contains("\"action\":\"CREATED\"");
	}

	@Test
	void importingNothingIsAnOkReport() {
		api.then(page(0, 0, 0));

		TicketmasterImportReport report = importer().run(null, null);

		assertThat(report.status()).isEqualTo(Status.OK);
		assertThat(report.result().created()).isZero();
		assertThat(store.calls).isEmpty();
	}

}
