package com.linkr.backend.people;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;

/**
 * Discovery persone per evento, con database di test (linkr_test_db).
 * Ogni test e' @Transactional: utenti, eventi, interessi e partecipazioni vengono annullati dal rollback finale.
 * Gli interessi sono quelli del catalogo V3 (slug: techno, house, jazz, running, cinema, ...).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class PeopleIntegrationTest {

	private record TestUser(String username, UUID id, String token) {
	}

	/** PostgreSQL ordina gli uuid come byte senza segno = ordine alfabetico della forma testuale (UUID.compareTo di Java no). */
	private static final java.util.Comparator<TestUser> BY_ID_LIKE_POSTGRES =
			java.util.Comparator.comparing(u -> u.id().toString());

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	// ---------- accesso ----------

	@Test
	void requiresAValidJwt() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		mockMvc.perform(get(url(event))).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized());

		TestUser me = user("expired", "Expired");
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(me.id().toString()).claim("roles", List.of("USER"))
						.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build()))
				.getTokenValue();
		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownDraftAndCancelledEventsReturn404() throws Exception {
		TestUser me = user("me404", "Me");
		for (UUID event : List.of(UUID.randomUUID(), createEvent("DRAFT", 24, 26L), createEvent("CANCELLED", 24, 26L))) {
			mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.title").value("Event not found"));
		}
	}

	@Test
	void endedEventsReturn409WithAndWithoutEndsAt() throws Exception {
		TestUser me = user("me409", "Me");
		UUID endedWithEnd = createEvent("PUBLISHED", -5, -1L);
		UUID endedWithoutEnd = createEvent("PUBLISHED", -4, null); // 4 ore fa + 3 ore presunte: finito
		participate(me, endedWithEnd, "GOING", false);
		participate(me, endedWithoutEnd, "GOING", false);

		for (UUID event : List.of(endedWithEnd, endedWithoutEnd)) {
			mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.title").value("Event has ended"));
		}
	}

	@Test
	void eventWithoutEndsAtIsOpenForThreeHoursAfterStart() throws Exception {
		TestUser me = user("me3h", "Me");
		UUID event = createEvent("PUBLISHED", -1, null);
		participate(me, event, "GOING", false);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me))).andExpect(status().isOk());
	}

	@Test
	void userWithoutOwnParticipationGets403() throws Exception {
		TestUser me = user("me403", "Me");
		TestUser other = user("other403", "Other");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(other, event, "GOING", true);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Participation required"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("other403"))));
	}

	@Test
	void privateParticipantCanSeeOthersButIsNotListed() throws Exception {
		TestUser me = user("private", "Me");
		TestUser anna = user("anna", "Anna");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "INTERESTED", false); // io: nascosto, ma posso guardare
		participate(anna, event, "GOING", true);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].username", contains("anna")));
		// ...e Anna non vede me
		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(anna)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	// ---------- privacy: chi compare ----------

	@Test
	void onlyVisibleOthersOfTheSameEventAreListedAndNeverMyself() throws Exception {
		TestUser me = user("viewer", "Viewer");
		TestUser visible = user("visible", "Visible");
		TestUser hidden = user("hidden", "Hidden");
		TestUser elsewhere = user("elsewhere", "Elsewhere");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		UUID otherEvent = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "GOING", true);       // visibile, ma e' me: escluso
		participate(visible, event, "INTERESTED", true);
		participate(hidden, event, "GOING", false);
		participate(elsewhere, otherEvent, "GOING", true);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].username", contains("visible")))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].status").value("INTERESTED"));
	}

	@Test
	void personDisappearsWhenTheyHideThemselvesAgain() throws Exception {
		TestUser me = user("watcher", "Watcher");
		TestUser anna = user("annah", "Anna");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "GOING", false);
		participate(anna, event, "GOING", true);
		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(jsonPath("$.totalElements").value(1));

		// Anna cambia visibilita' con l'API reale
		mockMvc.perform(put("/api/events/" + event + "/participation").header(HttpHeaders.AUTHORIZATION, bearer(anna))
						.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"GOING\",\"visibleToOthers\":false}"))
				.andExpect(status().isOk());

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(jsonPath("$.totalElements").value(0))
				.andExpect(jsonPath("$.items.length()").value(0));
	}

	@Test
	void responseContainsOnlyPublicFields() throws Exception {
		TestUser me = user("fields", "Me");
		TestUser anna = user("annaf", "Anna");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		interests(me, "techno");
		interests(anna, "techno", "jazz", "running");
		jdbc.sql("UPDATE app_user SET bio = 'secret bio', avatar_url = 'https://example.com/a.png' WHERE id = :id")
				.param("id", anna.id()).update();
		participate(me, event, "GOING", false);
		participate(anna, event, "GOING", true);

		String body = mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		Map<String, Object> item = JsonPath.read(body, "$.items[0]");
		assertThat(item.keySet()).containsExactlyInAnyOrder("userId", "username", "displayName", "avatarUrl", "status",
				"sharedInterestsCount", "sharedInterests");
		assertThat(item.get("avatarUrl")).isEqualTo("https://example.com/a.png");
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("secret bio")
				.doesNotContain("bio").doesNotContain("role").doesNotContain("password").doesNotContain("Hash")
				.doesNotContain("jazz").doesNotContain("running"); // interessi NON condivisi
		Map<String, Object> shared = JsonPath.read(body, "$.items[0].sharedInterests[0]");
		assertThat(shared.keySet()).containsExactlyInAnyOrder("id", "slug", "name", "categorySlug");
	}

	// ---------- interessi in comune ----------

	@Test
	void sharedInterestsAreTheRealIntersectionInCatalogOrder() throws Exception {
		TestUser me = user("meint", "Me");
		TestUser anna = user("annai", "Anna");
		TestUser bruno = user("brunoi", "Bruno");
		TestUser carla = user("carlai", "Carla");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		interests(me, "jazz", "techno", "house", "cinema");
		interests(anna, "running", "techno", "jazz", "brunch");   // in comune: techno, jazz
		interests(bruno, "running", "brunch");                    // nessuno in comune
		// carla: nessun interesse
		participate(me, event, "GOING", false);
		participate(anna, event, "GOING", true);
		participate(bruno, event, "GOING", true);
		participate(carla, event, "GOING", true);

		ResultActions result = mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].username").value("annai"))
				.andExpect(jsonPath("$.items[0].sharedInterestsCount").value(2))
				// ordine del catalogo (techno prima di jazz), non quello di inserimento
				.andExpect(jsonPath("$.items[0].sharedInterests[*].slug", contains("techno", "jazz")))
				.andExpect(jsonPath("$.items[0].sharedInterests[0].categorySlug").value("music"))
				.andExpect(jsonPath("$.items[0].sharedInterests[0].name").value("Techno"))
				.andExpect(jsonPath("$.items[1].sharedInterestsCount").value(0))
				.andExpect(jsonPath("$.items[1].sharedInterests.length()").value(0))
				.andExpect(jsonPath("$.items[2].sharedInterestsCount").value(0));
		assertThat(result).isNotNull();
	}

	@Test
	void everyoneHasZeroSharedWhenIHaveNoInterests() throws Exception {
		TestUser me = user("nointerests", "Me");
		TestUser anna = user("annan", "Anna");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		interests(anna, "techno", "jazz");
		participate(me, event, "GOING", false);
		participate(anna, event, "GOING", true);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(jsonPath("$.items[0].sharedInterestsCount").value(0))
				.andExpect(jsonPath("$.items[0].sharedInterests.length()").value(0));
	}

	// ---------- ordinamento ----------

	@Test
	void sortSharedOrdersBySharedThenGoingThenNameThenId() throws Exception {
		TestUser me = user("sorter", "Me");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		interests(me, "techno", "house", "jazz");
		TestUser interested2 = user("s_int2", "Marta");
		TestUser going2 = user("s_go2", "Zoe");
		TestUser going1 = user("s_go1", "Luca");
		TestUser zero1 = user("s_zero1", "Zed");
		TestUser zero2 = user("s_zero2", "abe");     // minuscola: l'ordine per nome ignora le maiuscole
		TestUser twinA = user("s_twinA", "Twin");
		TestUser twinB = user("s_twinB", "Twin");
		interests(interested2, "techno", "house");
		interests(going2, "techno", "jazz");
		interests(going1, "house");
		interests(twinA, "jazz");
		interests(twinB, "jazz");
		participate(me, event, "GOING", false);
		participate(interested2, event, "INTERESTED", true);
		participate(going2, event, "GOING", true);
		participate(going1, event, "INTERESTED", true);
		participate(zero1, event, "GOING", true);
		participate(zero2, event, "GOING", true);
		participate(twinA, event, "GOING", true);
		participate(twinB, event, "GOING", true);

		String body = mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		List<String> usernames = JsonPath.read(body, "$.items[*].username");
		List<String> twins = List.of(twinA, twinB).stream().sorted(BY_ID_LIKE_POSTGRES)
				.map(TestUser::username).toList();
		// 2 in comune: GOING (Zoe) prima di INTERESTED (Marta); 1 in comune: GOING (Twin x2, per id) poi INTERESTED (Luca);
		// 0 in comune: abe prima di Zed
		List<String> expected = new ArrayList<>(List.of("s_go2", "s_int2"));
		expected.addAll(twins);
		expected.addAll(List.of("s_go1", "s_zero2", "s_zero1"));
		assertThat(usernames).containsExactlyElementsOf(expected);
		// stesso ordine con sort esplicito
		mockMvc.perform(get(url(event) + "?sort=shared").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(jsonPath("$.items[*].username", contains(expected.toArray())));
	}

	@Test
	void sortRecentOrdersByParticipationUpdateTimeThenId() throws Exception {
		TestUser me = user("recenter", "Me");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser old = user("r_old", "Old");
		TestUser newest = user("r_new", "New");
		TestUser middle = user("r_mid", "Mid");
		TestUser tieA = user("r_tieA", "Tie");
		TestUser tieB = user("r_tieB", "Tie");
		participate(me, event, "GOING", false);
		participate(old, event, "GOING", true);
		participate(newest, event, "INTERESTED", true);
		participate(middle, event, "GOING", true);
		participate(tieA, event, "GOING", true);
		participate(tieB, event, "GOING", true);
		Instant now = Instant.now();
		touch(newest, event, now.plus(1, ChronoUnit.HOURS));
		touch(tieA, event, now.minus(30, ChronoUnit.MINUTES));
		touch(tieB, event, now.minus(30, ChronoUnit.MINUTES));
		touch(middle, event, now.minus(1, ChronoUnit.HOURS));
		touch(old, event, now.minus(5, ChronoUnit.HOURS));

		List<String> ties = List.of(tieA, tieB).stream().sorted(BY_ID_LIKE_POSTGRES).map(TestUser::username).toList();
		List<String> expected = new ArrayList<>(List.of("r_new"));
		expected.addAll(ties);
		expected.addAll(List.of("r_mid", "r_old"));
		mockMvc.perform(get(url(event) + "?sort=recent").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].username", contains(expected.toArray())));
	}

	// ---------- paginazione ----------

	@Test
	void paginationReturnsStableDisjointPagesWithTotals() throws Exception {
		TestUser me = user("pager", "Me");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "GOING", false);
		List<String> all = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			participate(user("pg" + i, "Person" + i), event, "GOING", true);
		}

		for (int page = 0; page < 3; page++) {
			String body = mockMvc.perform(get(url(event) + "?size=2&page=" + page).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.page").value(page))
					.andExpect(jsonPath("$.size").value(2))
					.andExpect(jsonPath("$.totalElements").value(5))
					.andExpect(jsonPath("$.totalPages").value(3))
					.andReturn().getResponse().getContentAsString();
			all.addAll(JsonPath.<List<String>>read(body, "$.items[*].username"));
		}
		assertThat(all).containsExactly("pg1", "pg2", "pg3", "pg4", "pg5"); // niente doppioni ne' buchi, ordine stabile

		mockMvc.perform(get(url(event) + "?size=2&page=3").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0))
				.andExpect(jsonPath("$.totalElements").value(5));
	}

	@Test
	void defaultsAndMaximumSizeAreApplied() throws Exception {
		TestUser me = user("defaults", "Me");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "GOING", false);

		mockMvc.perform(get(url(event)).header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(0)).andExpect(jsonPath("$.totalPages").value(0));
		mockMvc.perform(get(url(event) + "?size=50").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
	}

	@Test
	void invalidQueryParametersReturn400() throws Exception {
		TestUser me = user("badparams", "Me");
		UUID event = createEvent("PUBLISHED", 24, 26L);
		participate(me, event, "GOING", false);

		for (String query : List.of("size=0", "size=51", "size=-1", "size=abc", "page=-1", "page=abc",
				"sort=foo", "sort=SHARED", "sort=shared;drop table app_user", "sort=u.email")) {
			mockMvc.perform(get(url(event) + "?" + query).header(HttpHeaders.AUTHORIZATION, bearer(me)))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		mockMvc.perform(get("/api/events/not-a-uuid/people").header(HttpHeaders.AUTHORIZATION, bearer(me)))
				.andExpect(status().isBadRequest());
		assertThat(jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single()).isGreaterThan(0);
	}

	// ---------- regressione ----------

	@Test
	void publicEndpointsStayPublicAndPeopleIsNeverPublic() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + event)).andExpect(status().isOk());
		mockMvc.perform(get("/api/interests")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + event + "/people")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}")).andExpect(status().isUnauthorized());
	}

	// ---------- helper ----------

	private String url(UUID event) {
		return "/api/events/" + event + "/people";
	}

	private String bearer(TestUser user) {
		return "Bearer " + user.token();
	}

	private TestUser user(String username, String displayName) {
		try {
			String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"" + username + "\",\"email\":\"" + username
									+ "@example.com\",\"password\":\"password123\",\"displayName\":\"" + displayName + "\"}"))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			return new TestUser(username, UUID.fromString(JsonPath.read(body, "$.user.id")), JsonPath.read(body, "$.accessToken"));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void interests(TestUser user, String... slugs) {
		jdbc.sql("INSERT INTO user_interest (user_id, interest_id) SELECT :u, id FROM interest WHERE slug IN (:slugs)")
				.param("u", user.id()).param("slugs", List.of(slugs)).update();
	}

	private void participate(TestUser user, UUID event, String status, boolean visible) {
		jdbc.sql("""
				INSERT INTO event_participation (user_id, event_id, status, visible_to_others)
				VALUES (:u, :e, :s, :v)
				""").param("u", user.id()).param("e", event).param("s", status).param("v", visible).update();
	}

	private void touch(TestUser user, UUID event, Instant updatedAt) {
		jdbc.sql("UPDATE event_participation SET updated_at = :t WHERE user_id = :u AND event_id = :e")
				.param("t", Timestamp.from(updatedAt)).param("u", user.id()).param("e", event).update();
	}

	/** Evento di prova (annullato dal rollback): inizio e fine in ore da adesso (negativo = passato), fine opzionale. */
	private UUID createEvent(String status, long startHours, Long endHours) {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('PEOPLE test event', 'Created by a test', 'MUSIC', :status, :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("status", status)
				.param("start", Timestamp.from(now.plus(startHours, ChronoUnit.HOURS)))
				.param("end", endHours == null ? null : Timestamp.from(now.plus(endHours, ChronoUnit.HOURS)), Types.TIMESTAMP)
				.query(UUID.class).single();
	}

}
