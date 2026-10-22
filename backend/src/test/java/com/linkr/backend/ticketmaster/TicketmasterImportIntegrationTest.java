package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.ARTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.SPORTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;

import jakarta.persistence.EntityManager;

/**
 * L'importazione sul database di test, con l'integrazione e l'importazione ACCESE ma con un client Ticketmaster FINTO (nessuna
 * chiamata reale). Ogni test e' @Transactional: tutto viene annullato dal rollback finale.
 *
 * Dimostra, sul database vero: autorizzazione ADMIN; eventi creati OFFICIAL/PUBLISHED/non demo/senza creatore/senza immagine e collegati
 * a Ticketmaster; visibili in GET /api/events con la loro origine; idempotenza; eventi demo, community e ufficiali creati a mano mai toccati
 * (contenuti identici e nessuna UPDATE/DELETE); partecipazioni conservate; modifiche e annullamenti fatti a mano rispettati
 * (con le vere API di modifica e annullamento); nessuna scrittura se la lettura da Ticketmaster fallisce.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"linkr.ticketmaster.enabled=true",
		"linkr.ticketmaster.import-enabled=true",
		"linkr.ticketmaster.api-key=TESTKEY-integration-not-a-real-key",
		"linkr.ticketmaster.min-call-interval=0",
		"linkr.ticketmaster.retry-backoff=0" })
class TicketmasterImportIntegrationTest {

	private static final String URL = "/api/admin/import/ticketmaster";
	private static final String KEY = "TESTKEY-integration-not-a-real-key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private TicketmasterApi api;

	private final AtomicReference<String> currentPage = new AtomicReference<>();
	private boolean stubbed;
	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, entityManager);
		}
		return users;
	}

	/** Il client finto risponde sempre con la pagina corrente (cambiabile tra un'importazione e l'altra). */
	private void apiReturns(String pageJson) {
		currentPage.set(pageJson);
		if (stubbed) {
			return;   // ri-stubbare un mock chiama lo stub esistente con argomenti nulli: basta cambiare la pagina
		}
		stubbed = true;
		when(api.fetchEvents(any(), eq(0), any())).thenAnswer(invocation -> {
			CallStats stats = invocation.getArgument(2);
			stats.calls++;
			stats.pagesFetched++;
			return TicketmasterResponseParser.parse(currentPage.get());
		});
	}

	private static Instant inDays(long days) {
		return Instant.now().truncatedTo(ChronoUnit.SECONDS).plus(days, ChronoUnit.DAYS);
	}

	private static String pageOf(TicketmasterTestData.EventJson... events) {
		String[] jsons = new String[events.length];
		for (int i = 0; i < events.length; i++) {
			jsons[i] = events[i].json();
		}
		return page(0, 1, events.length, jsons);
	}

	private String importAs(TestUser admin, String query) throws Exception {
		return mockMvc.perform(post(URL + query).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private UUID insertEvent(String title, boolean demo, String type, UUID createdBy, Instant start) {
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo, event_type, created_by)
				VALUES (:title, 'Descrizione scritta a mano', 'MUSIC', 'PUBLISHED', :start, :end, 'Sede di prova', 'Via di prova',
				        'Isola', 45.4350, 9.2315, :demo, :type, :createdBy) RETURNING id
				""")
				.param("title", title)
				.param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plus(2, ChronoUnit.HOURS)))
				.param("demo", demo)
				.param("type", type)
				.param("createdBy", createdBy, Types.OTHER)
				.query(UUID.class).single();
	}

	/** I timestamptz letti con singleRow() arrivano come Timestamp (o OffsetDateTime a seconda del driver). */
	private static Instant instant(Object value) {
		return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((java.time.OffsetDateTime) value).toInstant();
	}

	private long[] writes(String table) {
		String[] parts = DatabaseSnapshots.writesInThisTransaction(jdbc).get(table).split("/");
		return new long[] { Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]) };
	}

	private String rowFingerprint(String table, UUID id) {
		return jdbc.sql("SELECT md5(t::text) FROM " + table + " t WHERE id = :id").param("id", id).query(String.class).single();
	}

	// ---------- autorizzazione ----------

	@Test
	void onlyAnAdminCanImport() throws Exception {
		TestUser admin = users().admin("tmi_boss");
		TestUser normal = users().user("tmi_normal");
		apiReturns(page(0, 1, 0));

		mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token")).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, normal.bearer())).andExpect(status().isForbidden());
		verifyNoInteractions(api);

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
	}

	@Test
	void parametersOutsideTheConservativeLimitsAreA400AndNothingIsRequested() throws Exception {
		TestUser admin = users().admin("tmi_params");

		mockMvc.perform(post(URL).param("maxCandidates", "51").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
		mockMvc.perform(post(URL).param("days", "0").header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().isBadRequest());
		verifyNoInteractions(api);
	}

	// ---------- creazione e visibilita' in Discover ----------

	@Test
	void importedEventsAreOfficialTicketmasterEventsWithoutImagesAndAppearInDiscover() throws Exception {
		TestUser admin = users().admin("tmi_creator");
		apiReturns(pageOf(
				event("tm-music").title("Concerto Alfa").startsAt(inDays(5)),
				event("tm-sport").title("Olimpia Milano - Virtus").segment(SPORTS_ID, "Sports").startsAt(inDays(6)),
				event("tm-art").title("Mostra Beta").segment(ARTS_ID, "Arts & Theatre").startsAt(inDays(7))));
		UUID community = insertEvent("Aperitivo della community", false, "COMMUNITY", users().user("tmi_fan").id(), inDays(3));

		String body = importAs(admin, "?maxCandidates=10");

		assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("OK");
		assertThat((Integer) JsonPath.read(body, "$.result.created")).isEqualTo(3);
		assertThat((Boolean) JsonPath.read(body, "$.dryRun")).isFalse();
		Map<String, Object> row = jdbc.sql("""
				SELECT e.title, e.description, e.category, e.status, e.event_type, e.demo, e.created_by, e.image_url, e.image_alt, e.image_credit,
				       e.venue_name, e.address, e.neighbourhood, e.latitude, e.longitude,
				       s.source, s.external_id, s.source_url, s.source_status, s.last_seen_at, s.created_at AS s_created, s.updated_at AS s_updated,
				       e.updated_at AS e_updated
				  FROM event e JOIN event_source s ON s.event_id = e.id WHERE s.external_id = 'tm-music'
				""").query().singleRow();
		assertThat(row).containsEntry("title", "Concerto Alfa").containsEntry("category", "MUSIC").containsEntry("status", "PUBLISHED")
				.containsEntry("event_type", "OFFICIAL").containsEntry("demo", false).containsEntry("created_by", null)
				.containsEntry("image_url", null).containsEntry("image_alt", null).containsEntry("image_credit", null)
				.containsEntry("venue_name", "Fabrique").containsEntry("address", "Via Fantoli 9").containsEntry("neighbourhood", "Milano")
				.containsEntry("source", "TICKETMASTER").containsEntry("external_id", "tm-music")
				.containsEntry("source_url", "https://www.ticketmaster.it/event/tm-music?utm=secret-looking").containsEntry("source_status", "onsale");
		assertThat((String) row.get("description")).startsWith("Evento in programma presso Fabrique.").contains("pagina ufficiale su Ticketmaster")
				.doesNotContain("musica dal vivo");
		assertThat(row.get("last_seen_at")).isNotNull();
		assertThat(row.get("s_updated")).as("evento e collegamento portano lo stesso momento di sincronizzazione").isEqualTo(row.get("e_updated"));

		// GET /api/events (pubblico, senza token): gli eventi importati ci sono, con l'origine; quelli della community no
		String list = mockMvc.perform(get("/api/events")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<String>>read(list, "$[?(@.source.provider == 'TICKETMASTER')].title")).containsExactlyInAnyOrder("Concerto Alfa",
				"Olimpia Milano - Virtus", "Mostra Beta");
		assertThat(JsonPath.<java.util.List<String>>read(list, "$[?(@.source.provider == 'TICKETMASTER')].type")).containsOnly("OFFICIAL");
		assertThat(JsonPath.<java.util.List<Object>>read(list, "$[?(@.source.provider == 'TICKETMASTER' && @.imageUrl != null)]")).isEmpty();
		assertThat(JsonPath.<java.util.List<String>>read(list, "$[?(@.source.provider == 'TICKETMASTER')].source.url"))
				.allMatch(url -> url.startsWith("https://www.ticketmaster.it/event/"));
		assertThat(JsonPath.<java.util.List<String>>read(list, "$[?(@.id == '" + community + "')].title")).containsExactly("Aperitivo della community");
		assertThat(JsonPath.<java.util.List<Object>>read(list, "$[?(@.id == '" + community + "' && @.source != null)]")).isEmpty();

		// dettaglio pubblico
		UUID id = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-music'").query(UUID.class).single();
		mockMvc.perform(get("/api/events/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source.provider").value("TICKETMASTER"))
				.andExpect(jsonPath("$.source.url").value("https://www.ticketmaster.it/event/tm-music?utm=secret-looking"))
				.andExpect(jsonPath("$.type").value("OFFICIAL"))
				.andExpect(jsonPath("$.creator").doesNotExist())
				.andExpect(jsonPath("$.canEdit").value(false))
				.andExpect(jsonPath("$.imageUrl").doesNotExist());
		mockMvc.perform(get("/api/events/{id}", community)).andExpect(status().isOk()).andExpect(jsonPath("$.source").doesNotExist());
	}

	@Test
	void theResponseNeverContainsTheKeyOrAnyUrl() throws Exception {
		TestUser admin = users().admin("tmi_secret");
		apiReturns(pageOf(event("tm-1").startsAt(inDays(5))));

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(content().string(not(containsString(KEY))))
				.andExpect(content().string(not(containsString("http"))))
				.andExpect(content().string(not(containsString("ticketm"))));
	}

	// ---------- idempotenza ----------

	@Test
	void runningTheSameImportAgainCreatesNothing() throws Exception {
		TestUser admin = users().admin("tmi_again");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5)), event("tm-b").title("Concerto Beta").startsAt(inDays(6))));

		importAs(admin, "");
		long events = jdbc.sql("SELECT count(*) FROM event").query(Long.class).single();
		long links = jdbc.sql("SELECT count(*) FROM event_source").query(Long.class).single();
		UUID idA = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-a'").query(UUID.class).single();
		String fingerprintBefore = rowFingerprint("event", idA);
		long[] eventWritesBefore = writes("event");

		String second = importAs(admin, "");

		assertThat((Integer) JsonPath.read(second, "$.result.created")).isZero();
		assertThat((Integer) JsonPath.read(second, "$.result.unchanged")).isEqualTo(2);
		assertThat((Integer) JsonPath.read(second, "$.result.updated")).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM event").query(Long.class).single()).isEqualTo(events);
		assertThat(jdbc.sql("SELECT count(*) FROM event_source").query(Long.class).single()).isEqualTo(links);
		assertThat(rowFingerprint("event", idA)).as("l'evento non e' stato riscritto").isEqualTo(fingerprintBefore);
		assertThat(writes("event")).as("nessuna INSERT/UPDATE/DELETE su event alla seconda importazione").isEqualTo(eventWritesBefore);
	}

	@Test
	void theSourceCanChangeAnUntouchedEventButOnlyTheAllowedFields() throws Exception {
		TestUser admin = users().admin("tmi_follow");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5))));
		importAs(admin, "");
		UUID id = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-a'").query(UUID.class).single();
		Map<String, Object> before = jdbc.sql("SELECT status, event_type, demo, created_by, image_url, created_at FROM event WHERE id = :id")
				.param("id", id).query().singleRow();
		Instant newStart = inDays(9);
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa - Nuova Data").startsAt(newStart).venueName("Nuova Sede")));

		String second = importAs(admin, "");

		assertThat((Integer) JsonPath.read(second, "$.result.updated")).isEqualTo(1);
		Map<String, Object> after = jdbc.sql("""
				SELECT e.title, e.venue_name, e.starts_at, e.status, e.event_type, e.demo, e.created_by, e.image_url, e.created_at, e.updated_at,
				       s.updated_at AS s_updated, s.last_seen_at
				  FROM event e JOIN event_source s ON s.event_id = e.id WHERE e.id = :id
				""").param("id", id).query().singleRow();
		assertThat(after).containsEntry("title", "Concerto Alfa - Nuova Data").containsEntry("venue_name", "Nuova Sede");
		assertThat(instant(after.get("starts_at"))).isEqualTo(newStart);
		// i campi che l'importazione non possiede non cambiano
		assertThat(after).containsEntry("status", before.get("status")).containsEntry("event_type", before.get("event_type"))
				.containsEntry("demo", before.get("demo")).containsEntry("created_by", before.get("created_by"))
				.containsEntry("image_url", before.get("image_url")).containsEntry("created_at", before.get("created_at"));
		assertThat(after.get("s_updated")).isEqualTo(after.get("updated_at"));
	}

	// ---------- eventi esistenti e partecipazioni ----------

	@Test
	void demoCommunityAndManualOfficialEventsAndParticipationsAreNeverTouched() throws Exception {
		TestUser admin = users().admin("tmi_safe");
		TestUser fan = users().user("tmi_safe_fan");
		UUID demo = insertEvent("Evento demo Navigli", true, "OFFICIAL", null, inDays(4));
		UUID manualOfficial = insertEvent("Evento ufficiale curato a mano", false, "OFFICIAL", null, inDays(4));
		UUID community = insertEvent("Aperitivo della community", false, "COMMUNITY", fan.id(), inDays(4));
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", fan.id()).param("e", community).update();
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status) VALUES (:u, :e, 'INTERESTED')").param("u", fan.id()).param("e", demo).update();
		apiReturns(pageOf(
				event("tm-a").title("Concerto Alfa").startsAt(inDays(5)),
				// stesso titolo/orario/sede di quelli esistenti, ma ids Ticketmaster diversi: NON devono sovrascriverli
				event("tm-clash").title("Aperitivo della community").startsAt(inDays(4)).coordinates("45.4350", "9.2315")));

		Map<String, String> before = DatabaseSnapshots.contents(jdbc);
		String demoRow = rowFingerprint("event", demo);
		String manualRow = rowFingerprint("event", manualOfficial);
		String communityRow = rowFingerprint("event", community);
		long[] eventWrites = writes("event");
		long[] participationWrites = writes("event_participation");
		long[] userWrites = writes("app_user");
		long[] sourceWrites = writes("event_source");

		String body = importAs(admin, "");

		assertThat((Integer) JsonPath.read(body, "$.result.created")).isEqualTo(1);
		assertThat(rowFingerprint("event", demo)).isEqualTo(demoRow);
		assertThat(rowFingerprint("event", manualOfficial)).isEqualTo(manualRow);
		assertThat(rowFingerprint("event", community)).isEqualTo(communityRow);
		Map<String, String> after = DatabaseSnapshots.contents(jdbc);
		assertThat(after.get("event_participation")).as("partecipazioni identiche").isEqualTo(before.get("event_participation"));
		assertThat(after.get("app_user")).isEqualTo(before.get("app_user"));
		assertThat(after.get("link_request")).isEqualTo(before.get("link_request"));
		assertThat(after.get("chat_message")).isEqualTo(before.get("chat_message"));
		// a livello di database: solo INSERT su event (1 evento) e event_source (1 collegamento); nessuna UPDATE o DELETE da nessuna parte
		long[] eventWritesAfter = writes("event");
		assertThat(eventWritesAfter[0] - eventWrites[0]).as("INSERT su event").isEqualTo(1);
		assertThat(eventWritesAfter[1] - eventWrites[1]).as("UPDATE su event").isZero();
		assertThat(eventWritesAfter[2] - eventWrites[2]).as("DELETE su event").isZero();
		assertThat(writes("event_participation")).isEqualTo(participationWrites);
		assertThat(writes("app_user")).isEqualTo(userWrites);
		// i contatori possono includere anche operazioni gia' fatte da transazioni precedenti sulla stessa connessione: contano solo le differenze
		long[] sourceWritesAfter = writes("event_source");
		assertThat(sourceWritesAfter[0] - sourceWrites[0]).as("INSERT su event_source").isEqualTo(1);
		assertThat(sourceWritesAfter[1] - sourceWrites[1]).as("UPDATE su event_source").isZero();
		assertThat(sourceWritesAfter[2] - sourceWrites[2]).as("DELETE su event_source").isZero();
	}

	@Test
	void anObviousDuplicateOfAnExistingEventIsSkippedAndTheExistingOneIsLeftAlone() throws Exception {
		TestUser admin = users().admin("tmi_dup");
		Instant start = inDays(5);
		UUID manual = insertEvent("Concerto Alfa", false, "OFFICIAL", null, start);
		String manualRow = rowFingerprint("event", manual);
		apiReturns(pageOf(event("tm-dup").title("Concerto Alfa").startsAt(start), event("tm-other").title("Concerto Beta").startsAt(start)));

		String body = importAs(admin, "");

		assertThat((Integer) JsonPath.read(body, "$.result.created")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(body, "$.result.skippedDuplicates")).isEqualTo(1);
		assertThat(JsonPath.<java.util.List<String>>read(body, "$.items[?(@.externalId == 'tm-dup')].reason")).containsExactly("DUPLICATE_OF_EXISTING_EVENT");
		assertThat(rowFingerprint("event", manual)).isEqualTo(manualRow);
		assertThat(jdbc.sql("SELECT count(*) FROM event_source WHERE external_id = 'tm-dup'").query(Long.class).single()).isZero();
	}

	@Test
	void updatingAnImportedEventKeepsTheParticipationsOfThePeopleGoingToIt() throws Exception {
		TestUser admin = users().admin("tmi_keep");
		TestUser fan = users().user("tmi_keep_fan");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5))));
		importAs(admin, "");
		UUID id = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-a'").query(UUID.class).single();
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", fan.id()).param("e", id).update();
		String participationBefore = DatabaseSnapshots.contents(jdbc).get("event_participation");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa - Nuova Data").startsAt(inDays(8))));

		String second = importAs(admin, "");

		assertThat((Integer) JsonPath.read(second, "$.result.updated")).isEqualTo(1);
		assertThat(DatabaseSnapshots.contents(jdbc).get("event_participation")).isEqualTo(participationBefore);
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(1L);
		// e la lista "i miei eventi salvati" porta l'origine dell'evento importato
		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, fan.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.participations[0].event.title").value("Concerto Alfa - Nuova Data"))
				.andExpect(jsonPath("$.participations[0].event.source.provider").value("TICKETMASTER"));
	}

	// ---------- modifiche e annullamenti fatti a mano, con le API vere ----------

	@Test
	void editsAndCancellationsMadeInLinkrAreRespectedByLaterImports() throws Exception {
		TestUser admin = users().admin("tmi_editor");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5))));
		importAs(admin, "");
		UUID id = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-a'").query(UUID.class).single();

		// 1) un admin modifica l'evento dall'interfaccia (PUT /api/events/{id}): da ora e' "suo"
		Instant adminStart = inDays(10);
		String edit = """
				{"title":"Titolo scelto da un admin","description":"Descrizione scritta da un admin","category":"MUSIC","startsAt":"%s",
				 "venueName":"Sede scelta dall'admin","address":"Via Scelta 1","neighbourhood":"Isola","latitude":45.48,"longitude":9.19}
				""".formatted(adminStart.atZone(ZoneId.of("Europe/Rome")).toOffsetDateTime());
		mockMvc.perform(put("/api/events/{id}", id).header(HttpHeaders.AUTHORIZATION, admin.bearer()).contentType(MediaType.APPLICATION_JSON).content(edit))
				.andExpect(status().isOk());
		entityManager.flush();
		entityManager.clear();

		apiReturns(pageOf(event("tm-a").title("Titolo cambiato dalla fonte").startsAt(inDays(12)).venueName("Sede della fonte")));
		String afterEdit = importAs(admin, "");

		assertThat((Integer) JsonPath.read(afterEdit, "$.result.keptLocalEdits")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(afterEdit, "$.result.updated")).isZero();
		assertThat(JsonPath.<java.util.List<String>>read(afterEdit, "$.items[?(@.externalId == 'tm-a')].reason")).containsExactly("EDITED_IN_LINKR");
		Map<String, Object> row = jdbc.sql("SELECT title, venue_name, description, starts_at FROM event WHERE id = :id").param("id", id).query().singleRow();
		assertThat(row).containsEntry("title", "Titolo scelto da un admin").containsEntry("venue_name", "Sede scelta dall'admin")
				.containsEntry("description", "Descrizione scritta da un admin");
		assertThat(instant(row.get("starts_at"))).isEqualTo(adminStart);
		// "visto ora" e stato dell'annuncio si aggiornano comunque, il link resta
		assertThat(jdbc.sql("SELECT source_url FROM event_source WHERE external_id = 'tm-a'").query(String.class).single()).startsWith("https://");

		// 2) lo stesso evento viene annullato dall'interfaccia (DELETE /api/events/{id}): non si riattiva mai piu'
		mockMvc.perform(delete("/api/events/{id}", id).header(HttpHeaders.AUTHORIZATION, admin.bearer())).andExpect(status().is2xxSuccessful());
		entityManager.flush();
		entityManager.clear();
		String afterCancel = importAs(admin, "");

		assertThat(JsonPath.<java.util.List<String>>read(afterCancel, "$.items[?(@.externalId == 'tm-a')].reason")).containsExactly("CANCELLED_IN_LINKR");
		assertThat(jdbc.sql("SELECT status FROM event WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo("CANCELLED");
		String list = mockMvc.perform(get("/api/events")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<java.util.List<Object>>read(list, "$[?(@.id == '" + id + "')]")).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM event_source WHERE external_id = 'tm-a'").query(Long.class).single()).isEqualTo(1L);   // non ne crea un'altra
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE title LIKE 'Titolo cambiato%'").query(Long.class).single()).isZero();
	}

	// ---------- stato dell'annuncio sulla fonte ----------

	@Test
	void whenTheSourceCancelsAnImportedEventOnlyTheLinkIsUpdated() throws Exception {
		TestUser admin = users().admin("tmi_status");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5))));
		importAs(admin, "");
		UUID id = jdbc.sql("SELECT event_id FROM event_source WHERE external_id = 'tm-a'").query(UUID.class).single();
		String eventRow = rowFingerprint("event", id);
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5)).status("canceled")));

		String second = importAs(admin, "");

		assertThat((Integer) JsonPath.read(second, "$.result.sourceStatusRecorded")).isEqualTo(1);
		assertThat((Integer) JsonPath.read(second, "$.result.created")).isZero();
		assertThat(jdbc.sql("SELECT source_status FROM event_source WHERE external_id = 'tm-a'").query(String.class).single()).isEqualTo("canceled");
		assertThat(rowFingerprint("event", id)).as("l'evento LINKR non e' stato toccato").isEqualTo(eventRow);
	}

	// ---------- errori ----------

	@Test
	void ifTicketmasterCannotBeReadNothingIsWritten() throws Exception {
		TestUser admin = users().admin("tmi_fail");
		insertEvent("Un evento qualunque", false, "COMMUNITY", admin.id(), inDays(3));
		when(api.fetchEvents(any(), anyInt(), any())).thenThrow(new TicketmasterException(Kind.UNAUTHORIZED, 401, null));
		Map<String, String> contentsBefore = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesBefore = DatabaseSnapshots.writesInThisTransaction(jdbc);

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.source.errors[0].code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.result.created").value(0));

		assertThat(DatabaseSnapshots.contents(jdbc)).isEqualTo(contentsBefore);
		assertThat(DatabaseSnapshots.writesInThisTransaction(jdbc)).isEqualTo(writesBefore);
	}

	@Test
	void theStoreOnlyEverWritesTicketmasterLinkedOfficialEvents() throws Exception {
		// Il vincolo UNIQUE(event_id) di V9: un evento ha al massimo un'origine, quindi un collegamento mai ricollegato altrove.
		TestUser admin = users().admin("tmi_unique");
		apiReturns(pageOf(event("tm-a").title("Concerto Alfa").startsAt(inDays(5))));
		importAs(admin, "");

		assertThat(jdbc.sql("""
				SELECT count(*) FROM event e WHERE e.id IN (SELECT event_id FROM event_source)
				   AND NOT (e.event_type = 'OFFICIAL' AND e.demo = false AND e.created_by IS NULL AND e.image_url IS NULL)
				""").query(Long.class).single()).isZero();
	}

}
