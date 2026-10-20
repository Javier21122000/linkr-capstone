package com.linkr.backend.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.linkr.backend.chat.ChatService;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * WebSocket end-to-end: server vero su porta casuale, client STOMP vero, database di test (linkr_test_db).
 *
 * Non usa @IntegrationTest perche' quello avvia un ambiente MOCK (senza porta); qui servono commit veri (il server risponde
 * su altri thread), quindi il test NON e' @Transactional e a fine test cancella SOLO gli utenti che ha creato
 * (richieste Link e messaggi spariscono con il CASCADE). URL e nome del database sono gli stessi di @IntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/linkr_test_db",
		"linkr.database.expected-name=linkr_test_db" })
@ActiveProfiles("test")
class ChatWebSocketTest {

	private static final String QUEUE = "/user/queue/messages";
	private static final long WAIT_MS = 5_000;
	private static final long QUIET_MS = 1_000;

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private ChatService chatService;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private final String run = UUID.randomUUID().toString().substring(0, 8);
	private final List<UUID> users = new ArrayList<>();
	private final List<WebSocketStompClient> stompClients = new ArrayList<>();
	private final HttpClient http = HttpClient.newHttpClient();

	@AfterEach
	void cleanUp() {
		stompClients.forEach(WebSocketStompClient::stop);
		if (!users.isEmpty()) {
			jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)").param("ids", users).update();   // CASCADE: Link e messaggi
		}
	}

	// ======================= autenticazione =======================

	@Test
	void connectionWithoutAValidTokenIsRejected() throws Exception {
		UUID anna = user("noauth");
		List<StompHeaders> attempts = new ArrayList<>();
		attempts.add(headers(null));
		attempts.add(headers("Bearer"));
		attempts.add(headers("Bearer "));
		attempts.add(headers("Bearer not.a.jwt"));
		attempts.add(headers("Basic " + token(anna)));
		attempts.add(headers("Bearer " + token(anna, Instant.now().minusSeconds(3600), "linkr", encoderWithAnotherKey())));   // firma di un altro
		attempts.add(headers("Bearer " + token(anna, Instant.now().minusSeconds(3600), "linkr", jwtEncoder)));                  // scaduto
		attempts.add(headers("Bearer " + token(anna, Instant.now().plusSeconds(3600), "someone-else", jwtEncoder)));            // issuer sbagliato
		attempts.add(headers("Bearer " + token(UUID.randomUUID().toString().replace("-", "x"), Instant.now().plusSeconds(3600), "linkr", jwtEncoder))); // sub non e' un id

		for (StompHeaders connect : attempts) {
			Client client = connect(connect, null);
			assertThat(client.awaitClosedOrError()).as("headers: %s", connect).isTrue();   // il server risponde ERROR e chiude
			assertThat(client.wasConnected()).as("headers: %s", connect).isFalse();
		}
	}

	@Test
	void theTokenInTheUrlIsNotAcceptedInPlaceOfTheHeader() throws Exception {
		UUID anna = user("query");
		Client client = connect(headers(null), "/ws?access_token=" + token(anna));

		assertThat(client.awaitClosedOrError()).isTrue();
		assertThat(client.wasConnected()).isFalse();
	}

	@Test
	void aValidTokenConnects() throws Exception {
		UUID anna = user("ok");

		Client client = connect(headers("Bearer " + token(anna)), null);

		assertThat(client.awaitConnected()).isTrue();
		assertThat(client.session.isConnected()).isTrue();
	}

	@Test
	void handshakeFromADisallowedOriginIsRejectedAndAnAllowedOneWorks() throws Exception {
		UUID anna = user("origin");

		Client evil = connect(headers("Bearer " + token(anna)), null, "http://evil.example");
		assertThat(evil.awaitClosedOrError()).isTrue();      // handshake rifiutato (403): nessun CONNECT arriva nemmeno al server
		assertThat(evil.wasConnected()).isFalse();

		Client good = connect(headers("Bearer " + token(anna)), null, "http://localhost:5173");
		assertThat(good.awaitConnected()).isTrue();
	}

	@Test
	void aSessionCannotSubscribeAfterItsTokenExpired() throws Exception {
		UUID anna = user("expiring");
		Client client = connect(headers("Bearer " + token(anna, Instant.now().plusSeconds(2), "linkr", jwtEncoder)), null);
		assertThat(client.awaitConnected()).isTrue();

		Thread.sleep(3_000);
		client.subscribe(QUEUE);

		assertThat(client.awaitClosedOrError()).isTrue();
	}

	// ======================= sottoscrizioni e invii =======================

	@Test
	void onlyTheOwnPrivateQueueCanBeSubscribed() throws Exception {
		UUID anna = user("subs");
		UUID bea = user("subs2");

		for (String destination : List.of("/queue/messages", "/user/" + bea + "/queue/messages", "/user/queue/other", "/topic/chat",
				"/queue/anything", "/user/queue/messages/extra", "/app/x")) {
			Client client = connect(headers("Bearer " + token(anna)), null);
			assertThat(client.awaitConnected()).isTrue();
			client.subscribe(destination);
			assertThat(client.awaitClosedOrError()).as("destination: %s", destination).isTrue();
		}
		Client allowed = connect(headers("Bearer " + token(anna)), null);
		assertThat(allowed.awaitConnected()).isTrue();
		allowed.subscribe(QUEUE);
		Thread.sleep(QUIET_MS);
		assertThat(allowed.session.isConnected()).isTrue();
	}

	@Test
	void arbitrarySendFramesAreRejectedAndNeverReachAnybody() throws Exception {
		UUID anna = user("send_a");
		UUID bea = user("send_b");
		Client victim = connect(headers("Bearer " + token(bea)), null);
		assertThat(victim.awaitConnected()).isTrue();
		victim.subscribe(QUEUE);

		for (String destination : List.of("/app/anything", "/queue/messages", "/user/" + bea + "/queue/messages", "/topic/x")) {
			Client attacker = connect(headers("Bearer " + token(anna)), null);
			assertThat(attacker.awaitConnected()).isTrue();
			attacker.session.send(destination, "{\"body\":\"fake\"}");
			assertThat(attacker.awaitClosedOrError()).as("destination: %s", destination).isTrue();
		}
		assertThat(victim.messages.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();
		assertThat(victim.session.isConnected()).isTrue();
	}

	// ======================= notifiche =======================

	@Test
	void aMessageSentViaRestIsPushedToBothPeopleAndToNobodyElse() throws Exception {
		UUID anna = user("push_a");
		UUID bea = user("push_b");
		UUID carl = user("push_c");
		UUID conversation = link(anna, bea, "ACCEPTED");
		Client forBea = subscribed(bea);
		Client forAnna = subscribed(anna);
		Client forAnnaSecondTab = subscribed(anna);
		Client forCarl = subscribed(carl);

		HttpResponse<String> response = sendRest(anna, conversation, "{\"body\":\"Ciao Bea!\"}");
		assertThat(response.statusCode()).isEqualTo(201);

		Map<String, Object> toBea = forBea.messages.poll(WAIT_MS, TimeUnit.MILLISECONDS);
		assertThat(toBea).isNotNull();
		assertThat(toBea).containsEntry("body", "Ciao Bea!").containsEntry("mine", false)
				.containsEntry("conversationId", conversation.toString()).containsEntry("senderId", anna.toString());
		assertThat(toBea.get("createdAt")).isInstanceOf(String.class);
		assertThat(toBea.keySet()).containsExactlyInAnyOrder("messageId", "conversationId", "senderId", "mine", "body", "createdAt", "readAt");
		for (Client sender : List.of(forAnna, forAnnaSecondTab)) {                     // il mittente, anche con piu' schede
			Map<String, Object> toAnna = sender.messages.poll(WAIT_MS, TimeUnit.MILLISECONDS);
			assertThat(toAnna).isNotNull();
			assertThat(toAnna).containsEntry("mine", true).containsEntry("messageId", toBea.get("messageId"));
		}
		assertThat(forCarl.messages.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();   // un terzo non riceve nulla
		assertThat(forBea.messages.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();    // e nessun duplicato

		// la notifica arriva solo per un messaggio gia' salvato
		assertThat(jdbc.sql("SELECT count(*) FROM chat_message WHERE id = :id").param("id", UUID.fromString((String) toBea.get("messageId")))
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void rejectedOrInvalidSendsNotifyNobody() throws Exception {
		UUID anna = user("rej_a");
		UUID bea = user("rej_b");
		UUID mallory = user("rej_m");
		UUID pending = link(anna, mallory, "PENDING");
		UUID conversation = link(anna, bea, "ACCEPTED");
		Client forBea = subscribed(bea);
		Client forAnna = subscribed(anna);
		Client forMallory = subscribed(mallory);

		assertThat(sendRest(mallory, conversation, "{\"body\":\"intruso\"}").statusCode()).isEqualTo(404);   // non e' parte della chat
		assertThat(sendRest(anna, pending, "{\"body\":\"in attesa\"}").statusCode()).isEqualTo(404);          // Link non accettato
		assertThat(sendRest(anna, conversation, "{\"body\":\"   \"}").statusCode()).isEqualTo(400);
		assertThat(sendRest(anna, conversation, "{\"body\":\"" + "x".repeat(2001) + "\"}").statusCode()).isEqualTo(400);

		for (Client client : List.of(forBea, forAnna, forMallory)) {
			assertThat(client.messages.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single()).isZero();
	}

	@Test
	void nothingIsPushedWhenTheTransactionRollsBack() throws Exception {
		UUID anna = user("rb_a");
		UUID bea = user("rb_b");
		UUID conversation = link(anna, bea, "ACCEPTED");
		Client forBea = subscribed(bea);

		transactionTemplate.execute(status -> {
			chatService.send(anna, conversation, "non verra' salvato");
			status.setRollbackOnly();
			return null;
		});

		assertThat(forBea.messages.poll(QUIET_MS, TimeUnit.MILLISECONDS)).isNull();
		assertThat(jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single()).isZero();

		// e una scrittura che va a buon fine, dopo, notifica normalmente
		chatService.send(anna, conversation, "questo si");
		assertThat(forBea.messages.poll(WAIT_MS, TimeUnit.MILLISECONDS)).containsEntry("body", "questo si");
	}

	@Test
	void restStillWorksWithoutAnyWebSocketConnection() throws Exception {
		UUID anna = user("nows_a");
		UUID bea = user("nows_b");
		UUID conversation = link(anna, bea, "ACCEPTED");

		assertThat(sendRest(anna, conversation, "{\"body\":\"nessuno ascolta\"}").statusCode()).isEqualTo(201);

		assertThat(jdbc.sql("SELECT count(*) FROM chat_message").query(Long.class).single()).isEqualTo(1);
	}

	// ======================= helper =======================

	/** Client STOMP connesso e gia' iscritto alla propria coda privata. */
	private Client subscribed(UUID user) throws Exception {
		Client client = connect(headers("Bearer " + token(user)), null);
		assertThat(client.awaitConnected()).isTrue();
		client.subscribe(QUEUE);
		Thread.sleep(300);   // il broker registra la sottoscrizione in modo asincrono
		return client;
	}

	private Client connect(StompHeaders connectHeaders, String path) {
		return connect(connectHeaders, path, null);
	}

	private Client connect(StompHeaders connectHeaders, String path, String origin) {
		WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		stompClient.setMessageConverter(new JacksonJsonMessageConverter());
		stompClients.add(stompClient);
		WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
		if (origin != null) {
			handshake.setOrigin(origin);
		}
		Client client = new Client();
		stompClient.connectAsync("ws://localhost:" + port + (path == null ? "/ws" : path), handshake, connectHeaders, client);
		return client;
	}

	private StompHeaders headers(String authorization) {
		StompHeaders headers = new StompHeaders();
		if (authorization != null) {
			headers.add("Authorization", authorization);
		}
		return headers;
	}

	/** Gestore di una sessione STOMP: raccoglie i messaggi della coda e annota errori e chiusure. */
	private static final class Client extends StompSessionHandlerAdapter {

		final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
		final CountDownLatch connected = new CountDownLatch(1);
		final CountDownLatch failed = new CountDownLatch(1);
		volatile StompSession session;

		@Override
		public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
			this.session = session;
			connected.countDown();
		}

		@Override
		public void handleFrame(StompHeaders headers, Object payload) {
			failed.countDown();                                // l'unico frame non legato a una sottoscrizione e' un ERROR
		}

		@Override
		public void handleTransportError(StompSession session, Throwable exception) {
			failed.countDown();
		}

		@Override
		public void handleException(StompSession session, org.springframework.messaging.simp.stomp.StompCommand command,
				StompHeaders headers, byte[] payload, Throwable exception) {
			failed.countDown();
		}

		boolean wasConnected() {
			return connected.getCount() == 0;
		}

		boolean awaitConnected() throws InterruptedException {
			return connected.await(WAIT_MS, TimeUnit.MILLISECONDS);
		}

		/** True se il server ha risposto con un ERROR / ha chiuso la connessione. */
		boolean awaitClosedOrError() throws InterruptedException {
			if (failed.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
				return true;
			}
			return session != null && eventually(() -> !session.isConnected());
		}

		void subscribe(String destination) {
			session.subscribe(destination, new StompFrameHandler() {
				@Override
				public java.lang.reflect.Type getPayloadType(StompHeaders headers) {
					return Map.class;
				}

				@Override
				@SuppressWarnings("unchecked")
				public void handleFrame(StompHeaders headers, Object payload) {
					messages.add((Map<String, Object>) payload);
				}
			});
		}

		private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
			long deadline = System.currentTimeMillis() + 2_000;
			while (System.currentTimeMillis() < deadline) {
				if (condition.getAsBoolean()) {
					return true;
				}
				Thread.sleep(50);
			}
			return condition.getAsBoolean();
		}

	}

	private HttpResponse<String> sendRest(UUID sender, UUID conversation, String json) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/conversations/" + conversation + "/messages"))
				.header("Authorization", "Bearer " + token(sender)).header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private String token(UUID user) {
		return token(user, Instant.now().plusSeconds(3600), "linkr", jwtEncoder);
	}

	private String token(UUID user, Instant expiresAt, String issuer, JwtEncoder encoder) {
		return token(user.toString(), expiresAt, issuer, encoder);
	}

	private String token(String subject, Instant expiresAt, String issuer, JwtEncoder encoder) {
		Instant issuedAt = expiresAt.minusSeconds(60);
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer(issuer).subject(subject).claim("roles", List.of("USER"))
						.issuedAt(issuedAt).expiresAt(expiresAt).build())).getTokenValue();
	}

	private JwtEncoder encoderWithAnotherKey() {
		byte[] key = (UUID.randomUUID().toString() + UUID.randomUUID()).getBytes();
		return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(key, "HmacSHA256")));
	}

	/** Utente inserito direttamente (il token lo firmiamo noi con la chiave dell'applicazione). */
	private UUID user(String name) {
		String username = "w" + run + name;
		UUID id = jdbc.sql("INSERT INTO app_user (username, email, password_hash, display_name) VALUES (:u, :e, 'not-a-hash', :u) RETURNING id")
				.param("u", username).param("e", username + "@example.com").query(UUID.class).single();
		users.add(id);
		return id;
	}

	private UUID link(UUID requester, UUID recipient, String status) {
		return jdbc.sql("""
				INSERT INTO link_request (requester_id, recipient_id, status, responded_at)
				VALUES (:a, :b, :s, CASE WHEN :s = 'PENDING' THEN NULL ELSE now() END) RETURNING id
				""")
				.param("a", requester).param("b", recipient).param("s", status).query(UUID.class).single();
	}

}
