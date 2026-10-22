package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;

import jakarta.persistence.EntityManager;

/**
 * Il dry-run con il database di test (linkr_test_db), l'integrazione ACCESA ma con un client Ticketmaster FINTO: nessuna chiamata
 * reale. Ogni test e' @Transactional (rollback finale), quindi le fotografie "prima/dopo" misurano solo cio' che fa il dry-run.
 *
 * Dimostra: solo l'ADMIN entra; zero scritture (contenuti identici e nessun INSERT/UPDATE/DELETE in nessuna tabella); eventi demo,
 * eventi della community, partecipazioni e event_source invariati; potenziali duplicati individuati leggendo il database vero.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"linkr.ticketmaster.enabled=true",
		"linkr.ticketmaster.api-key=TESTKEY-integration-not-a-real-key",
		"linkr.ticketmaster.min-call-interval=0",
		"linkr.ticketmaster.retry-backoff=0" })
class TicketmasterDryRunIntegrationTest {

	private static final String URL = "/api/admin/import/ticketmaster/dry-run";
	private static final String KEY = "TESTKEY-integration-not-a-real-key";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private ExistingEventsSource existingEvents;

	@MockitoBean
	private TicketmasterApi api;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, entityManager);
		}
		return users;
	}

	private void apiReturns(String pageJson) {
		when(api.fetchEvents(any(), eq(0), any())).thenAnswer(invocation -> {
			CallStats stats = invocation.getArgument(2);
			stats.calls++;
			stats.pagesFetched++;
			return TicketmasterResponseParser.parse(pageJson);
		});
	}

	// ---------- autorizzazione sul contesto vero ----------

	@Test
	void onlyAnAdminCanRunTheDryRun() throws Exception {
		TestUser admin = users().admin("tm_boss");
		TestUser normal = users().user("tm_normal");
		apiReturns(page(0, 1, 0));

		mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.token")).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, normal.bearer())).andExpect(status().isForbidden());
		verifyNoInteractions(api);

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OK"));
	}

	@Test
	void anAdminDemotedAfterLoginLosesAccessWithTheNextToken() throws Exception {
		TestUser admin = users().admin("tm_demoted");
		users().setRole(admin.id(), "USER");
		TestUser again = users().login("tm_demoted");

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, again.bearer())).andExpect(status().isForbidden());
		verifyNoInteractions(api);
	}

	// ---------- zero scritture ----------

	@Test
	void theDryRunWritesNothingAndLeavesDemoCommunityAndParticipationsUntouched() throws Exception {
		TestUser admin = users().admin("tm_writer");
		TestUser fan = users().user("tm_fan");
		Instant soon = Instant.now().plus(10, ChronoUnit.DAYS);

		UUID demo = insertEvent("Festival Demo Navigli", true, "OFFICIAL", null, soon);
		UUID community = insertEvent("Aperitivo della community", false, "COMMUNITY", fan.id(), soon.plus(1, ChronoUnit.DAYS));
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", fan.id()).param("e", community).update();
		jdbc.sql("INSERT INTO event_source (event_id, source, external_id, source_status) VALUES (:e, 'TICKETMASTER', 'tm-already', 'onsale')")
				.param("e", community).update();

		apiReturns(page(0, 1, 5,
				event("tm-new").title("Concerto unico mai visto").startsAt(soon.plus(3, ChronoUnit.DAYS)).json(),
				event("tm-dup").title("Festival Demo Navigli").startsAt(soon).coordinates("45.46", "9.19").json(),
				event("tm-already").title("Qualcosa gia' importato").startsAt(soon.plus(4, ChronoUnit.DAYS)).json(),
				event("tm-cancelled").status("canceled").startsAt(soon.plus(5, ChronoUnit.DAYS)).json(),
				event("tm-nowhere").noLocation().startsAt(soon.plus(6, ChronoUnit.DAYS)).json()));

		Map<String, String> contentsBefore = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesBefore = DatabaseSnapshots.writesInThisTransaction(jdbc);
		assertThat(contentsBefore.get("event_source")).doesNotStartWith("0:"); // c'e' almeno la riga appena inserita

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OK"))
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.counts.received").value(5))
				.andExpect(jsonPath("$.counts.validCandidates").value(3))
				.andExpect(jsonPath("$.excluded.status").value(1))
				.andExpect(jsonPath("$.excluded.coordinates").value(1))
				.andExpect(jsonPath("$.potentialDuplicates.checked").value(true))
				.andExpect(jsonPath("$.potentialDuplicates.withExistingEvents").value(1))
				.andExpect(jsonPath("$.potentialDuplicates.alreadyImported").value(1))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));

		Map<String, String> contentsAfter = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesAfter = DatabaseSnapshots.writesInThisTransaction(jdbc);

		// 1) nessuna tabella ha cambiato contenuto
		assertThat(contentsAfter).isEqualTo(contentsBefore);
		// 2) nessuna tabella ha visto un INSERT, UPDATE o DELETE
		assertThat(writesAfter).isEqualTo(writesBefore);
		// 3) in particolare: eventi demo e community, partecipazioni e event_source
		assertThat(contentsAfter.get("event")).isEqualTo(contentsBefore.get("event"));
		assertThat(contentsAfter.get("event_participation")).isEqualTo(contentsBefore.get("event_participation"));
		assertThat(contentsAfter.get("event_source")).isEqualTo(contentsBefore.get("event_source"));
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE id IN (:a, :b)").param("a", demo).param("b", community).query(Long.class).single())
				.isEqualTo(2L);
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE title IN ('Concerto unico mai visto', 'Qualcosa gia'' importato')")
				.query(Long.class).single()).isZero(); // nessun evento di Ticketmaster e' stato creato
	}

	@Test
	void aFailedUpstreamCallAlsoWritesNothing() throws Exception {
		TestUser admin = users().admin("tm_failing");
		insertEvent("Evento qualunque", false, "COMMUNITY", admin.id(), Instant.now().plus(3, ChronoUnit.DAYS));
		when(api.fetchEvents(any(), anyInt(), any())).thenThrow(new TicketmasterException(Kind.UNAUTHORIZED, 401, null));
		Map<String, String> contentsBefore = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesBefore = DatabaseSnapshots.writesInThisTransaction(jdbc);

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.errors[0].code").value("UNAUTHORIZED"))
				.andExpect(jsonPath("$.errors[0].httpStatus").value(401));

		assertThat(DatabaseSnapshots.contents(jdbc)).isEqualTo(contentsBefore);
		assertThat(DatabaseSnapshots.writesInThisTransaction(jdbc)).isEqualTo(writesBefore);
	}

	@Test
	void theResponseNeverContainsTheKeyOrAnyUrl() throws Exception {
		TestUser admin = users().admin("tm_secret");
		apiReturns(page(0, 1, 1, event("tm-1").startsAt(Instant.now().plus(5, ChronoUnit.DAYS)).json()));

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.counts.validCandidates").value(1))
				.andExpect(content().string(not(containsString(KEY))))
				.andExpect(content().string(not(containsString("http"))))
				.andExpect(content().string(not(containsString("ticketm"))));
	}

	@Test
	void parametersOutsideTheLimitsAreA400AndNothingIsRequested() throws Exception {
		TestUser admin = users().admin("tm_params");

		mockMvc.perform(post(URL).param("days", "0").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid parameters"));
		mockMvc.perform(post(URL).param("maxCandidates", "100000").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(api);
	}

	// ---------- la lettura degli eventi esistenti (JdbcExistingEventsSource) ----------

	@Test
	void existingEventsSourceReturnsOnlyEventsThatAreNotDraftsAndInRange() {
		Instant base = Instant.now().plus(20, ChronoUnit.DAYS);
		UUID published = insertEvent("Pubblicato", false, "COMMUNITY", null, base);
		UUID cancelled = insertEvent("Annullato", false, "COMMUNITY", null, base.plus(1, ChronoUnit.HOURS));
		jdbc.sql("UPDATE event SET status = 'CANCELLED' WHERE id = :id").param("id", cancelled).update();
		UUID draft = insertEvent("Bozza", false, "COMMUNITY", null, base.plus(2, ChronoUnit.HOURS));
		jdbc.sql("UPDATE event SET status = 'DRAFT' WHERE id = :id").param("id", draft).update();
		UUID outside = insertEvent("Troppo lontano", false, "COMMUNITY", null, base.plus(10, ChronoUnit.DAYS));

		List<ExistingEventsSource.ExistingEvent> found = existingEvents.eventsStartingBetween(base.minus(1, ChronoUnit.HOURS),
				base.plus(5, ChronoUnit.HOURS));

		assertThat(found).extracting(ExistingEventsSource.ExistingEvent::id).containsExactly(published, cancelled)
				.doesNotContain(draft, outside);
		assertThat(found.get(0).title()).isEqualTo("Pubblicato");
		assertThat(found.get(0).latitude()).isEqualTo(45.46);
		assertThat(found.get(0).startsAt()).isCloseTo(base, within(1, ChronoUnit.MILLIS));
	}

	@Test
	void importedExternalIdsOnlyReportsTicketmasterLinksAndHandlesBigAndEmptyLists() {
		UUID eventId = insertEvent("Importato", false, "COMMUNITY", null, Instant.now().plus(2, ChronoUnit.DAYS));
		jdbc.sql("INSERT INTO event_source (event_id, source, external_id) VALUES (:e, 'TICKETMASTER', 'known-1')").param("e", eventId).update();

		assertThat(existingEvents.importedExternalIds(List.of())).isEmpty();
		assertThat(existingEvents.importedExternalIds(List.of("known-1", "unknown"))).containsExactly("known-1");

		List<String> many = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 1300).mapToObj(i -> "bulk-" + i).toList());
		many.add("known-1");
		assertThat(existingEvents.importedExternalIds(many)).containsExactly("known-1"); // oltre 1000 id: letti a blocchi
	}

	private UUID insertEvent(String title, boolean demo, String type, UUID createdBy, Instant start) {
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo, event_type, created_by)
				VALUES (:title, 'Creato da un test', 'MUSIC', 'PUBLISHED', :start, :end, 'Sede di prova', 'Via di prova',
				        'Test', 45.46, 9.19, :demo, :type, :createdBy) RETURNING id
				""")
				.param("title", title)
				.param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plus(2, ChronoUnit.HOURS)))
				.param("demo", demo)
				.param("type", type)
				.param("createdBy", createdBy, Types.OTHER)
				.query(UUID.class).single();
	}

}
