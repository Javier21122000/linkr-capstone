package com.linkr.backend.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

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
import com.linkr.backend.linkrequest.LinkRequestService;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/**
 * Inviti agli eventi con database di test (linkr_test_db). Ogni test e' @Transactional: tutto viene annullato dal rollback.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class InviteIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SecretKey jwtSecretKey;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, null);
		}
		return users;
	}

	// ======================= emissione =======================

	@Test
	void issuingRequiresAJwt() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		mockMvc.perform(post("/api/events/" + event + "/invites")).andExpect(status().isUnauthorized());
		assertThat(links()).isZero();
	}

	@Test
	void theTokenIsIssuedToTheCallerOnlyAndCannotNameAnotherSender() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = users().user("bob", "Bob");
		TestUser mallory = visible("mallory", "Mallory");

		// il corpo, i parametri e le intestazioni che "indicano" un mittente non contano: il mittente e' il soggetto del JWT
		String token = JsonPath.read(mockMvc.perform(post("/api/events/" + event + "/invites?sender=" + mallory.id() + "&senderId=" + mallory.id())
						.header(HttpHeaders.AUTHORIZATION, alice.bearer()).header("X-Sender", mallory.id().toString())
						.contentType(MediaType.APPLICATION_JSON).content("{\"senderId\":\"" + mallory.id() + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").exists()).andReturn().getResponse().getContentAsString(), "$.token");

		resolve(bob, token).andExpect(status().isOk()).andExpect(jsonPath("$.sender.displayName").value("Alice"));
		assertThat(links()).isZero();
	}

	@Test
	void issuingFailsForUnknownCancelledAndEndedEvents() throws Exception {
		TestUser alice = visible("alice", "Alice");
		issue(alice, UUID.randomUUID()).andExpect(status().isNotFound());
		issue(alice, createEvent("CANCELLED", 24, 26L)).andExpect(status().isNotFound());
		issue(alice, createEvent("PUBLISHED", -30, -28L)).andExpect(status().isConflict());
	}

	@Test
	void theTokenExpiresInThirtyDays() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		String body = issue(alice, event).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
		assertThat(Math.abs(ChronoUnit.SECONDS.between(Instant.now().plus(30, ChronoUnit.DAYS), expiresAt))).isLessThan(30);
	}

	// ======================= identita' del mittente =======================

	@Test
	void anAuthenticatedViewerSeesOnlyTheDisplayNameAndAvatarOfAVisibleSender() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = users().user("bob", "Bob");   // appena iscritto: NON visibile, nessun interesse, nessuna partecipazione

		String body = resolve(bob, tokenOf(alice, event)).andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(event.toString()))
				.andExpect(jsonPath("$.sender.displayName").value("Alice"))
				.andExpect(jsonPath("$.linkStatus").value("NONE"))
				.andExpect(jsonPath("$.linkRequestId").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> root = JsonPath.read(body, "$");
		assertThat(root.keySet()).containsExactlyInAnyOrder("eventId", "sender", "linkStatus", "linkRequestId");
		Map<String, Object> sender = JsonPath.read(body, "$.sender");
		assertThat(sender.keySet()).containsExactlyInAnyOrder("displayName", "avatarUrl");
		assertThat(body).doesNotContain(alice.id().toString()).doesNotContain("alice@").doesNotContain("email").doesNotContain("username")
				.doesNotContain("bio").doesNotContain("role").doesNotContain("assword");
		assertThat(links()).as("guardare un invito non crea nessuna richiesta").isZero();
	}

	@Test
	void anonymousVisitorsGetNothingAboutTheSender() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		String token = tokenOf(visible("alice", "Alice"), event);
		mockMvc.perform(get("/api/invites/" + token)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/invites/" + token + "/link-request")).andExpect(status().isUnauthorized());
	}

	@Test
	void aSenderWhoIsNotVisibleIsNeverRevealed() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = users().user("alice", "Alice");   // non visibile
		TestUser bob = users().user("bob", "Bob");
		String token = tokenOf(alice, event);

		String hidden = resolve(bob, token).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertThat(hidden).doesNotContain("Alice");
		sendLink(bob, token).andExpect(status().isNotFound());
		assertThat(links()).isZero();

		// e se la visibilita' viene tolta DOPO aver condiviso, l'invito smette di rivelare l'identita'
		discovery(alice, true);
		resolve(bob, token).andExpect(status().isOk());
		discovery(alice, false);
		resolve(bob, token).andExpect(status().isNotFound());
	}

	@Test
	void everyUnusableInviteGivesTheSameNotFoundAnswer() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = users().user("bob", "Bob");
		String valid = tokenOf(alice, event);

		// scaduto: emesso 31 giorni fa con la stessa chiave
		InviteTokenService past = new InviteTokenService(jwtSecretKey, Clock.fixed(Instant.now().minus(31, ChronoUnit.DAYS), ZoneOffset.UTC));
		String expired = past.issue(event, alice.id()).token();
		String tampered = valid.substring(0, valid.length() - 3) + (valid.endsWith("AAA") ? "BBB" : "AAA");
		String otherSender = new InviteTokenService(new javax.crypto.spec.SecretKeySpec("x".repeat(40).getBytes(), "HmacSHA256"), Clock.systemUTC())
				.issue(event, alice.id()).token();
		UUID endedEvent = createEvent("PUBLISHED", -30, -28L);
		String forEnded = new InviteTokenService(jwtSecretKey, Clock.systemUTC()).issue(endedEvent, alice.id()).token();
		UUID cancelled = createEvent("CANCELLED", 24, 26L);
		String forCancelled = new InviteTokenService(jwtSecretKey, Clock.systemUTC()).issue(cancelled, alice.id()).token();
		String forUnknownEvent = new InviteTokenService(jwtSecretKey, Clock.systemUTC()).issue(UUID.randomUUID(), alice.id()).token();

		String reference = resolve(bob, "garbage").andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		for (String token : new String[] {expired, tampered, otherSender, forEnded, forCancelled, forUnknownEvent, "", "a.b", "x".repeat(500)}) {
			if (token.isEmpty()) {
				continue;
			}
			String body = resolve(bob, token).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
			assertThat(JsonPath.<String>read(body, "$.title")).isEqualTo(JsonPath.<String>read(reference, "$.title"));
			assertThat(JsonPath.<String>read(body, "$.detail")).isEqualTo(JsonPath.<String>read(reference, "$.detail"));
			sendLink(bob, token).andExpect(status().isNotFound());
		}
		// il token valido resta valido, e non e' quello del JWT (ne' viceversa)
		resolve(bob, valid).andExpect(status().isOk());
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + valid)).andExpect(status().isUnauthorized());
		resolve(bob, bob.token()).andExpect(status().isNotFound());
		assertThat(links()).isZero();
	}

	@Test
	void theSenderOpeningTheirOwnLinkSeesNothing() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		String token = tokenOf(alice, event);
		resolve(alice, token).andExpect(status().isNotFound());
		sendLink(alice, token).andExpect(status().isNotFound());
		assertThat(links()).isZero();
	}

	@Test
	void theBaseEventLinkNeverDependsOnTheInvite() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		for (String param : new String[] {"", "?i=garbage", "?i=", "?i=a.b"}) {
			mockMvc.perform(get("/api/events/" + event + param)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(event.toString()));
		}
	}

	// ======================= richiesta Link =======================

	@Test
	void aNewUserCanSendAnExplicitLinkRequestToTheSenderAndItStaysPending() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = users().user("bob", "Bob");   // non visibile, non partecipa, nessun interesse in comune
		String token = tokenOf(alice, event);

		String body = sendLink(bob, token).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.direction").value("OUTGOING"))
				.andExpect(jsonPath("$.eventId").value(event.toString()))
				.andExpect(jsonPath("$.otherUser.displayName").value("Alice")).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("email").doesNotContain("assword");
		assertThat(links()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT status FROM link_request").query(String.class).single()).isEqualTo("PENDING");
		assertThat(jdbc.sql("SELECT requester_id FROM link_request").query(UUID.class).single()).isEqualTo(bob.id());

		// nessuna connessione automatica: Alice non e' collegata a Bob finche' non accetta
		resolve(bob, token).andExpect(jsonPath("$.linkStatus").value("OUTGOING_PENDING"));
		mockMvc.perform(get("/api/link-requests?direction=incoming&status=PENDING").header(HttpHeaders.AUTHORIZATION, alice.bearer()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
	}

	@Test
	void duplicatesAreRejectedInBothDirectionsAndTheExistingStateIsShown() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = visible("bob", "Bob");
		String aliceToken = tokenOf(alice, event);
		String bobToken = tokenOf(bob, event);

		sendLink(bob, aliceToken).andExpect(status().isCreated());
		sendLink(bob, aliceToken).andExpect(status().isConflict());                  // stessa richiesta due volte
		sendLink(alice, bobToken).andExpect(status().isConflict());                  // la richiesta inversa mentre l'altra e' pendente
		assertThat(links()).isEqualTo(1);

		resolve(bob, aliceToken).andExpect(jsonPath("$.linkStatus").value("OUTGOING_PENDING"));
		resolve(alice, bobToken).andExpect(jsonPath("$.linkStatus").value("INCOMING_PENDING"));

		UUID request = jdbc.sql("SELECT id FROM link_request").query(UUID.class).single();
		mockMvc.perform(post("/api/link-requests/" + request + "/accept").header(HttpHeaders.AUTHORIZATION, alice.bearer())).andExpect(status().isOk());
		resolve(bob, aliceToken).andExpect(jsonPath("$.linkStatus").value("ACCEPTED"));
		resolve(alice, bobToken).andExpect(jsonPath("$.linkStatus").value("ACCEPTED"));
		sendLink(bob, aliceToken).andExpect(status().isConflict());                  // gia' collegati
		assertThat(links()).isEqualTo(1);
	}

	@Test
	void aDeclinedRequestDoesNotRevealTheRefusalAndCannotBeResent() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = visible("bob", "Bob");
		String aliceToken = tokenOf(alice, event);
		String bobToken = tokenOf(bob, event);

		sendLink(bob, aliceToken).andExpect(status().isCreated());
		UUID request = jdbc.sql("SELECT id FROM link_request").query(UUID.class).single();
		mockMvc.perform(post("/api/link-requests/" + request + "/decline").header(HttpHeaders.AUTHORIZATION, alice.bearer())).andExpect(status().isOk());

		resolve(bob, aliceToken).andExpect(jsonPath("$.linkStatus").value("OUTGOING_PENDING"));   // la privacy del rifiuto e' quella di sempre
		resolve(alice, bobToken).andExpect(jsonPath("$.linkStatus").value("INCOMING_DECLINED"));
		sendLink(bob, aliceToken).andExpect(status().isConflict());
		assertThat(links()).isEqualTo(1);
	}

	@Test
	void theAntiSpamLimitsStillApplyToInviteRequests() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser bob = users().user("bob", "Bob");
		for (int i = 0; i < LinkRequestService.MAX_PER_DAY; i++) {
			TestUser sender = visible("sender" + i, "Sender " + i);
			sendLink(bob, tokenOf(sender, event)).andExpect(status().isCreated());
		}
		TestUser eleventh = visible("senderx", "Sender Extra");
		sendLink(bob, tokenOf(eleventh, event)).andExpect(status().isTooManyRequests());
		assertThat(links()).isEqualTo(LinkRequestService.MAX_PER_DAY);
	}

	@Test
	void aForwardedLinkGrantsNothingBeyondTheSameBoundedActions() throws Exception {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = visible("alice", "Alice");
		TestUser bob = users().user("bob", "Bob");
		TestUser carol = users().user("carol", "Carol");
		String token = tokenOf(alice, event);

		// Carol riceve il link inoltrato da Bob: vede lo stesso (solo nome e avatar di Alice) e puo' solo chiedere un Link ad Alice
		String body = resolve(carol, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("bob").doesNotContain("Bob");
		// non puo' chiedere un Link a Bob, ne' vedere il suo profilo, ne' usare l'invito per altro
		mockMvc.perform(get("/api/users/" + bob.id() + "/profile").header(HttpHeaders.AUTHORIZATION, carol.bearer())).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/users/" + alice.id() + "/profile").header(HttpHeaders.AUTHORIZATION, carol.bearer())).andExpect(status().isNotFound());
		sendLink(carol, token).andExpect(status().isCreated());
		assertThat(links()).isEqualTo(1);
	}

	// ======================= aiuti =======================

	private TestUser visible(String username, String displayName) throws Exception {
		TestUser user = users().user(username, displayName);
		discovery(user, true);
		return user;
	}

	private void discovery(TestUser user, boolean discoverable) throws Exception {
		mockMvc.perform(put("/api/users/me/discovery").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"discoverable\":" + discoverable + "}")).andExpect(status().isOk());
	}

	private ResultActions issue(TestUser user, UUID event) throws Exception {
		return mockMvc.perform(post("/api/events/" + event + "/invites").header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private String tokenOf(TestUser user, UUID event) throws Exception {
		return JsonPath.read(issue(user, event).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
	}

	private ResultActions resolve(TestUser viewer, String token) throws Exception {
		return mockMvc.perform(get("/api/invites/" + token).header(HttpHeaders.AUTHORIZATION, viewer.bearer()));
	}

	private ResultActions sendLink(TestUser viewer, String token) throws Exception {
		return mockMvc.perform(post("/api/invites/" + token + "/link-request").header(HttpHeaders.AUTHORIZATION, viewer.bearer()));
	}

	private long links() {
		return jdbc.sql("SELECT count(*) FROM link_request").query(Long.class).single();
	}

	private UUID createEvent(String status, long startHours, Long endHours) {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('INVITE test event', 'Created by a test', 'MUSIC', :status, :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("status", status)
				.param("start", Timestamp.from(now.plus(startHours, ChronoUnit.HOURS)))
				.param("end", endHours == null ? null : Timestamp.from(now.plus(endHours, ChronoUnit.HOURS)), java.sql.Types.TIMESTAMP)
				.query(UUID.class).single();
	}

}
