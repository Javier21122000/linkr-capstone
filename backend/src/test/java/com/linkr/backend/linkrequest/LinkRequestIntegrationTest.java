package com.linkr.backend.linkrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
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
 * Richieste Link con database di test (linkr_test_db). Ogni test e' @Transactional: tutto viene annullato dal rollback.
 * La concorrenza reale (thread con commit separati) e' in LinkRequestConcurrencyTest.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class LinkRequestIntegrationTest {

	private record TestUser(String username, UUID id, String token) {
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	// ======================= invio =======================

	@Test
	void validRequestReturns201WithPublicDataOnly() throws Exception {
		Scenario s = scenario();

		String body = send(s.alice, s.event, s.bob).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.direction").value("OUTGOING"))
				.andExpect(jsonPath("$.respondedAt").doesNotExist())
				.andExpect(jsonPath("$.eventId").value(s.event.toString()))
				.andExpect(jsonPath("$.eventTitle").value("LINK test event"))
				.andExpect(jsonPath("$.otherUser.userId").value(s.bob.id().toString()))
				.andExpect(jsonPath("$.otherUser.username").value("bob"))
				.andExpect(jsonPath("$.otherUser.displayName").value("Bob"))
				.andReturn().getResponse().getContentAsString();

		Map<String, Object> root = JsonPath.read(body, "$");
		assertThat(root.keySet()).containsExactlyInAnyOrder("requestId", "status", "direction", "createdAt", "respondedAt",
				"eventId", "eventTitle", "otherUser");
		Map<String, Object> other = JsonPath.read(body, "$.otherUser");
		assertThat(other.keySet()).containsExactlyInAnyOrder("userId", "username", "displayName", "avatarUrl");
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("assword")
				.doesNotContain("role").doesNotContain("Hash");
		assertThat(rows()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT requester_id FROM link_request").query(UUID.class).single()).isEqualTo(s.alice.id());
	}

	@Test
	void requiresAValidJwt() throws Exception {
		Scenario s = scenario();
		String json = body(s.event, s.bob);
		mockMvc.perform(post("/api/link-requests").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(s.alice.id().toString()).claim("roles", List.of("USER"))
						.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build()))
				.getTokenValue();
		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
				.contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isUnauthorized());
		for (String path : List.of("/api/link-requests", "/api/link-requests/" + UUID.randomUUID() + "/accept")) {
			mockMvc.perform(path.endsWith("accept") ? post(path) : get(path)).andExpect(status().isUnauthorized());
		}
		assertThat(rows()).isZero();
	}

	@Test
	void requestToYourselfReturns400() throws Exception {
		Scenario s = scenario();

		send(s.alice, s.event, s.alice).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid link request"));
		assertThat(rows()).isZero();
	}

	@Test
	void unknownDraftAndCancelledEventsReturn404() throws Exception {
		Scenario s = scenario();
		for (UUID event : List.of(UUID.randomUUID(), createEvent("DRAFT", 24, 26L), createEvent("CANCELLED", 24, 26L))) {
			send(s.alice, event, s.bob).andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Event not found"));
		}
		assertThat(rows()).isZero();
	}

	@Test
	void endedEventsReturn409WithAndWithoutEndsAt() throws Exception {
		Scenario s = scenario();
		UUID endedWithEnd = createEvent("PUBLISHED", -5, -1L);
		UUID endedWithoutEnd = createEvent("PUBLISHED", -4, null);
		for (UUID event : List.of(endedWithEnd, endedWithoutEnd)) {
			participate(s.alice, event, "GOING", true);
			participate(s.bob, event, "GOING", true);
			send(s.alice, event, s.bob).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Event has ended"));
		}
		assertThat(rows()).isZero();
	}

	@Test
	void senderWithoutParticipationGets403() throws Exception {
		Scenario s = scenario();
		TestUser outsider = user("outsider", "Outsider");

		send(outsider, s.event, s.bob).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Participation required"));
		assertThat(rows()).isZero();
	}

	@Test
	void unavailableRecipientsAllGetTheSameUniform404() throws Exception {
		Scenario s = scenario();
		TestUser notParticipating = user("notpart", "NotPart");
		TestUser hidden = user("hiddenone", "Hidden");
		participate(hidden, s.event, "GOING", false);
		TestUser participatesElsewhere = user("elsewhere", "Elsewhere");
		participate(participatesElsewhere, createEvent("PUBLISHED", 24, 26L), "GOING", true);

		List<String> bodies = new java.util.ArrayList<>();
		for (UUID recipient : List.of(UUID.randomUUID(), notParticipating.id(), hidden.id(), participatesElsewhere.id())) {
			String body = mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, bearer(s.alice))
							.contentType(MediaType.APPLICATION_JSON).content(body(s.event, recipient)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.title").value("Person not available"))
					.andReturn().getResponse().getContentAsString();
			bodies.add(body.replaceAll("\"instance\":\"[^\"]*\"", ""));
		}
		assertThat(bodies).hasSize(4).allMatch(b -> b.equals(bodies.get(0))); // stessa risposta: non si distingue il caso
		assertThat(rows()).isZero();
	}

	@Test
	void privateSenderCanStillSendRequests() throws Exception {
		Scenario s = scenario();
		TestUser privateUser = user("privateone", "Private");
		participate(privateUser, s.event, "INTERESTED", false); // partecipante ma NON visibile

		send(privateUser, s.event, s.bob).andExpect(status().isCreated());
	}

	@Test
	void invalidPayloadsReturn400AndSenderIdInBodyIsIgnored() throws Exception {
		Scenario s = scenario();
		// eventId e' facoltativo (richiesta da Discover People): manca solo recipientId, oppure e' invalido
		for (String payload : List.of("{}", "{\"eventId\":\"" + s.event + "\"}", "{\"eventId\":null}",
				"{\"eventId\":\"nope\",\"recipientId\":\"" + s.bob.id() + "\"}", "{not json", "")) {
			mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, bearer(s.alice))
					.contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
		}
		// un senderId/requesterId nel body non ha effetto: il mittente e' quello del token
		mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, bearer(s.alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"senderId\":\"" + s.bob.id() + "\",\"requesterId\":\"" + s.bob.id() + "\",\"eventId\":\""
								+ s.event + "\",\"recipientId\":\"" + s.bob.id() + "\"}"))
				.andExpect(status().isCreated());
		assertThat(jdbc.sql("SELECT requester_id FROM link_request").query(UUID.class).single()).isEqualTo(s.alice.id());
	}

	// ======================= unicita' della coppia =======================

	@Test
	void duplicateInSameDirectionReverseDirectionAndOtherEventAreAllRejectedWith409() throws Exception {
		Scenario s = scenario();
		UUID otherEvent = createEvent("PUBLISHED", 30, 32L);
		participate(s.alice, otherEvent, "GOING", true);
		participate(s.bob, otherEvent, "GOING", true);
		sendOk(s.alice, s.event, s.bob);

		send(s.alice, s.event, s.bob).andExpect(status().isConflict())                // stessa direzione
				.andExpect(jsonPath("$.title").value("Link request already exists"));
		send(s.bob, s.event, s.alice).andExpect(status().isConflict());                // direzione inversa
		send(s.alice, otherEvent, s.bob).andExpect(status().isConflict());             // altro evento, stessa direzione
		send(s.bob, otherEvent, s.alice).andExpect(status().isConflict());             // altro evento, direzione inversa
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	void declinedRequestCannotBeSentAgainByEitherPerson() throws Exception {
		Scenario s = scenario();
		String id = sendOk(s.alice, s.event, s.bob);
		act("/api/link-requests/" + id + "/decline", s.bob).andExpect(status().isOk());

		send(s.alice, s.event, s.bob).andExpect(status().isConflict()); // il mittente riprova: stesso 409 di una PENDING
		send(s.bob, s.event, s.alice).andExpect(status().isConflict()); // chi ha rifiutato non puo' inviare a sua volta
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	void databaseEnforcesPairUniquenessAndOtherConstraints() {
		Scenario s = scenario();
		jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, event_id) VALUES (:a, :b, :e)")
				.param("a", s.alice.id()).param("b", s.bob.id()).param("e", s.event).update();

		// indice univoco sulla coppia NON ordinata: anche la direzione inversa viola il vincolo
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO link_request (requester_id, recipient_id) VALUES (:a, :b)")
				.param("a", s.bob.id()).param("b", s.alice.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	// Un test per vincolo: dopo la prima istruzione fallita PostgreSQL annulla la transazione, quindi non se ne puo' provare un'altra.
	@Test
	void databaseRejectsSelfRequests() {
		Scenario s = scenario();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO link_request (requester_id, recipient_id) VALUES (:a, :a)")
				.param("a", s.alice.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsInvalidStatus() {
		Scenario s = scenario();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, status, responded_at) VALUES (:a, :b, 'MAYBE', now())")
				.param("a", s.alice.id()).param("b", s.bob.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAnAnsweredRequestWithoutRespondedAt() {
		Scenario s = scenario();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, status) VALUES (:a, :b, 'ACCEPTED')")
				.param("a", s.alice.id()).param("b", s.bob.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsAPendingRequestWithRespondedAt() {
		Scenario s = scenario();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, status, responded_at) VALUES (:a, :b, 'PENDING', now())")
				.param("a", s.alice.id()).param("b", s.bob.id()).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	// ======================= accettazione e rifiuto =======================

	@Test
	void onlyTheRecipientCanAccept() throws Exception {
		Scenario s = scenario();
		TestUser third = user("third", "Third");
		String id = sendOk(s.alice, s.event, s.bob);

		act("/api/link-requests/" + id + "/accept", s.alice).andExpect(status().isNotFound()); // il mittente
		act("/api/link-requests/" + id + "/accept", third).andExpect(status().isNotFound());   // un terzo
		act("/api/link-requests/" + UUID.randomUUID() + "/accept", s.bob).andExpect(status().isNotFound());
		assertThat(storedStatus(id)).isEqualTo("PENDING");

		act("/api/link-requests/" + id + "/accept", s.bob).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andExpect(jsonPath("$.direction").value("INCOMING"))
				.andExpect(jsonPath("$.respondedAt").isNotEmpty())
				.andExpect(jsonPath("$.otherUser.username").value("alice"));
		assertThat(storedStatus(id)).isEqualTo("ACCEPTED");
	}

	@Test
	void onlyTheRecipientCanDecline() throws Exception {
		Scenario s = scenario();
		TestUser third = user("third2", "Third");
		String id = sendOk(s.alice, s.event, s.bob);

		act("/api/link-requests/" + id + "/decline", s.alice).andExpect(status().isNotFound());
		act("/api/link-requests/" + id + "/decline", third).andExpect(status().isNotFound());
		assertThat(storedStatus(id)).isEqualTo("PENDING");

		act("/api/link-requests/" + id + "/decline", s.bob).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DECLINED")).andExpect(jsonPath("$.respondedAt").isNotEmpty());
		assertThat(storedStatus(id)).isEqualTo("DECLINED");
	}

	@Test
	void repeatingTheSameAnswerIsIdempotentAndContradictionsReturn409() throws Exception {
		Scenario s = scenario();
		String accepted = sendOk(s.alice, s.event, s.bob);
		String first = act("/api/link-requests/" + accepted + "/accept", s.bob).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String second = act("/api/link-requests/" + accepted + "/accept", s.bob).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(second).isEqualTo(first); // nessuna modifica: stesso respondedAt
		act("/api/link-requests/" + accepted + "/decline", s.bob).andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Link request already answered"));
		assertThat(storedStatus(accepted)).isEqualTo("ACCEPTED");

		TestUser carol = user("carol", "Carol");
		participate(carol, s.event, "GOING", true);
		String declined = sendOk(s.alice, s.event, carol);
		String d1 = act("/api/link-requests/" + declined + "/decline", carol).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String d2 = act("/api/link-requests/" + declined + "/decline", carol).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(d2).isEqualTo(d1);
		act("/api/link-requests/" + declined + "/accept", carol).andExpect(status().isConflict());
		assertThat(storedStatus(declined)).isEqualTo("DECLINED");
	}

	// ======================= elenchi, filtri, privacy del rifiuto =======================

	@Test
	void incomingAndOutgoingListsContainOnlyOwnRequests() throws Exception {
		Scenario s = scenario();
		TestUser dave = user("dave", "Dave");
		TestUser erin = user("erin", "Erin");
		participate(dave, s.event, "GOING", true);
		participate(erin, s.event, "GOING", true);
		sendOk(s.alice, s.event, s.bob);   // alice -> bob
		sendOk(dave, s.event, s.alice);    // dave -> alice
		sendOk(erin, s.event, dave);       // erin -> dave (alice non c'entra)

		list(s.alice, "direction=incoming").andExpect(jsonPath("$.items[*].otherUser.username", contains("dave")))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].direction").value("INCOMING"));
		list(s.alice, "direction=outgoing").andExpect(jsonPath("$.items[*].otherUser.username", contains("bob")))
				.andExpect(jsonPath("$.items[0].direction").value("OUTGOING"));
		list(s.alice, "direction=all").andExpect(jsonPath("$.totalElements").value(2));
		list(s.alice, "").andExpect(jsonPath("$.items[*].otherUser.username", contains("dave"))); // default: incoming
		list(s.bob, "direction=incoming").andExpect(jsonPath("$.items[*].otherUser.username", contains("alice")));
		list(s.bob, "direction=outgoing").andExpect(jsonPath("$.totalElements").value(0));
		list(user("nobody", "Nobody"), "direction=all").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void statusFilterWorksForTheRecipient() throws Exception {
		Scenario s = scenario();
		TestUser dave = user("dave2", "Dave");
		TestUser erin = user("erin2", "Erin");
		participate(dave, s.event, "GOING", true);
		participate(erin, s.event, "GOING", true);
		String a = sendOk(dave, s.event, s.alice);
		String b = sendOk(erin, s.event, s.alice);
		sendOk(s.bob, s.event, s.alice);
		act("/api/link-requests/" + a + "/accept", s.alice).andExpect(status().isOk());
		act("/api/link-requests/" + b + "/decline", s.alice).andExpect(status().isOk());

		list(s.alice, "direction=incoming&status=PENDING").andExpect(jsonPath("$.items[*].otherUser.username", contains("bob")));
		list(s.alice, "direction=incoming&status=ACCEPTED").andExpect(jsonPath("$.items[*].otherUser.username", contains("dave2")));
		list(s.alice, "direction=incoming&status=DECLINED").andExpect(jsonPath("$.items[*].otherUser.username", contains("erin2")))
				.andExpect(jsonPath("$.items[0].status").value("DECLINED"));
		list(s.alice, "direction=incoming").andExpect(jsonPath("$.totalElements").value(3));
	}

	@Test
	void declineIsHiddenFromTheSenderEverywhere() throws Exception {
		Scenario s = scenario();
		String id = sendOk(s.alice, s.event, s.bob);
		act("/api/link-requests/" + id + "/decline", s.bob).andExpect(status().isOk());

		// il mittente: sempre PENDING, mai respondedAt, mai nel filtro DECLINED
		for (String direction : List.of("outgoing", "all")) {
			list(s.alice, "direction=" + direction).andExpect(jsonPath("$.items[0].status").value("PENDING"))
					.andExpect(jsonPath("$.items[0].respondedAt").doesNotExist());
			list(s.alice, "direction=" + direction + "&status=PENDING").andExpect(jsonPath("$.totalElements").value(1))
					.andExpect(jsonPath("$.items[0].status").value("PENDING"));
			list(s.alice, "direction=" + direction + "&status=DECLINED").andExpect(jsonPath("$.totalElements").value(0));
			list(s.alice, "direction=" + direction + "&status=ACCEPTED").andExpect(jsonPath("$.totalElements").value(0));
		}
		// il destinatario vede il vero stato
		list(s.bob, "direction=incoming").andExpect(jsonPath("$.items[0].status").value("DECLINED"))
				.andExpect(jsonPath("$.items[0].respondedAt").isNotEmpty());
		list(s.bob, "direction=incoming&status=PENDING").andExpect(jsonPath("$.totalElements").value(0));
		list(s.bob, "direction=all&status=DECLINED").andExpect(jsonPath("$.totalElements").value(1));
		// nessun altro endpoint lo rivela: la riproposizione e' un 409 identico a quello di una PENDING
		send(s.alice, s.event, s.bob).andExpect(status().isConflict());
	}

	@Test
	void acceptedRequestIsVisibleToBothWithRespondedAt() throws Exception {
		Scenario s = scenario();
		String id = sendOk(s.alice, s.event, s.bob);
		act("/api/link-requests/" + id + "/accept", s.bob).andExpect(status().isOk());

		list(s.alice, "direction=outgoing&status=ACCEPTED").andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
				.andExpect(jsonPath("$.items[0].respondedAt").isNotEmpty());
		list(s.bob, "direction=all&status=ACCEPTED").andExpect(jsonPath("$.totalElements").value(1));
		list(s.alice, "direction=outgoing&status=PENDING").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void listsNeverExposePrivateFieldsAndAreOrderedAndPaginated() throws Exception {
		Scenario s = scenario();
		for (int i = 1; i <= 3; i++) {
			TestUser sender = user("sender" + i, "Sender" + i);
			participate(sender, s.event, "GOING", true);
			String id = sendOk(sender, s.event, s.alice);
			jdbc.sql("UPDATE link_request SET created_at = now() - make_interval(hours => :h) WHERE id = :id")
					.param("h", 10 - i).param("id", UUID.fromString(id)).update(); // sender3 e' il piu' recente
		}
		String body = list(s.alice, "direction=incoming&size=2&page=0").andExpect(status().isOk())
				.andExpect(jsonPath("$.items[*].otherUser.username", contains("sender3", "sender2")))
				.andExpect(jsonPath("$.totalElements").value(3)).andExpect(jsonPath("$.totalPages").value(2))
				.andReturn().getResponse().getContentAsString();
		list(s.alice, "direction=incoming&size=2&page=1").andExpect(jsonPath("$.items[*].otherUser.username", contains("sender1")));
		list(s.alice, "direction=incoming&size=2&page=2").andExpect(jsonPath("$.items.length()").value(0));
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("assword")
				.doesNotContain("role").doesNotContain("Hash").doesNotContain("updatedAt");
	}

	@Test
	void invalidListParametersReturn400() throws Exception {
		Scenario s = scenario();
		for (String query : List.of("direction=sideways", "direction=INCOMING", "status=foo", "status=pending", "size=0",
				"size=51", "size=abc", "page=-1", "page=abc")) {
			list(s.alice, query).andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		mockMvc.perform(post("/api/link-requests/not-a-uuid/accept").header(HttpHeaders.AUTHORIZATION, bearer(s.alice)))
				.andExpect(status().isBadRequest());
		list(s.alice, "size=50").andExpect(status().isOk());
	}

	// ======================= persistenza del Link =======================

	@Test
	void acceptedLinkSurvivesHiddenOrDeletedParticipationEndedEventAndDeletedEvent() throws Exception {
		Scenario s = scenario();
		String id = sendOk(s.alice, s.event, s.bob);
		act("/api/link-requests/" + id + "/accept", s.bob).andExpect(status().isOk());

		// Bob nasconde la partecipazione, Alice la elimina
		mockMvc.perform(put("/api/events/" + s.event + "/participation").header(HttpHeaders.AUTHORIZATION, bearer(s.bob))
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"GOING\",\"visibleToOthers\":false}"))
				.andExpect(status().isOk());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/events/" + s.event + "/participation").header(HttpHeaders.AUTHORIZATION, bearer(s.alice)))
				.andExpect(status().isNoContent());
		// l'evento termina
		jdbc.sql("UPDATE event SET starts_at = now() - interval '6 hours', ends_at = now() - interval '2 hours' WHERE id = :id")
				.param("id", s.event).update();
		for (TestUser user : List.of(s.alice, s.bob)) {
			list(user, "direction=all&status=ACCEPTED").andExpect(jsonPath("$.totalElements").value(1))
					.andExpect(jsonPath("$.items[0].eventTitle").value("LINK test event"));
		}
		// l'evento viene cancellato: il Link resta, senza contesto
		jdbc.sql("DELETE FROM event WHERE id = :id").param("id", s.event).update();
		list(s.alice, "direction=all&status=ACCEPTED").andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.items[0].eventId").doesNotExist())
				.andExpect(jsonPath("$.items[0].eventTitle").doesNotExist());
		assertThat(rows()).isEqualTo(1);
	}

	@Test
	void pendingRequestStaysAnswerableAfterTheRecipientHidesOrTheEventEnds() throws Exception {
		Scenario s = scenario();
		String id = sendOk(s.alice, s.event, s.bob);
		jdbc.sql("UPDATE event_participation SET visible_to_others = false WHERE user_id = :u").param("u", s.bob.id()).update();
		jdbc.sql("UPDATE event SET starts_at = now() - interval '6 hours', ends_at = now() - interval '2 hours' WHERE id = :id")
				.param("id", s.event).update();

		act("/api/link-requests/" + id + "/accept", s.bob).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
	}

	@Test
	void deletingAUserCascadesToTheirRequests() {
		Scenario s = scenario();
		jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, event_id) VALUES (:a, :b, :e)")
				.param("a", s.alice.id()).param("b", s.bob.id()).param("e", s.event).update();

		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", s.bob.id()).update();

		assertThat(rows()).isZero();
	}

	// ======================= anti-spam =======================

	@Test
	void awaitingAnswerLimitCountsPendingAndDeclinedRequests() throws Exception {
		Scenario s = scenario();
		TestUser r1 = eligible("limit_r1", s.event);
		TestUser r2 = eligible("limit_r2", s.event);
		// 19 richieste inviate "in attesa" (14 PENDING + 5 DECLINED, che il mittente non sa rifiutate), di 2 giorni fa
		for (int i = 0; i < 19; i++) {
			UUID other = rawUser("old" + i);
			jdbc.sql("""
					INSERT INTO link_request (requester_id, recipient_id, status, responded_at, created_at)
					VALUES (:a, :b, :st, CASE WHEN :st = 'DECLINED' THEN now() ELSE NULL END, now() - interval '2 days')
					""").param("a", s.alice.id()).param("b", other).param("st", i < 5 ? "DECLINED" : "PENDING").update();
		}
		sendOk(s.alice, s.event, r1.id() == null ? s.bob : r1);          // la 20esima e' consentita
		send(s.alice, s.event, r2).andExpect(status().isTooManyRequests())    // la 21esima no
				.andExpect(jsonPath("$.title").value("Too many link requests"));
		assertThat(rows()).isEqualTo(20);
	}

	@Test
	void acceptedRequestsDoNotCountAsAwaitingButDailyLimitApplies() throws Exception {
		Scenario s = scenario();
		TestUser r1 = eligible("daily_r1", s.event);
		TestUser r2 = eligible("daily_r2", s.event);
		// 9 ACCEPTED create nell'ultima ora: non sono "in attesa", ma contano per il limite giornaliero (10)
		for (int i = 0; i < 9; i++) {
			jdbc.sql("""
					INSERT INTO link_request (requester_id, recipient_id, status, responded_at, created_at)
					VALUES (:a, :b, 'ACCEPTED', now(), now() - interval '1 hour')
					""").param("a", s.alice.id()).param("b", rawUser("daily" + i)).update();
		}
		sendOk(s.alice, s.event, r1);                                            // la 10esima delle 24 ore
		send(s.alice, s.event, r2).andExpect(status().isTooManyRequests());      // l'11esima no
	}

	@Test
	void oldRequestsDoNotCountForTheDailyLimit() throws Exception {
		Scenario s = scenario();
		TestUser r1 = eligible("old_r1", s.event);
		for (int i = 0; i < 10; i++) {
			jdbc.sql("""
					INSERT INTO link_request (requester_id, recipient_id, status, responded_at, created_at)
					VALUES (:a, :b, 'ACCEPTED', now(), now() - interval '25 hours')
					""").param("a", s.alice.id()).param("b", rawUser("oldday" + i)).update();
		}
		sendOk(s.alice, s.event, r1);
	}

	// ======================= discovery: linkStatus =======================

	@Test
	void peopleDiscoveryShowsTheLinkStatusFromEachPointOfView() throws Exception {
		Scenario s = scenario(); // alice (io), bob: entrambi visibili
		TestUser none = eligible("ls_none", s.event);
		TestUser outgoing = eligible("ls_out", s.event);
		TestUser incoming = eligible("ls_in", s.event);
		TestUser declinedByMe = eligible("ls_declinedbyme", s.event);
		TestUser declinedMe = eligible("ls_declinedme", s.event);
		TestUser accepted = eligible("ls_acc", s.event);
		sendOk(s.alice, s.event, outgoing);
		String inId = sendOk(incoming, s.event, s.alice);
		String dByMe = sendOk(declinedByMe, s.event, s.alice);
		act("/api/link-requests/" + dByMe + "/decline", s.alice).andExpect(status().isOk());
		String dMe = sendOk(s.alice, s.event, declinedMe);
		act("/api/link-requests/" + dMe + "/decline", declinedMe).andExpect(status().isOk());
		String acc = sendOk(s.alice, s.event, accepted);
		act("/api/link-requests/" + acc + "/accept", accepted).andExpect(status().isOk());

		String body = mockMvc.perform(get("/api/events/" + s.event + "/people?size=50").header(HttpHeaders.AUTHORIZATION, bearer(s.alice)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(statusOf(body, "bob")).isEqualTo("NONE");
		assertThat(statusOf(body, "ls_none")).isEqualTo("NONE");
		assertThat(statusOf(body, "ls_out")).isEqualTo("OUTGOING_PENDING");
		assertThat(statusOf(body, "ls_in")).isEqualTo("INCOMING_PENDING");
		assertThat(statusOf(body, "ls_declinedbyme")).isEqualTo("INCOMING_DECLINED");   // io ho rifiutato: non si riabilita l'invio
		assertThat(statusOf(body, "ls_declinedme")).isEqualTo("OUTGOING_PENDING");      // mi hanno rifiutato: non lo scopro
		assertThat(statusOf(body, "ls_acc")).isEqualTo("ACCEPTED");
		assertThat(requestIdOf(body, "ls_in")).isEqualTo(inId);
		assertThat(requestIdOf(body, "ls_none")).isNull();
		assertThat(none.id()).isNotNull();

		// dal punto di vista dell'altro
		String fromIncoming = mockMvc.perform(get("/api/events/" + s.event + "/people?size=50").header(HttpHeaders.AUTHORIZATION, bearer(incoming)))
				.andReturn().getResponse().getContentAsString();
		assertThat(statusOf(fromIncoming, "alice")).isEqualTo("OUTGOING_PENDING");
		String fromAccepted = mockMvc.perform(get("/api/events/" + s.event + "/people?size=50").header(HttpHeaders.AUTHORIZATION, bearer(accepted)))
				.andReturn().getResponse().getContentAsString();
		assertThat(statusOf(fromAccepted, "alice")).isEqualTo("ACCEPTED");
		String fromDeclinedMe = mockMvc.perform(get("/api/events/" + s.event + "/people?size=50").header(HttpHeaders.AUTHORIZATION, bearer(declinedMe)))
				.andReturn().getResponse().getContentAsString();
		assertThat(statusOf(fromDeclinedMe, "alice")).isEqualTo("INCOMING_DECLINED");
	}

	// ======================= regressione =======================

	@Test
	void publicEndpointsStayPublicAndLinkRequestsAreNeverPublic() throws Exception {
		Scenario s = scenario();
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + s.event)).andExpect(status().isOk());
		mockMvc.perform(get("/api/interests")).andExpect(status().isOk());
		mockMvc.perform(get("/api/link-requests")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(s.alice))).andExpect(status().isOk());
	}

	// ======================= helper =======================

	private record Scenario(UUID event, TestUser alice, TestUser bob) {
	}

	/** Evento futuro con alice e bob entrambi partecipanti e VISIBILI. */
	private Scenario scenario() {
		UUID event = createEvent("PUBLISHED", 24, 26L);
		TestUser alice = user("alice", "Alice");
		TestUser bob = user("bob", "Bob");
		participate(alice, event, "GOING", true);
		participate(bob, event, "GOING", true);
		return new Scenario(event, alice, bob);
	}

	private TestUser eligible(String username, UUID event) {
		TestUser user = user(username, username);
		participate(user, event, "GOING", true);
		return user;
	}

	private String body(UUID event, TestUser recipient) {
		return body(event, recipient.id());
	}

	private String body(UUID event, UUID recipient) {
		return "{\"eventId\":\"" + event + "\",\"recipientId\":\"" + recipient + "\"}";
	}

	private ResultActions send(TestUser sender, UUID event, TestUser recipient) throws Exception {
		return mockMvc.perform(post("/api/link-requests").header(HttpHeaders.AUTHORIZATION, bearer(sender))
				.contentType(MediaType.APPLICATION_JSON).content(body(event, recipient)));
	}

	/** Invio che deve riuscire: restituisce l'id della richiesta. */
	private String sendOk(TestUser sender, UUID event, TestUser recipient) throws Exception {
		String response = send(sender, event, recipient).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(response, "$.requestId");
	}

	/** POST autenticato senza corpo (accept/decline). */
	private ResultActions act(String path, TestUser user) throws Exception {
		return mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(user)));
	}

	private ResultActions list(TestUser user, String query) throws Exception {
		return mockMvc.perform(get("/api/link-requests" + (query.isEmpty() ? "" : "?" + query))
				.header(HttpHeaders.AUTHORIZATION, bearer(user)));
	}

	private String storedStatus(String id) {
		return jdbc.sql("SELECT status FROM link_request WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single();
	}

	private long rows() {
		return jdbc.sql("SELECT count(*) FROM link_request").query(Long.class).single();
	}

	private String statusOf(String body, String username) {
		List<String> statuses = JsonPath.read(body, "$.items[?(@.username=='" + username + "')].linkStatus");
		return statuses.get(0);
	}

	private String requestIdOf(String body, String username) {
		List<String> ids = JsonPath.read(body, "$.items[?(@.username=='" + username + "')].linkRequestId");
		return ids.isEmpty() ? null : ids.get(0);
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

	/** Utente inserito direttamente (senza login): serve solo come "altra persona" nei conteggi anti-spam. */
	private UUID rawUser(String username) {
		return jdbc.sql("INSERT INTO app_user (username, email, password_hash, display_name) VALUES (:u, :e, 'not-a-hash', :u) RETURNING id")
				.param("u", username).param("e", username + "@example.com").query(UUID.class).single();
	}

	private void participate(TestUser user, UUID event, String status, boolean visible) {
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, :s, :v)")
				.param("u", user.id()).param("e", event).param("s", status).param("v", visible).update();
	}

	private UUID createEvent(String status, long startHours, Long endHours) {
		Instant now = Instant.now();
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('LINK test event', 'Created by a test', 'MUSIC', :status, :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("status", status)
				.param("start", Timestamp.from(now.plus(startHours, ChronoUnit.HOURS)))
				.param("end", endHours == null ? null : Timestamp.from(now.plus(endHours, ChronoUnit.HOURS)), Types.TIMESTAMP)
				.query(UUID.class).single();
	}

}
