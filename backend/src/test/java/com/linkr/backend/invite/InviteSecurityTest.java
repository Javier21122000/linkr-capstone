package com.linkr.backend.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/**
 * Controlli di sicurezza mirati sugli inviti (con InviteIntegrationTest): il token d'invito non e' un JWT, non finisce nei log, non permette
 * di enumerare profili e le risposte non contengono dati privati. Database di test (linkr_test_db), rollback a fine test.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class InviteSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private jakarta.persistence.EntityManager entityManager;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, null);
		}
		return users;
	}

	@Test
	void anInviteTokenIsNeverAcceptedAsABearerCredentialAnywhere(CapturedOutput output) throws Exception {
		UUID event = createEvent();
		TestUser alice = visible("alice");
		String token = tokenOf(alice, event);

		for (String path : new String[] {"/api/users/me", "/api/people", "/api/link-requests", "/api/conversations", "/api/users/me/participations",
				"/api/invites/" + token}) {
			mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
		}
		mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/events/" + event + "/invites").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());
		assertThat(output.getAll()).doesNotContain(token);
	}

	@Test
	void everyInviteEndpointNeedsAJwt() throws Exception {
		UUID event = createEvent();
		String token = tokenOf(visible("alice"), event);
		mockMvc.perform(post("/api/events/" + event + "/invites")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/invites/" + token)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/invites/" + token + "/link-request")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/invites/" + token).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
	}

	@Test
	void theCompleteTokenNeverAppearsInTheLogs(CapturedOutput output) throws Exception {
		UUID event = createEvent();
		TestUser alice = visible("alice");
		TestUser bob = users().user("bob", "Bob");
		String token = tokenOf(alice, event);
		String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");

		mockMvc.perform(get("/api/invites/" + token).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isOk());
		mockMvc.perform(get("/api/invites/" + tampered).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isNotFound());
		mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isCreated());
		mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isConflict());
		mockMvc.perform(get("/api/invites/" + "x".repeat(400)).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/invites/" + token)).andExpect(status().isUnauthorized());

		String logs = output.getAll();
		assertThat(logs).doesNotContain(token).doesNotContain(tampered).doesNotContain(token.split("\\.")[0]).doesNotContain(token.split("\\.")[1]);
		assertThat(logs).as("nemmeno la firma da sola").doesNotContain(token.split("\\.")[1].substring(0, 20));
	}

	@Test
	void profilesCannotBeEnumeratedThroughInvites() throws Exception {
		UUID event = createEvent();
		TestUser alice = visible("alice");
		TestUser hidden = users().user("hidden", "Hidden");
		TestUser bob = users().user("bob", "Bob");
		TestUser deleted = visible("deleted");
		String forDeleted = tokenOf(deleted, event);
		String forHidden = tokenOf(hidden, event);
		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", deleted.id()).update();
		entityManager.clear();   // il test vive in una sola transazione: senza, JPA restituirebbe ancora l'utente dalla cache di primo livello

		String reference = mockMvc.perform(get("/api/invites/garbage").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
		// sender inesistente, sender non visibile, evento inesistente: indistinguibili da un token inventato
		for (String token : new String[] {forDeleted, forHidden,
				new InviteTokenService(jwtKey(), java.time.Clock.systemUTC()).issue(UUID.randomUUID(), alice.id()).token()}) {
			String body = mockMvc.perform(get("/api/invites/" + token).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isNotFound())
					.andReturn().getResponse().getContentAsString();
			assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo(JsonPath.<String>read(reference, "$.title"));
			assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo(JsonPath.<String>read(reference, "$.detail"));
			mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isNotFound());
		}
		assertThat(jdbc.sql("SELECT count(*) FROM link_request").query(Long.class).single()).isZero();
	}

	@Test
	void noPrivateDataIsEverReturned() throws Exception {
		UUID event = createEvent();
		TestUser alice = visible("alice", "Alice QA");
		TestUser bob = users().user("bob", "Bob");

		String issued = mockMvc.perform(post("/api/events/" + event + "/invites").header(HttpHeaders.AUTHORIZATION, alice.bearer())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(com.jayway.jsonpath.JsonPath.<java.util.Map<String, Object>>read(issued, "$").keySet()).containsExactlyInAnyOrder("token", "expiresAt");
		String token = JsonPath.read(issued, "$.token");

		String resolved = mockMvc.perform(get("/api/invites/" + token).header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String link = mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		for (String body : new String[] {issued, resolved, link}) {
			assertThat(body).doesNotContain("@example.com").doesNotContain("email").doesNotContain("assword").doesNotContain("Hash")
					.doesNotContain("bio").doesNotContain("role").doesNotContain("discoverable").doesNotContain("interest");
		}
		// chi guarda l'invito vede solo nome e avatar: ne' id ne' username del mittente
		assertThat(resolved).contains("Alice QA").doesNotContain(alice.id().toString()).doesNotContain("alice");
	}

	@Test
	void aDeclinedRequestCanNeverBeRepeatedThroughTheInvite() throws Exception {
		UUID event = createEvent();
		TestUser alice = visible("alice");
		TestUser bob = users().user("bob", "Bob");
		String token = tokenOf(alice, event);

		mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isCreated());
		UUID request = jdbc.sql("SELECT id FROM link_request").query(UUID.class).single();
		mockMvc.perform(post("/api/link-requests/" + request + "/decline").header(HttpHeaders.AUTHORIZATION, alice.bearer())).andExpect(status().isOk());

		// nuovi token, altri giorni, altri tentativi: sempre la stessa richiesta, mai una seconda
		for (int attempt = 0; attempt < 3; attempt++) {
			String again = tokenOf(alice, event);
			mockMvc.perform(post("/api/invites/" + again + "/link-request").header(HttpHeaders.AUTHORIZATION, bob.bearer())).andExpect(status().isConflict());
		}
		assertThat(jdbc.sql("SELECT count(*) FROM link_request").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM link_request").query(String.class).single()).isEqualTo("DECLINED");
	}

	// ======================= aiuti =======================

	@Autowired
	private javax.crypto.SecretKey jwtSecretKey;

	private javax.crypto.SecretKey jwtKey() {
		return jwtSecretKey;
	}

	private TestUser visible(String username) throws Exception {
		return visible(username, username);
	}

	private TestUser visible(String username, String displayName) throws Exception {
		TestUser user = users().user(username, displayName);
		mockMvc.perform(put("/api/users/me/discovery").header(HttpHeaders.AUTHORIZATION, user.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"discoverable\":true}")).andExpect(status().isOk());
		return user;
	}

	private String tokenOf(TestUser user, UUID event) throws Exception {
		return JsonPath.read(mockMvc.perform(post("/api/events/" + event + "/invites").header(HttpHeaders.AUTHORIZATION, user.bearer()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
	}

	private UUID createEvent() {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('INVITE security event', 'Created by a test', 'MUSIC', 'PUBLISHED', :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("start", Timestamp.from(now.plus(24, ChronoUnit.HOURS))).param("end", Timestamp.from(now.plus(26, ChronoUnit.HOURS)))
				.query(UUID.class).single();
	}

}
