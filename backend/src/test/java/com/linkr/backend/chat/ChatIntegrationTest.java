package com.linkr.backend.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/**
 * Chat REST con database di test (linkr_test_db). Ogni test e' @Transactional: tutto viene annullato dal rollback.
 * Le conversazioni sono richieste Link inserite direttamente (ACCEPTED, PENDING, DECLINED) per controllare lo stato.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
class ChatIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ApplicationEvents applicationEvents;

	private TestUsers users;

	private TestUsers users() {
		if (users == null) {
			users = new TestUsers(mockMvc, jdbc, null);
		}
		return users;
	}

	// ======================= sicurezza =======================

	@Test
	void everyEndpointRequiresAValidJwt() throws Exception {
		UUID any = UUID.randomUUID();
		mockMvc.perform(get("/api/conversations")).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/conversations/" + any + "/messages")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/conversations/" + any + "/messages").contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/conversations/" + any + "/read")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/conversations").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
		assertThat(messages()).isZero();
	}

	@Test
	void onlyAcceptedLinksHaveAChatAndEveryOtherCaseLooksTheSame() throws Exception {
		TestUser anna = users().user("ch_na_a");
		TestUser bea = users().user("ch_na_b");
		TestUser carl = users().user("ch_na_c");
		TestUser dan = users().user("ch_na_d");
		UUID pending = link(anna, bea, "PENDING");
		UUID declined = link(anna, carl, "DECLINED");

		String reference = null;
		for (UUID conversation : List.of(pending, declined, UUID.randomUUID())) {
			for (TestUser user : List.of(anna, bea, carl, dan)) {
				String listBody = messagesOf(user, conversation, "").andExpect(status().isNotFound())
						.andExpect(jsonPath("$.title").value("Conversation not found")).andReturn().getResponse().getContentAsString();
				// "instance" contiene l'id richiesto (diverso per costruzione): si confronta tutto il resto
				String comparable = JsonPath.read(listBody, "$.status") + "|" + JsonPath.read(listBody, "$.title") + "|"
						+ JsonPath.read(listBody, "$.detail");
				if (reference == null) {
					reference = comparable;
				}
				assertThat(comparable).isEqualTo(reference);         // un rifiuto o un Link in attesa non si distinguono da "non esiste"
				send(user, conversation, "ciao").andExpect(status().isNotFound());
				read(user, conversation).andExpect(status().isNotFound());
			}
		}
		assertThat(messages()).isZero();                              // nessuna riga scritta
		conversations(anna, "").andExpect(jsonPath("$.totalElements").value(0));
		conversations(bea, "").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void aThirdUserCanNeitherReadNorWriteNorMarkRead() throws Exception {
		TestUser anna = users().user("ch_third_a");
		TestUser bea = users().user("ch_third_b");
		TestUser mallory = users().user("ch_third_m");
		UUID conversation = link(anna, bea, "ACCEPTED");
		sendOk(anna, conversation, "segreto");

		messagesOf(mallory, conversation, "").andExpect(status().isNotFound());
		send(mallory, conversation, "intruso").andExpect(status().isNotFound());
		read(mallory, conversation).andExpect(status().isNotFound());
		conversations(mallory, "").andExpect(jsonPath("$.totalElements").value(0));

		assertThat(messages()).isEqualTo(1);
		assertThat(unreadRows(conversation)).isEqualTo(1);           // mallory non ha segnato nulla come letto
	}

	// ======================= invio =======================

	@Test
	void bothPeopleOfAnAcceptedLinkCanWriteAndSeeEachOthersMessages() throws Exception {
		TestUser anna = users().user("ch_send_a", "Anna");
		TestUser bea = users().user("ch_send_b", "Bea");
		UUID conversation = link(anna, bea, "ACCEPTED");

		String sent = send(anna, conversation, "  Ciao Bea!  ").andExpect(status().isCreated())
				.andExpect(jsonPath("$.conversationId").value(conversation.toString()))
				.andExpect(jsonPath("$.senderId").value(anna.id().toString()))
				.andExpect(jsonPath("$.mine").value(true))
				.andExpect(jsonPath("$.body").value("Ciao Bea!"))
				.andExpect(jsonPath("$.readAt").doesNotExist())
				.andReturn().getResponse().getContentAsString();
		Map<String, Object> root = JsonPath.read(sent, "$");
		assertThat(root.keySet()).containsExactlyInAnyOrder("messageId", "conversationId", "senderId", "mine", "body", "createdAt", "readAt");
		sendOk(bea, conversation, "Ciao Anna");

		for (TestUser viewer : List.of(anna, bea)) {
			String body = messagesOf(viewer, conversation, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat((List<String>) JsonPath.read(body, "$.items[*].body")).containsExactly("Ciao Anna", "Ciao Bea!");
			assertThat((List<Boolean>) JsonPath.read(body, "$.items[*].mine")).containsExactly(viewer == bea, viewer == anna);
		}
		assertThat(jdbc.sql("SELECT sender_id FROM chat_message ORDER BY created_at").query(UUID.class).list())
				.containsExactly(anna.id(), bea.id());
	}

	@Test
	void theSenderIsAlwaysTheTokenUser() throws Exception {
		TestUser anna = users().user("ch_spoof_a");
		TestUser bea = users().user("ch_spoof_b");
		UUID conversation = link(anna, bea, "ACCEPTED");

		mockMvc.perform(post("/api/conversations/" + conversation + "/messages").header(HttpHeaders.AUTHORIZATION, anna.bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"body\":\"hola\",\"senderId\":\"" + bea.id() + "\",\"conversationId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.senderId").value(anna.id().toString()))
				.andExpect(jsonPath("$.conversationId").value(conversation.toString()));
		assertThat(jdbc.sql("SELECT sender_id FROM chat_message").query(UUID.class).single()).isEqualTo(anna.id());
	}

	@Test
	void bodyMustHaveBetweenOneAndTwoThousandCharacters() throws Exception {
		TestUser anna = users().user("ch_body_a");
		TestUser bea = users().user("ch_body_b");
		UUID conversation = link(anna, bea, "ACCEPTED");

		for (String raw : List.of("{}", "{\"body\":null}", "{\"body\":\"\"}", "{\"body\":\"   \"}", "{\"body\":\"\\n\\t\"}",
				"{\"body\":\"" + "x".repeat(2001) + "\"}", "{\"body\":[\"a\"]}", "{\"body\":{\"a\":1}}", "{not json", "")) {
			mockMvc.perform(post("/api/conversations/" + conversation + "/messages").header(HttpHeaders.AUTHORIZATION, anna.bearer())
					.contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isBadRequest());
		}
		assertThat(messages()).isZero();

		send(anna, conversation, "x".repeat(2000)).andExpect(status().isCreated());
		send(anna, conversation, "Ciao 👋 è una bella giornata <b>html</b> \"quote\" \\ back").andExpect(status().isCreated())
				.andExpect(jsonPath("$.body").value("Ciao 👋 è una bella giornata <b>html</b> \"quote\" \\ back"));
		assertThat(messages()).isEqualTo(2);
	}

	@Test
	void sendingPublishesOneEventPerPersonWithTheirOwnView() throws Exception {
		TestUser anna = users().user("ch_event_a");
		TestUser bea = users().user("ch_event_b");
		UUID conversation = link(anna, bea, "ACCEPTED");

		sendOk(anna, conversation, "notifica");

		List<ChatMessageCreated> events = applicationEvents.stream(ChatMessageCreated.class).toList();
		assertThat(events).hasSize(1);
		ChatMessageCreated event = events.get(0);
		assertThat(event.senderId()).isEqualTo(anna.id());
		assertThat(event.recipientId()).isEqualTo(bea.id());
		assertThat(event.forSender().mine()).isTrue();
		assertThat(event.forRecipient().mine()).isFalse();
		assertThat(event.forRecipient().readAt()).isNull();
		assertThat(event.forRecipient().body()).isEqualTo("notifica");

		send(users().user("ch_event_x"), conversation, "intruso").andExpect(status().isNotFound());
		assertThat(applicationEvents.stream(ChatMessageCreated.class).count()).isEqualTo(1);   // un rifiuto non notifica nulla
	}

	// ======================= lettura e paginazione =======================

	@Test
	void messagesPageBackwardsWithoutGapsOrDuplicates() throws Exception {
		TestUser anna = users().user("ch_page_a");
		TestUser bea = users().user("ch_page_b");
		UUID conversation = link(anna, bea, "ACCEPTED");
		for (int i = 1; i <= 7; i++) {
			sendOk(i % 2 == 0 ? bea : anna, conversation, "m" + i);
		}

		String first = messagesOf(anna, conversation, "size=3").andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(first, "$.items[*].body")).containsExactly("m7", "m6", "m5");
		String second = messagesOf(anna, conversation, "size=3&before=" + lastId(first)).andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(second, "$.items[*].body")).containsExactly("m4", "m3", "m2");
		String third = messagesOf(anna, conversation, "size=3&before=" + lastId(second)).andExpect(jsonPath("$.hasMore").value(false))
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(third, "$.items[*].body")).containsExactly("m1");

		// pagina esattamente piena: nessun "hasMore" falso
		messagesOf(anna, conversation, "size=7").andExpect(jsonPath("$.items.length()").value(7)).andExpect(jsonPath("$.hasMore").value(false));
		messagesOf(anna, conversation, "").andExpect(jsonPath("$.items.length()").value(7));
	}

	@Test
	void pagingRejectsInvalidParametersAndForeignCursors() throws Exception {
		TestUser anna = users().user("ch_badpage_a");
		TestUser bea = users().user("ch_badpage_b");
		TestUser carl = users().user("ch_badpage_c");
		UUID conversation = link(anna, bea, "ACCEPTED");
		UUID other = link(anna, carl, "ACCEPTED");
		String foreign = JsonPath.read(send(anna, other, "altra chat").andReturn().getResponse().getContentAsString(), "$.messageId");
		sendOk(anna, conversation, "uno");

		for (String query : List.of("size=0", "size=51", "size=abc", "before=not-a-uuid", "before=" + UUID.randomUUID(), "before=" + foreign)) {
			messagesOf(anna, conversation, query).andExpect(status().isBadRequest());
		}
		for (String query : List.of("page=-1", "size=0", "size=51", "page=abc")) {
			conversations(anna, query).andExpect(status().isBadRequest());
		}
	}

	// ======================= non letti =======================

	@Test
	void unreadCountsAreForTheRecipientOnlyAndMarkReadClearsThemIdempotently() throws Exception {
		TestUser anna = users().user("ch_unread_a");
		TestUser bea = users().user("ch_unread_b");
		UUID conversation = link(anna, bea, "ACCEPTED");
		sendOk(anna, conversation, "uno");
		sendOk(anna, conversation, "due");
		sendOk(bea, conversation, "risposta");

		conversations(bea, "").andExpect(jsonPath("$.items[0].unreadCount").value(2));
		conversations(anna, "").andExpect(jsonPath("$.items[0].unreadCount").value(1));

		read(bea, conversation).andExpect(status().isNoContent());
		read(bea, conversation).andExpect(status().isNoContent());                    // idempotente
		conversations(bea, "").andExpect(jsonPath("$.items[0].unreadCount").value(0));
		conversations(anna, "").andExpect(jsonPath("$.items[0].unreadCount").value(1)); // i messaggi di Bea restano non letti per Anna

		// Anna vede che Bea ha letto i suoi messaggi; Bea non vede "letto" sui messaggi che ha ricevuto
		String forAnna = messagesOf(anna, conversation, "").andReturn().getResponse().getContentAsString();
		assertThat((List<Object>) JsonPath.read(forAnna, "$.items[?(@.body=='uno')].readAt")).hasSize(1).doesNotContainNull();
		assertThat((List<Object>) JsonPath.read(forAnna, "$.items[?(@.body=='risposta')].readAt")).containsOnlyNulls();
		String forBea = messagesOf(bea, conversation, "").andReturn().getResponse().getContentAsString();
		assertThat((List<Object>) JsonPath.read(forBea, "$.items[?(@.body=='uno')].readAt")).containsOnlyNulls();
		assertThat((List<Object>) JsonPath.read(forBea, "$.items[?(@.body=='risposta')].readAt")).containsOnlyNulls();   // Anna non l'ha ancora letta

		read(anna, conversation).andExpect(status().isNoContent());
		conversations(anna, "").andExpect(jsonPath("$.items[0].unreadCount").value(0));
		forBea = messagesOf(bea, conversation, "").andReturn().getResponse().getContentAsString();
		assertThat((List<Object>) JsonPath.read(forBea, "$.items[?(@.body=='risposta')].readAt")).hasSize(1).doesNotContainNull();
	}

	@Test
	void markReadNeverTouchesMyOwnOrOtherConversations() throws Exception {
		TestUser anna = users().user("ch_iso_a");
		TestUser bea = users().user("ch_iso_b");
		TestUser carl = users().user("ch_iso_c");
		UUID withBea = link(anna, bea, "ACCEPTED");
		UUID withCarl = link(anna, carl, "ACCEPTED");
		sendOk(bea, withBea, "da bea");
		sendOk(carl, withCarl, "da carl");

		read(anna, withBea).andExpect(status().isNoContent());

		assertThat(unreadRows(withBea)).isZero();
		assertThat(unreadRows(withCarl)).isEqualTo(1);
	}

	// ======================= elenco conversazioni =======================

	@Test
	void conversationsListOnlyMyAcceptedLinksNewestActivityFirst() throws Exception {
		TestUser anna = users().user("ch_list_a", "Anna");
		TestUser bea = users().user("ch_list_b", "Bea");
		TestUser carl = users().user("ch_list_c", "Carl");
		TestUser dan = users().user("ch_list_d", "Dan");
		TestUser eve = users().user("ch_list_e", "Eve");
		UUID withBea = link(anna, bea, "ACCEPTED");
		UUID withCarl = link(carl, anna, "ACCEPTED");        // io sono la destinataria
		UUID withDan = link(anna, dan, "ACCEPTED");          // senza messaggi
		link(anna, eve, "PENDING");                          // non e' una conversazione
		sendOk(bea, withBea, "primo");
		sendOk(carl, withCarl, "ultimo");

		String body = conversations(anna, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat((List<String>) JsonPath.read(body, "$.items[*].conversationId"))
				.containsExactly(withCarl.toString(), withBea.toString(), withDan.toString());
		assertThat((List<String>) JsonPath.read(body, "$.items[*].otherUser.username")).containsExactly("ch_list_c", "ch_list_b", "ch_list_d");
		assertThat((List<String>) JsonPath.read(body, "$.items[?(@.otherUser.username=='ch_list_c')].lastMessage.body")).containsExactly("ultimo");
		assertThat((List<Object>) JsonPath.read(body, "$.items[?(@.otherUser.username=='ch_list_d')].lastMessage")).containsOnlyNulls();
		assertThat((List<Object>) JsonPath.read(body, "$.items[*].linkedAt")).doesNotContainNull().hasSize(3);
		Map<String, Object> other = JsonPath.read(body, "$.items[0].otherUser");
		assertThat(other.keySet()).containsExactlyInAnyOrder("userId", "username", "displayName", "avatarUrl");
		assertThat(body).doesNotContain("email").doesNotContain("@example.com").doesNotContain("assword");

		// dal punto di vista di Eve (richiesta in attesa) non c'e' nulla
		conversations(eve, "").andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void conversationsArePaginated() throws Exception {
		TestUser anna = users().user("ch_cp_a");
		for (int i = 0; i < 5; i++) {
			link(anna, users().user("ch_cp_p" + i), "ACCEPTED");
		}

		List<String> all = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			String body = conversations(anna, "page=" + page + "&size=2").andExpect(jsonPath("$.totalElements").value(5))
					.andExpect(jsonPath("$.totalPages").value(3)).andReturn().getResponse().getContentAsString();
			all.addAll(JsonPath.read(body, "$.items[*].conversationId"));
		}
		assertThat(all).hasSize(5).doesNotHaveDuplicates();
	}

	@Test
	void anAcceptedLinkKeepsItsChatEvenWithoutAnyEventOrDiscoveryState() throws Exception {
		TestUser anna = users().user("ch_persist_a");
		TestUser bea = users().user("ch_persist_b");
		UUID conversation = link(anna, bea, "ACCEPTED");       // il Link non ha evento e nessuno e' visibile in Discover
		assertThat(jdbc.sql("SELECT event_id FROM link_request WHERE id = :id").param("id", conversation).query().singleRow().get("event_id")).isNull();

		send(anna, conversation, "funziona").andExpect(status().isCreated());
		send(bea, conversation, "anche qui").andExpect(status().isCreated());
	}

	// ======================= cancellazioni a cascata =======================

	@Test
	void messagesDisappearWithTheirLinkOrTheirSender() throws Exception {
		TestUser anna = users().user("ch_cascade_a");
		TestUser bea = users().user("ch_cascade_b");
		TestUser carl = users().user("ch_cascade_c");
		UUID withBea = link(anna, bea, "ACCEPTED");
		UUID withCarl = link(anna, carl, "ACCEPTED");
		sendOk(anna, withBea, "a bea");
		sendOk(anna, withCarl, "a carl");

		jdbc.sql("DELETE FROM link_request WHERE id = :id").param("id", withBea).update();
		assertThat(messages()).isEqualTo(1);
		jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", anna.id()).update();
		assertThat(messages()).isZero();
	}

	@Test
	void databaseRejectsBlankBodiesEvenIfTheApiIsBypassed() throws Exception {
		assertDatabaseRejectsBody("   ");
	}

	@Test
	void databaseRejectsOversizedBodiesEvenIfTheApiIsBypassed() throws Exception {
		assertDatabaseRejectsBody("x".repeat(2001));
	}

	/** Un solo inserimento per test: dopo un errore SQL PostgreSQL annulla l'intera transazione del test. */
	private void assertDatabaseRejectsBody(String body) throws Exception {
		TestUser anna = users().user("ch_db_a");
		TestUser bea = users().user("ch_db_b");
		UUID conversation = link(anna, bea, "ACCEPTED");

		org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
				() -> jdbc.sql("INSERT INTO chat_message (link_request_id, sender_id, body) VALUES (:c, :s, :b)")
						.param("c", conversation).param("s", anna.id()).param("b", body).update());
	}

	// ======================= helper =======================

	private ResultActions send(TestUser user, UUID conversation, String body) throws Exception {
		return mockMvc.perform(post("/api/conversations/" + conversation + "/messages").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"body\":" + quote(body) + "}"));
	}

	private void sendOk(TestUser user, UUID conversation, String body) throws Exception {
		send(user, conversation, body).andExpect(status().isCreated());
	}

	private ResultActions messagesOf(TestUser user, UUID conversation, String query) throws Exception {
		return mockMvc.perform(get("/api/conversations/" + conversation + "/messages" + (query.isEmpty() ? "" : "?" + query))
				.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private ResultActions conversations(TestUser user, String query) throws Exception {
		return mockMvc.perform(get("/api/conversations" + (query.isEmpty() ? "" : "?" + query)).header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private ResultActions read(TestUser user, UUID conversation) throws Exception {
		return mockMvc.perform(post("/api/conversations/" + conversation + "/read").header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private String lastId(String pageBody) {
		List<String> ids = JsonPath.read(pageBody, "$.items[*].messageId");
		return ids.get(ids.size() - 1);
	}

	/** JSON di una stringa con escape (virgolette, backslash, a capo). */
	private String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	/** Richiesta Link inserita direttamente, nello stato indicato. */
	private UUID link(TestUser requester, TestUser recipient, String status) {
		return jdbc.sql("""
				INSERT INTO link_request (requester_id, recipient_id, status, responded_at)
				VALUES (:a, :b, :s, CASE WHEN :s = 'PENDING' THEN NULL ELSE now() END) RETURNING id
				""")
				.param("a", requester.id()).param("b", recipient.id()).param("s", status).query(UUID.class).single();
	}

	private long messages() {
		return jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single();
	}

	private long unreadRows(UUID conversation) {
		return jdbc.sql("SELECT count(*) FROM chat_message WHERE link_request_id = :c AND read_at IS NULL")
				.param("c", conversation).query(Long.class).single();
	}

}
