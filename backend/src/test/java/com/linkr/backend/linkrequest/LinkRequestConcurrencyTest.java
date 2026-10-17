package com.linkr.backend.linkrequest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.linkr.backend.IntegrationTest;

/**
 * Concorrenza REALE: i thread chiamano il service con transazioni e commit separati (il test NON e' @Transactional).
 * Usa solo linkr_test_db e, a fine test, cancella SOLO le righe che ha creato (utenti ed eventi con nome casuale;
 * le partecipazioni e le richieste spariscono con il CASCADE).
 */
@IntegrationTest
class LinkRequestConcurrencyTest {

	@Autowired
	private LinkRequestService service;

	@Autowired
	private JdbcClient jdbc;

	private final String run = UUID.randomUUID().toString().substring(0, 8);
	private final List<UUID> users = new ArrayList<>();
	private final List<UUID> events = new ArrayList<>();

	@AfterEach
	void cleanUpOwnRows() {
		if (!users.isEmpty()) {
			jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)").param("ids", users).update();
		}
		if (!events.isEmpty()) {
			jdbc.sql("DELETE FROM event WHERE id IN (:ids)").param("ids", events).update();
		}
	}

	@Test
	void simultaneousRequestsInBothDirectionsCreateExactlyOneRow() throws Exception {
		UUID event = event();
		UUID a = participant("a", event);
		UUID b = participant("b", event);

		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			tasks.add(() -> run(() -> service.send(a, event, b)));
			tasks.add(() -> run(() -> service.send(b, event, a)));
		}
		List<Outcome> outcomes = runTogether(tasks);

		assertThat(outcomes.stream().filter(o -> o.status == null)).hasSize(1);                         // un solo 201
		assertThat(outcomes.stream().filter(o -> o.status == HttpStatus.CONFLICT)).hasSize(7);          // gli altri 409
		assertThat(jdbc.sql("""
				SELECT count(*) FROM link_request
				 WHERE (requester_id = :a AND recipient_id = :b) OR (requester_id = :b AND recipient_id = :a)
				""").param("a", a).param("b", b).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void simultaneousRequestsCannotBypassTheAwaitingAnswerLimit() throws Exception {
		UUID event = event();
		UUID sender = participant("sender", event);
		UUID r1 = participant("r1", event);
		UUID r2 = participant("r2", event);
		for (int i = 0; i < LinkRequestService.MAX_AWAITING_ANSWER - 1; i++) { // 19 richieste gia' in attesa, di 2 giorni fa
			jdbc.sql("""
					INSERT INTO link_request (requester_id, recipient_id, created_at)
					VALUES (:a, :b, now() - interval '2 days')
					""").param("a", sender).param("b", rawUser("old" + i)).update();
		}

		List<Outcome> outcomes = runTogether(List.of(
				() -> run(() -> service.send(sender, event, r1)),
				() -> run(() -> service.send(sender, event, r2))));

		// senza il blocco sul mittente passerebbero entrambe (la 20esima e la 21esima): deve passarne UNA sola
		assertThat(outcomes.stream().filter(o -> o.status == null)).hasSize(1);
		assertThat(outcomes.stream().filter(o -> o.status == HttpStatus.TOO_MANY_REQUESTS)).hasSize(1);
		assertThat(jdbc.sql("SELECT count(*) FROM link_request WHERE requester_id = :s").param("s", sender)
				.query(Long.class).single()).isEqualTo(LinkRequestService.MAX_AWAITING_ANSWER);
	}

	@Test
	void simultaneousAcceptAndDeclineHaveExactlyOneWinner() throws Exception {
		UUID event = event();
		UUID a = participant("req", event);
		UUID b = participant("rec", event);
		UUID id = service.send(a, event, b).requestId();

		List<Callable<Outcome>> tasks = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			tasks.add(() -> run(() -> service.accept(b, id)));
			tasks.add(() -> run(() -> service.decline(b, id)));
		}
		List<Outcome> outcomes = runTogether(tasks);

		String finalStatus = jdbc.sql("SELECT status FROM link_request WHERE id = :id").param("id", id).query(String.class).single();
		assertThat(finalStatus).isIn("ACCEPTED", "DECLINED");
		// chi ha vinto e chi ha ripetuto la stessa risposta: 200; la risposta opposta: 409. Mai stati intermedi.
		assertThat(outcomes.stream().filter(o -> o.status == null)).hasSize(3);
		assertThat(outcomes.stream().filter(o -> o.status == HttpStatus.CONFLICT)).hasSize(3);
		assertThat(outcomes).allMatch(o -> o.status == null || o.status == HttpStatus.CONFLICT);
	}

	// ---------- helper ----------

	private record Outcome(HttpStatus status) {
	}

	private Outcome run(Runnable action) {
		try {
			action.run();
			return new Outcome(null);
		}
		catch (LinkRequestException e) {
			return new Outcome(e.getStatus());
		}
	}

	/** Fa partire tutti i task nello stesso istante. */
	private List<Outcome> runTogether(List<Callable<Outcome>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Outcome>> futures = new ArrayList<>();
			for (Callable<Outcome> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await();
			go.countDown();
			List<Outcome> outcomes = new ArrayList<>();
			for (Future<Outcome> future : futures) {
				outcomes.add(future.get());
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private UUID event() {
		UUID id = jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('CONC test event', 'Created by a test', 'MUSIC', 'PUBLISHED', :start, :end, 'Test venue',
				        'Test address', 'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("start", Timestamp.from(Instant.now().plus(24, ChronoUnit.HOURS)))
				.param("end", Timestamp.from(Instant.now().plus(26, ChronoUnit.HOURS)))
				.query(UUID.class).single();
		events.add(id);
		return id;
	}

	private UUID rawUser(String name) {
		String username = "c" + run + name;
		UUID id = jdbc.sql("INSERT INTO app_user (username, email, password_hash, display_name) VALUES (:u, :e, 'not-a-hash', :u) RETURNING id")
				.param("u", username).param("e", username + "@example.com").query(UUID.class).single();
		users.add(id);
		return id;
	}

	/** Utente partecipante VISIBILE all'evento. */
	private UUID participant(String name, UUID event) {
		UUID id = rawUser(name);
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status, visible_to_others) VALUES (:u, :e, 'GOING', true)")
				.param("u", id).param("e", event).update();
		return id;
	}

}
