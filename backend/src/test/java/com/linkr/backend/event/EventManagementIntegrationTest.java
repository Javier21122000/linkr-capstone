package com.linkr.backend.event;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

import jakarta.persistence.EntityManager;

/**
 * Creazione, modifica e annullamento degli eventi, con database di test (linkr_test_db).
 * Ogni test e' @Transactional: tutto viene annullato dal rollback.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class EventManagementIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, entityManager);
		}
		return users;
	}

	// ======================= creazione =======================

	@Test
	void validCreationReturns201AndStoresCreatorTypeStatusFromTheToken() throws Exception {
		TestUser anna = users().user("ev_anna", "Anna");

		String body = create(anna, payload()).andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("Aperitivo sui Navigli"))
				.andExpect(jsonPath("$.category").value("SOCIAL"))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.type").value("COMMUNITY"))
				.andExpect(jsonPath("$.demo").value(false))
				.andExpect(jsonPath("$.canEdit").value(true))
				.andExpect(jsonPath("$.creator.userId").value(anna.id().toString()))
				.andExpect(jsonPath("$.creator.username").value("ev_anna"))
				.andExpect(jsonPath("$.creator.displayName").value("Anna"))
				.andExpect(jsonPath("$.latitude").value(45.4527))
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> creator = JsonPath.read(body, "$.creator");
		assertThat(creator.keySet()).containsExactlyInAnyOrder("userId", "username", "displayName", "avatarUrl");
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("assword");

		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		Map<String, Object> row = jdbc.sql("SELECT created_by, event_type, status, demo FROM event WHERE id = :id")
				.param("id", id).query().singleRow();
		assertThat(row.get("created_by")).isEqualTo(anna.id());
		assertThat(row.get("event_type")).isEqualTo("COMMUNITY");
		assertThat(row.get("status")).isEqualTo("PUBLISHED");
		assertThat(row.get("demo")).isEqualTo(false);
	}

	@Test
	void createdEventAppearsInThePublicListAndDetail() throws Exception {
		TestUser anna = users().user("ev_public", "Anna");
		String id = JsonPath.read(create(anna, payload()).andReturn().getResponse().getContentAsString(), "$.id");

		mockMvc.perform(get("/api/events")).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + id + "')].type").value("COMMUNITY"))
				.andExpect(jsonPath("$[?(@.id=='" + id + "')].status").value("PUBLISHED"));
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isOk())
				.andExpect(jsonPath("$.canEdit").value(false))
				.andExpect(jsonPath("$.creator.username").value("ev_public"));
	}

	@Test
	void creationRequiresAValidJwt() throws Exception {
		mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(json(payload())))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
				.contentType(MediaType.APPLICATION_JSON).content(json(payload()))).andExpect(status().isUnauthorized());
		mockMvc.perform(put("/api/events/" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json(payload())))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/events/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/users/me/events")).andExpect(status().isUnauthorized());
		assertThat(communityEvents()).isZero();
	}

	@Test
	void creatorStatusDemoAndUserIdSentByTheClientAreIgnored() throws Exception {
		TestUser anna = users().user("ev_ignore", "Anna");
		TestUser other = users().user("ev_victim", "Victim");
		Map<String, Object> p = payload();
		p.put("createdBy", other.id().toString());
		p.put("userId", other.id().toString());
		p.put("creatorId", other.id().toString());
		p.put("status", "CANCELLED");
		p.put("demo", true);
		p.put("id", UUID.randomUUID().toString());

		String id = JsonPath.read(create(anna, p).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

		Map<String, Object> row = jdbc.sql("SELECT created_by, status, demo FROM event WHERE id = :id")
				.param("id", UUID.fromString(id)).query().singleRow();
		assertThat(row.get("created_by")).isEqualTo(anna.id());
		assertThat(row.get("status")).isEqualTo("PUBLISHED");
		assertThat(row.get("demo")).isEqualTo(false);
	}

	@Test
	void invalidPayloadsReturn400AndStoreNothing() throws Exception {
		TestUser anna = users().user("ev_invalid", "Anna");
		Instant future = Instant.now().plus(2, ChronoUnit.DAYS);

		List<Map<String, Object>> bad = new ArrayList<>();
		for (String field : List.of("title", "description", "category", "startsAt", "venueName", "address", "neighbourhood",
				"latitude", "longitude")) {
			bad.add(without(field));
		}
		bad.add(with("title", "ab"));                                   // troppo corto
		bad.add(with("title", "   "));
		bad.add(with("title", "x".repeat(121)));
		bad.add(with("description", "x".repeat(5001)));
		bad.add(with("category", "NOT_A_CATEGORY"));
		bad.add(with("category", "music"));                             // maiuscole obbligatorie
		bad.add(with("latitude", 48.85));                               // Parigi: fuori Milano
		bad.add(with("longitude", 2.35));
		bad.add(with("latitude", 999));
		bad.add(with("imageUrl", "http://example.com/a.jpg"));          // solo https
		bad.add(with("imageUrl", "javascript:alert(1)"));
		bad.add(with("imageUrl", "https://example.com/a b.jpg"));
		Map<String, Object> noAlt = with("imageUrl", "https://example.com/a.jpg");   // immagine senza testo alternativo
		bad.add(noAlt);
		bad.add(with("startsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString())); // nel passato
		bad.add(with("startsAt", "domani"));
		Map<String, Object> endBefore = with("endsAt", future.minus(1, ChronoUnit.HOURS).toString());
		endBefore.put("startsAt", future.toString());                   // fine prima dell'inizio
		bad.add(endBefore);
		Map<String, Object> endEqual = with("endsAt", future.toString());
		endEqual.put("startsAt", future.toString());
		bad.add(endEqual);
		Map<String, Object> tooLong = with("endsAt", future.plus(8, ChronoUnit.DAYS).toString());
		tooLong.put("startsAt", future.toString());                     // piu' di 7 giorni
		bad.add(tooLong);
		bad.add(with("type", "WHATEVER"));

		for (Map<String, Object> p : bad) {
			create(anna, p).andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		for (String raw : List.of("", "{not json", "[]", "null")) {
			mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, anna.bearer())
					.contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());
		}
		assertThat(communityEvents()).isZero();
	}

	@Test
	void validationErrorsListTheWrongFieldsWithoutEchoingValues() throws Exception {
		TestUser anna = users().user("ev_errors", "Anna");
		Map<String, Object> p = with("title", "ab");
		p.put("endsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
		p.put("startsAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());

		create(anna, p).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Validation failed"))
				.andExpect(jsonPath("$.errors.title").exists());

		create(anna, with("imageUrl", "https://example.com/a.jpg")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.imageAlt").exists());
		create(anna, with("startsAt", Instant.now().minus(1, ChronoUnit.HOURS).toString())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.startsAt").exists());
	}

	@Test
	void eventWithImageAndOptionalEndIsAccepted() throws Exception {
		TestUser anna = users().user("ev_image", "Anna");
		Map<String, Object> p = payload();
		p.put("imageUrl", "https://example.com/a.jpg");
		p.put("imageAlt", "Tavolo con bicchieri");
		p.put("imageCredit", "Anna");
		p.put("endsAt", Instant.now().plus(2, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS).toString());

		create(anna, p).andExpect(status().isCreated())
				.andExpect(jsonPath("$.imageUrl").value("https://example.com/a.jpg"))
				.andExpect(jsonPath("$.imageAlt").value("Tavolo con bicchieri"))
				.andExpect(jsonPath("$.imageCredit").value("Anna"))
				.andExpect(jsonPath("$.endsAt").exists());
	}

	@Test
	void onlyAdminsCanCreateOfficialEvents() throws Exception {
		TestUser anna = users().user("ev_user_official", "Anna");
		TestUser boss = users().admin("ev_admin_official");

		create(anna, with("type", "OFFICIAL")).andExpect(status().isForbidden());
		create(anna, with("type", "COMMUNITY")).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("COMMUNITY"));
		create(boss, with("type", "OFFICIAL")).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("OFFICIAL"))
				.andExpect(jsonPath("$.demo").value(false));
		create(boss, payload()).andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("COMMUNITY"));
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE event_type = 'OFFICIAL' AND created_by = :id AND NOT demo")
				.param("id", boss.id()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void dailyCreationLimitReturns429ForUsersButNotForAdmins() throws Exception {
		TestUser anna = users().user("ev_limit", "Anna");
		TestUser boss = users().admin("ev_limit_admin");

		for (int i = 0; i < EventManagementService.MAX_CREATED_PER_DAY; i++) {
			create(anna, payload()).andExpect(status().isCreated());
		}
		create(anna, payload()).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.title").value("Too many events"));
		assertThat(communityEventsBy(anna)).isEqualTo(EventManagementService.MAX_CREATED_PER_DAY);

		// il limite e' per utente e per le ultime 24 ore
		TestUser bea = users().user("ev_limit_other", "Bea");
		create(bea, payload()).andExpect(status().isCreated());
		jdbc.sql("UPDATE event SET created_at = now() - interval '25 hours' WHERE created_by = :id").param("id", anna.id()).update();
		create(anna, payload()).andExpect(status().isCreated());

		for (int i = 0; i < EventManagementService.MAX_CREATED_PER_DAY + 1; i++) {
			create(boss, payload()).andExpect(status().isCreated());
		}
	}

	// ======================= modifica =======================

	@Test
	void creatorCanReplaceTheContentsAndNothingElseChanges() throws Exception {
		TestUser anna = users().user("ev_edit", "Anna");
		String id = createOk(anna, payload());

		Map<String, Object> edit = payload();
		edit.put("title", "Aperitivo aggiornato");
		edit.put("description", "Nuova descrizione");
		edit.put("category", "FOOD");
		edit.put("venueName", "Altro posto");
		edit.put("startsAt", Instant.now().plus(5, ChronoUnit.DAYS).toString());
		edit.put("createdBy", UUID.randomUUID().toString());
		edit.put("status", "CANCELLED");
		edit.put("demo", true);

		update(anna, id, edit).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Aperitivo aggiornato"))
				.andExpect(jsonPath("$.category").value("FOOD"))
				.andExpect(jsonPath("$.venueName").value("Altro posto"))
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.type").value("COMMUNITY"))
				.andExpect(jsonPath("$.canEdit").value(true))
				.andExpect(jsonPath("$.creator.userId").value(anna.id().toString()));

		Map<String, Object> row = jdbc.sql("SELECT title, created_by, status, demo, event_type FROM event WHERE id = :id")
				.param("id", UUID.fromString(id)).query().singleRow();
		assertThat(row.get("title")).isEqualTo("Aperitivo aggiornato");
		assertThat(row.get("created_by")).isEqualTo(anna.id());
		assertThat(row.get("status")).isEqualTo("PUBLISHED");
		assertThat(row.get("demo")).isEqualTo(false);
		assertThat(row.get("event_type")).isEqualTo("COMMUNITY");
	}

	@Test
	void anotherUserCannotEditAndNothingChanges() throws Exception {
		TestUser anna = users().user("ev_owner", "Anna");
		TestUser mallory = users().user("ev_mallory", "Mallory");
		String id = createOk(anna, payload());

		update(mallory, id, with("title", "Hacked title")).andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		cancel(mallory, id).andExpect(status().isForbidden());

		assertThat(storedTitle(id)).isEqualTo("Aperitivo sui Navigli");
		assertThat(storedStatus(id)).isEqualTo("PUBLISHED");
	}

	@Test
	void adminCanEditAndCancelAnyEventAndChangeItsType() throws Exception {
		TestUser anna = users().user("ev_by_user", "Anna");
		TestUser boss = users().admin("ev_boss");
		String id = createOk(anna, payload());

		update(boss, id, with("title", "Edited by admin")).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Edited by admin"))
				.andExpect(jsonPath("$.canEdit").value(true))
				.andExpect(jsonPath("$.creator.userId").value(anna.id().toString()));   // il creatore non cambia
		update(boss, id, with("type", "OFFICIAL")).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("OFFICIAL"));
		cancel(boss, id).andExpect(status().isNoContent());
		assertThat(storedStatus(id)).isEqualTo("CANCELLED");
	}

	@Test
	void userCannotChangeTheTypeOfTheirEvent() throws Exception {
		TestUser anna = users().user("ev_type", "Anna");
		String id = createOk(anna, payload());

		update(anna, id, with("type", "OFFICIAL")).andExpect(status().isForbidden());
		update(anna, id, with("type", "COMMUNITY")).andExpect(status().isOk());
		update(anna, id, payload()).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("COMMUNITY"));
	}

	@Test
	void eventsWithoutCreatorAreManagedByAdminsOnly() throws Exception {
		TestUser anna = users().user("ev_nocreator_user", "Anna");
		TestUser boss = users().admin("ev_nocreator_admin");
		UUID demo = insertEvent("PUBLISHED", 48, null, null, true);

		update(anna, demo.toString(), payload()).andExpect(status().isForbidden());
		cancel(anna, demo.toString()).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/events/" + demo).header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false))
				.andExpect(jsonPath("$.type").value("OFFICIAL")).andExpect(jsonPath("$.creator").doesNotExist());
		update(boss, demo.toString(), payload()).andExpect(status().isOk());
		assertThat(jdbc.sql("SELECT demo FROM event WHERE id = :id").param("id", demo).query(Boolean.class).single()).isTrue();
	}

	@Test
	void adminRoleIsReadFromTheDatabaseNotFromTheToken() throws Exception {
		TestUser anna = users().user("ev_demoted_owner", "Anna");
		TestUser boss = users().admin("ev_demoted_admin");
		String id = createOk(anna, payload());

		users().setRole(boss.id(), "USER");                                                      // il token dice ancora ADMIN

		update(boss, id, with("title", "Still admin?")).andExpect(status().isForbidden());
		cancel(boss, id).andExpect(status().isForbidden());
		create(boss, with("type", "OFFICIAL")).andExpect(status().isForbidden());
	}

	@Test
	void updateAndCancelOfUnknownEventsReturn404() throws Exception {
		TestUser anna = users().user("ev_unknown", "Anna");
		String missing = UUID.randomUUID().toString();
		UUID draft = insertEvent("DRAFT", 48, anna.id(), null, false);

		update(anna, missing, payload()).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Event not found"));
		cancel(anna, missing).andExpect(status().isNotFound());
		update(anna, draft.toString(), payload()).andExpect(status().isNotFound());
		cancel(anna, draft.toString()).andExpect(status().isNotFound());
		mockMvc.perform(put("/api/events/not-a-uuid").header(HttpHeaders.AUTHORIZATION, anna.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json(payload()))).andExpect(status().isBadRequest());
	}

	@Test
	void cancelledAndEndedEventsCannotBeEdited() throws Exception {
		TestUser anna = users().user("ev_frozen", "Anna");
		UUID cancelled = insertEvent("CANCELLED", 48, anna.id(), null, false);
		UUID ended = insertEvent("PUBLISHED", -6, anna.id(), -2L, false);

		update(anna, cancelled.toString(), payload()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Event cannot be changed"));
		update(anna, ended.toString(), payload()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Event cannot be changed"));
		cancel(anna, ended.toString()).andExpect(status().isConflict());
		assertThat(storedStatus(ended.toString())).isEqualTo("PUBLISHED");
	}

	@Test
	void eventInProgressStaysEditableWhileItsStartIsUnchanged() throws Exception {
		TestUser anna = users().user("ev_running", "Anna");
		UUID running = insertEvent("PUBLISHED", -1, anna.id(), 2L, false);
		String start = JsonPath.read(mockMvc.perform(get("/api/events/" + running).header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andReturn().getResponse().getContentAsString(), "$.startsAt");
		String end = JsonPath.read(mockMvc.perform(get("/api/events/" + running).header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andReturn().getResponse().getContentAsString(), "$.endsAt");

		Map<String, Object> same = payload();
		same.put("startsAt", start);
		same.put("endsAt", end);
		same.put("title", "Titolo corretto");
		update(anna, running.toString(), same).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Titolo corretto"));

		// spostare l'inizio nel passato invece no
		Map<String, Object> moved = payload();
		moved.put("startsAt", Instant.now().minus(3, ChronoUnit.HOURS).toString());
		update(anna, running.toString(), moved).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.startsAt").exists());
	}

	@Test
	void updateValidatesLikeCreation() throws Exception {
		TestUser anna = users().user("ev_edit_invalid", "Anna");
		String id = createOk(anna, payload());

		for (Map<String, Object> p : List.of(with("title", "ab"), with("latitude", 10), with("imageUrl", "http://x.io/a.png"),
				without("category"), with("category", "NOPE"))) {
			update(anna, id, p).andExpect(status().isBadRequest());
		}
		assertThat(storedTitle(id)).isEqualTo("Aperitivo sui Navigli");
	}

	// ======================= annullamento =======================

	@Test
	void cancellationIsLogicalIdempotentAndHidesTheEventFromThePublicList() throws Exception {
		TestUser anna = users().user("ev_cancel", "Anna");
		String id = createOk(anna, payload());

		cancel(anna, id).andExpect(status().isNoContent());
		cancel(anna, id).andExpect(status().isNoContent());                       // idempotente

		assertThat(storedStatus(id)).isEqualTo("CANCELLED");
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE id = :id").param("id", UUID.fromString(id)).query(Long.class).single())
				.isEqualTo(1);                                                    // la riga resta
		mockMvc.perform(get("/api/events")).andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void cancelledEventStaysRecognizableForCreatorAdminAndParticipantsOnly() throws Exception {
		TestUser anna = users().user("ev_seen_owner", "Anna");
		TestUser going = users().user("ev_seen_going", "Going");
		TestUser stranger = users().user("ev_seen_stranger", "Stranger");
		TestUser boss = users().admin("ev_seen_admin");
		String id = createOk(anna, payload());
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', false)")
				.param("u", going.id()).param("e", UUID.fromString(id)).update();

		cancel(anna, id).andExpect(status().isNoContent());

		for (TestUser viewer : List.of(anna, going, boss)) {
			mockMvc.perform(get("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, viewer.bearer()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.status").value("CANCELLED"))
					.andExpect(jsonPath("$.canEdit").value(false))
					.andExpect(jsonPath("$.title").value("Aperitivo sui Navigli"));
		}
		mockMvc.perform(get("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isNotFound());
		cancel(stranger, id).andExpect(status().isNotFound());                    // non si rivela nemmeno cancellando
		update(stranger, id, payload()).andExpect(status().isNotFound());

		// il partecipante lo ritrova nelle proprie partecipazioni, segnalato come annullato
		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, going.bearer()))
				.andExpect(jsonPath("$.participations.length()").value(1))
				.andExpect(jsonPath("$.participations[0].event.status").value("CANCELLED"));
		// ... e il creatore tra i propri eventi
		mockMvc.perform(get("/api/users/me/events").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(jsonPath("$.events[0].status").value("CANCELLED"));
	}

	// ======================= canEdit e "i miei eventi" =======================

	@Test
	void canEditDependsOnWhoIsLooking() throws Exception {
		TestUser anna = users().user("ev_can_owner", "Anna");
		TestUser other = users().user("ev_can_other", "Other");
		TestUser boss = users().admin("ev_can_admin");
		String id = createOk(anna, payload());

		assertCanEdit(id, null, false);
		assertCanEdit(id, other, false);
		assertCanEdit(id, anna, true);
		assertCanEdit(id, boss, true);

		UUID ended = insertEvent("PUBLISHED", -6, anna.id(), -2L, false);
		assertCanEdit(ended.toString(), anna, false);                              // terminato: non si modifica piu'
	}

	@Test
	void anInvalidTokenOnThePublicDetailIsRejected() throws Exception {
		TestUser anna = users().user("ev_badtoken", "Anna");
		String id = createOk(anna, payload());

		mockMvc.perform(get("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isOk());      // senza token resta pubblico
	}

	@Test
	void myEventsListsOnlyMineIncludingCancelledNewestStartFirst() throws Exception {
		TestUser anna = users().user("ev_mine_a", "Anna");
		TestUser bea = users().user("ev_mine_b", "Bea");
		UUID soon = insertEvent("PUBLISHED", 24, anna.id(), null, false);
		UUID later = insertEvent("PUBLISHED", 72, anna.id(), null, false);
		UUID cancelled = insertEvent("CANCELLED", 48, anna.id(), null, false);
		insertEvent("DRAFT", 30, anna.id(), null, false);                          // le bozze non compaiono
		insertEvent("PUBLISHED", 24, bea.id(), null, false);                       // di un altro
		insertEvent("PUBLISHED", 24, null, null, true);                            // demo

		String body = mockMvc.perform(get("/api/users/me/events").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		List<String> ids = JsonPath.read(body, "$.events[*].id");
		assertThat(ids).containsExactly(later.toString(), cancelled.toString(), soon.toString());
		assertThat((List<String>) JsonPath.read(body, "$.events[*].status")).containsExactly("PUBLISHED", "CANCELLED", "PUBLISHED");
		mockMvc.perform(get("/api/users/me/events").header(HttpHeaders.AUTHORIZATION, users().user("ev_mine_empty").bearer()))
				.andExpect(jsonPath("$.events.length()").value(0));
	}

	@Test
	void publicEndpointsStayPublic() throws Exception {
		TestUser anna = users().user("ev_regress", "Anna");
		String id = createOk(anna, payload());
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isOk());
		mockMvc.perform(get("/api/interests")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + id + "/people")).andExpect(status().isUnauthorized());
	}

	// ======================= helper =======================

	private ResultActions create(TestUser user, Map<String, Object> payload) throws Exception {
		return mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json(payload)));
	}

	private String createOk(TestUser user, Map<String, Object> payload) throws Exception {
		return JsonPath.read(create(user, payload).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	private ResultActions update(TestUser user, String id, Map<String, Object> payload) throws Exception {
		return mockMvc.perform(put("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json(payload)));
	}

	private ResultActions cancel(TestUser user, String id) throws Exception {
		return mockMvc.perform(delete("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private void assertCanEdit(String id, TestUser viewer, boolean expected) throws Exception {
		var request = get("/api/events/" + id);
		if (viewer != null) {
			request = request.header(HttpHeaders.AUTHORIZATION, viewer.bearer());
		}
		mockMvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(expected));
	}

	/** Evento valido, nel futuro e dentro Milano. */
	private Map<String, Object> payload() {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("title", "Aperitivo sui Navigli");
		p.put("description", "Un aperitivo tra persone con interessi simili.");
		p.put("category", "SOCIAL");
		p.put("startsAt", OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.SECONDS).toString());
		p.put("venueName", "Darsena");
		p.put("address", "Piazza XXIV Maggio, Milano");
		p.put("neighbourhood", "Navigli");
		p.put("latitude", 45.4527);
		p.put("longitude", 9.1757);
		return p;
	}

	private Map<String, Object> with(String field, Object value) {
		Map<String, Object> p = payload();
		p.put(field, value);
		return p;
	}

	private Map<String, Object> without(String field) {
		Map<String, Object> p = payload();
		p.remove(field);
		return p;
	}

	/** JSON minimale: stringhe (con escape), numeri, booleani e null bastano per i payload dei test. */
	private String json(Map<String, Object> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Object> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(e.getKey()).append("\":");
			Object v = e.getValue();
			if (v == null || v instanceof Number || v instanceof Boolean) {
				sb.append(v);
			}
			else {
				sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
			}
		}
		return sb.append('}').toString();
	}

	private long communityEvents() {
		return jdbc.sql("SELECT count(*) FROM event WHERE event_type = 'COMMUNITY'").query(Long.class).single();
	}

	private long communityEventsBy(TestUser user) {
		return jdbc.sql("SELECT count(*) FROM event WHERE created_by = :id").param("id", user.id()).query(Long.class).single();
	}

	private String storedTitle(String id) {
		return jdbc.sql("SELECT title FROM event WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
	}

	private String storedStatus(String id) {
		return jdbc.sql("SELECT status FROM event WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
	}

	/** Inserisce un evento direttamente. creator null = senza creatore (ufficiale/demo). */
	private UUID insertEvent(String status, long startHours, UUID creator, Long endHours, boolean demo) {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo, event_type, created_by)
				VALUES ('EVT test event', 'Created by a test', 'MUSIC', :status, :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, :demo, :type, :creator) RETURNING id
				""")
				.param("status", status)
				.param("start", Timestamp.from(now.plus(startHours, ChronoUnit.HOURS)))
				.param("end", endHours == null ? null : Timestamp.from(now.plus(endHours, ChronoUnit.HOURS)), Types.TIMESTAMP)
				.param("demo", demo)
				.param("type", creator == null ? "OFFICIAL" : "COMMUNITY")
				.param("creator", creator, Types.OTHER)
				.query(UUID.class).single();
	}

}
