package com.linkr.backend.interest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;

/**
 * Catalogo e interessi utente con database di test (linkr_test_db).
 * Ogni test e' @Transactional: gli utenti e le selezioni create vengono annullati dal rollback finale.
 * Il catalogo viene solo letto (viene dalla migrazione V3).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class InterestIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	// ---------- catalogo ----------

	@Test
	void catalogIsPublicAndHasSixCategoriesAndFortyInterests() throws Exception {
		String body = mockMvc.perform(get("/api/interests"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(6))
				.andExpect(jsonPath("$[0].slug").value("music"))
				.andExpect(jsonPath("$[0].name").value("Musica"))
				.andExpect(jsonPath("$[0].interests[0].slug").value("techno"))
				.andReturn().getResponse().getContentAsString();

		List<Integer> counts = JsonPath.read(body, "$[*].interests.length()");
		assertThat(counts).containsExactly(8, 7, 6, 6, 6, 7);
		assertThat(counts.stream().mapToInt(Integer::intValue).sum()).isEqualTo(40);
	}

	@Test
	void catalogCategoriesAndInterestsAreOrdered() throws Exception {
		String body = mockMvc.perform(get("/api/interests")).andReturn().getResponse().getContentAsString();

		List<Integer> categoryOrders = JsonPath.read(body, "$[*].sortOrder");
		assertThat(categoryOrders).isSorted().doesNotHaveDuplicates();
		List<String> categorySlugs = JsonPath.read(body, "$[*].slug");
		assertThat(categorySlugs).containsExactly("music", "sport", "art-culture", "nightlife", "food", "lifestyle");

		for (int i = 0; i < categorySlugs.size(); i++) {
			List<Integer> orders = JsonPath.read(body, "$[" + i + "].interests[*].sortOrder");
			assertThat(orders).isSorted().doesNotHaveDuplicates().first().isEqualTo(1);
		}
	}

	@Test
	void catalogSlugsAreUniqueAndIdsAreStable() throws Exception {
		String body = mockMvc.perform(get("/api/interests")).andReturn().getResponse().getContentAsString();

		List<String> slugs = JsonPath.read(body, "$[*].interests[*].slug");
		assertThat(slugs).hasSize(40).doesNotHaveDuplicates();
		// UUID fissi scritti nella migrazione, uguali in ogni ambiente
		List<String> ids = JsonPath.read(body, "$[*].interests[*].id");
		assertThat(ids.get(0)).isEqualTo("018f2000-0000-7000-8000-000000000001");
		assertThat(ids.get(39)).isEqualTo("018f2000-0000-7000-8000-000000000040");
	}

	// ---------- GET /me/interests ----------

	@Test
	void newUserHasNoInterests() throws Exception {
		String token = registerAndGetToken("empty");

		mockMvc.perform(get("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests.length()").value(0));
	}

	// ---------- PUT valido ----------

	@Test
	void putStoresSelectionAndGetReturnsItInCatalogOrder() throws Exception {
		String token = registerAndGetToken("mario");
		List<String> ids = catalogIds();
		String techno = ids.get(0);
		String jazz = ids.get(4);
		String running = ids.get(10);

		// inviati "in disordine": la risposta segue l'ordine del catalogo
		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(running, techno, jazz)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests.length()").value(3))
				.andExpect(jsonPath("$.interests[0].slug").value("techno"))
				.andExpect(jsonPath("$.interests[0].categorySlug").value("music"))
				.andExpect(jsonPath("$.interests[0].categoryId").isNotEmpty())
				.andExpect(jsonPath("$.interests[0].name").value("Techno"))
				.andExpect(jsonPath("$.interests[1].slug").value("jazz"))
				.andExpect(jsonPath("$.interests[2].slug").value("running"));

		mockMvc.perform(get("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests[*].slug", contains("techno", "jazz", "running")));
	}

	@Test
	void putReplacesThePreviousSelection() throws Exception {
		String token = registerAndGetToken("replace");
		List<String> ids = catalogIds();
		putOk(token, ids.get(0), ids.get(1));

		putOk(token, ids.get(1), ids.get(2));

		mockMvc.perform(get("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.interests[*].slug", contains("house", "uk-garage")));
		assertThat(rowCountFor("replace")).isEqualTo(2);
	}

	@Test
	void putEmptyArrayRemovesAllInterests() throws Exception {
		String token = registerAndGetToken("clear");
		putOk(token, catalogIds().get(0));

		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests.length()").value(0));
		assertThat(rowCountFor("clear")).isZero();
	}

	@Test
	void repeatedPutIsIdempotent() throws Exception {
		String token = registerAndGetToken("idem");
		List<String> ids = catalogIds();

		String first = putOk(token, ids.get(3), ids.get(7));
		String second = putOk(token, ids.get(3), ids.get(7));

		assertThat(second).isEqualTo(first);
		assertThat(rowCountFor("idem")).isEqualTo(2);
	}

	@Test
	void fifteenInterestsAreAllowed() throws Exception {
		String token = registerAndGetToken("max");

		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(catalogIds().subList(0, 15).toArray(String[]::new))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests.length()").value(15));
	}

	// ---------- PUT non valido ----------

	@Test
	void putWithoutTokenReturns401() throws Exception {
		mockMvc.perform(put("/api/users/me/interests").contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/users/me/interests")).andExpect(status().isUnauthorized());
	}

	@Test
	void putWithInvalidOrExpiredTokenReturns401() throws Exception {
		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
						.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isUnauthorized());

		String userId = userIdOf(registerAndGetBody("expired"));
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(userId).claim("roles", List.of("USER"))
						.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build()))
				.getTokenValue();
		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(expired))
						.contentType(MediaType.APPLICATION_JSON).content(body()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void moreThanFifteenInterestsReturn400AndChangeNothing() throws Exception {
		String token = registerAndGetToken("toomany");
		List<String> ids = catalogIds();
		putOk(token, ids.get(0));

		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(ids.subList(0, 16).toArray(String[]::new))))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors.interestIds").exists());

		assertSelection(token, "techno");
	}

	@Test
	void duplicatesReturn400AndChangeNothing() throws Exception {
		String token = registerAndGetToken("dups");
		List<String> ids = catalogIds();
		putOk(token, ids.get(1));

		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(ids.get(0), ids.get(0))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid interests"));

		assertSelection(token, "house");
	}

	@Test
	void unknownIdsReturn400AndChangeNothing() throws Exception {
		String token = registerAndGetToken("unknown");
		List<String> ids = catalogIds();
		putOk(token, ids.get(2));
		String unknown = UUID.randomUUID().toString();

		// una richiesta in parte valida non applica NIENTE (atomicita')
		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(ids.get(0), unknown)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.unknownIds[0]").value(unknown));

		assertSelection(token, "uk-garage");
	}

	@Test
	void aCategoryIdIsNotAcceptedAsAnInterest() throws Exception {
		String token = registerAndGetToken("category");
		String categoryId = JsonPath.read(
				mockMvc.perform(get("/api/interests")).andReturn().getResponse().getContentAsString(), "$[0].id");

		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(categoryId)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.unknownIds[0]").value(categoryId));
		assertThat(rowCountFor("category")).isZero();
	}

	@Test
	void nullOrMissingArrayAndMalformedBodyReturn400() throws Exception {
		String token = registerAndGetToken("nulls");

		for (String payload : List.of("{\"interestIds\":null}", "{}", "{not json", "{\"interestIds\":[\"not-a-uuid\"]}",
				"{\"interestIds\":[null]}", "{\"interestIds\":\"abc\"}")) {
			mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
							.contentType(MediaType.APPLICATION_JSON).content(payload))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	// ---------- isolamento tra utenti ----------

	@Test
	void usersCannotSeeOrChangeEachOthersInterests() throws Exception {
		String alice = registerAndGetToken("alice");
		String bob = registerAndGetToken("bob");
		List<String> ids = catalogIds();
		putOk(alice, ids.get(0), ids.get(1));
		putOk(bob, ids.get(5));

		// un userId nel payload viene ignorato: la modifica riguarda sempre l'utente del token
		String aliceId = userIdOf(registerBodyFor("alice"));
		mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(bob))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + aliceId + "\",\"interestIds\":[\"" + ids.get(6) + "\"]}"))
				.andExpect(status().isOk());

		assertSelection(alice, "techno", "house");
		assertSelection(bob, "dj-set");
		assertThat(rowCountFor("alice")).isEqualTo(2);
		assertThat(rowCountFor("bob")).isEqualTo(1);
	}

	@Test
	void deletingAnInterestOrUserCascadesToUserInterest() throws Exception {
		String token = registerAndGetToken("cascade");
		putOk(token, catalogIds().get(0), catalogIds().get(1));
		assertThat(rowCountFor("cascade")).isEqualTo(2);

		// ON DELETE CASCADE verificato direttamente sul database di test (annullato dal rollback)
		jdbc.sql("DELETE FROM app_user WHERE username = 'cascade'").update();
		assertThat(jdbc.sql("SELECT count(*) FROM user_interest ui WHERE NOT EXISTS "
				+ "(SELECT 1 FROM app_user u WHERE u.id = ui.user_id)").query(Long.class).single()).isZero();
	}

	// ---------- regressione ----------

	@Test
	void authAndEventEndpointsStillWork() throws Exception {
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		String token = registerAndGetToken("regress");
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("regress"))
				.andExpect(jsonPath("$.interests").doesNotExist()); // UserResponse invariato
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"regress@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isOk());
	}

	// ---------- helper ----------

	private List<String> catalogIds() throws Exception {
		String body = mockMvc.perform(get("/api/interests")).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$[*].interests[*].id");
	}

	private String body(String... ids) {
		List<String> quoted = new ArrayList<>();
		for (String id : ids) {
			quoted.add("\"" + id + "\"");
		}
		return "{\"interestIds\":[" + String.join(",", quoted) + "]}";
	}

	/** PUT che deve riuscire; restituisce il corpo della risposta. */
	private String putOk(String token, String... ids) throws Exception {
		return mockMvc.perform(put("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(ids)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private void assertSelection(String token, String... slugs) throws Exception {
		mockMvc.perform(get("/api/users/me/interests").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.interests[*].slug", contains(slugs)));
	}

	private long rowCountFor(String username) {
		return jdbc.sql("SELECT count(*) FROM user_interest ui JOIN app_user u ON u.id = ui.user_id WHERE u.username = :u")
				.param("u", username).query(Long.class).single();
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}

	private String registerAndGetToken(String username) throws Exception {
		return JsonPath.read(registerAndGetBody(username), "$.accessToken");
	}

	private String registerAndGetBody(String username) throws Exception {
		return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\",\"email\":\"" + username
								+ "@example.com\",\"password\":\"password123\",\"displayName\":\"Test\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private String registerBodyFor(String username) {
		return jdbc.sql("SELECT json_build_object('user', json_build_object('id', id))::text FROM app_user WHERE username = :u")
				.param("u", username).query(String.class).single();
	}

	private String userIdOf(String authResponseBody) {
		return JsonPath.read(authResponseBody, "$.user.id");
	}

}
