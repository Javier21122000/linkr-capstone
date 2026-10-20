package com.linkr.backend.linkrequest;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Concorrenza REALE delle richieste Link da Discover People (thread con commit separati: il test NON e' @Transactional).
 * Verifica soprattutto che invii incrociati (A->B e B->A) non vadano in deadlock e che resti UNA sola riga.
 * Usa solo linkr_test_db e, a fine test, cancella SOLO gli utenti che ha creato (le richieste spariscono con il CASCADE).
 */
@IntegrationTest
class DiscoverLinkConcurrencyTest {

	@Autowired
	private LinkRequestService service;

	@Autowired
	private JdbcClient jdbc;

	private final String run = UUID.randomUUID().toString().substring(0, 8);
	private final List<UUID> users = new ArrayList<>();

	@AfterEach
	void cleanUpOwnRows() {
		if (!users.isEmpty()) {
			jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)").param("ids", users).update();
		}
	}

	@Test
	void crossedRequestsFromDiscoverCreateExactlyOneRowAndNeverDeadlock() throws Exception {
		UUID a = discoverable("a");
		UUID b = discoverable("b");

		List<Callable<HttpStatus>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			tasks.add(() -> attempt(a, b));
			tasks.add(() -> attempt(b, a));
		}
		// un deadlock del database arriverebbe qui come eccezione (non e' un LinkRequestException) e farebbe fallire il test
		List<HttpStatus> outcomes = runTogether(tasks);

		assertThat(outcomes.stream().filter(s -> s == null)).hasSize(1);
		assertThat(outcomes.stream().filter(s -> s == HttpStatus.CONFLICT)).hasSize(11);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM link_request
				 WHERE (requester_id = :a AND recipient_id = :b) OR (requester_id = :b AND recipient_id = :a)
				""").param("a", a).param("b", b).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void aRecipientHidingWhileRequestsArriveNeverLeavesARequestToAHiddenPerson() throws Exception {
		UUID target = discoverable("target");
		List<UUID> senders = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			senders.add(discoverable("s" + i));
		}

		List<Callable<HttpStatus>> tasks = new ArrayList<>();
		for (UUID sender : senders) {
			tasks.add(() -> attempt(sender, target));
		}
		tasks.add(() -> {
			jdbc.sql("UPDATE app_user SET discoverable = false WHERE id = :id").param("id", target).update();
			return null;
		});
		List<HttpStatus> outcomes = runTogether(tasks);

		// ogni richiesta o e' stata accettata PRIMA che il destinatario si nascondesse, o e' stata rifiutata (404): nessun errore tecnico
		assertThat(outcomes).allMatch(s -> s == null || s == HttpStatus.NOT_FOUND);
		long created = jdbc.sql("SELECT count(*) FROM link_request WHERE recipient_id = :id").param("id", target).query(Long.class).single();
		assertThat(created).isEqualTo(outcomes.stream().filter(s -> s == null).count() - 1);   // -1: il task di "hide" restituisce null
	}

	// ---------- helper ----------

	/** null = richiesta creata; altrimenti lo stato HTTP dell'errore di dominio. */
	private HttpStatus attempt(UUID from, UUID to) {
		try {
			service.send(from, null, to);
			return null;
		}
		catch (LinkRequestException e) {
			return e.getStatus();
		}
	}

	private List<HttpStatus> runTogether(List<Callable<HttpStatus>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch ready = new CountDownLatch(tasks.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<HttpStatus>> futures = new ArrayList<>();
			for (Callable<HttpStatus> task : tasks) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return task.call();
				}));
			}
			ready.await();
			go.countDown();
			List<HttpStatus> outcomes = new ArrayList<>();
			for (Future<HttpStatus> future : futures) {
				outcomes.add(future.get());
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

	/** Utente visibile in Discover People con l'interesse "techno". */
	private UUID discoverable(String name) {
		String username = "d" + run + name;
		UUID id = jdbc.sql("""
				INSERT INTO app_user (username, email, password_hash, display_name, discoverable)
				VALUES (:u, :e, 'not-a-hash', :u, true) RETURNING id
				""")
				.param("u", username).param("e", username + "@example.com").query(UUID.class).single();
		users.add(id);
		jdbc.sql("INSERT INTO user_interest (user_id, interest_id) SELECT :u, id FROM interest WHERE slug = 'techno'")
				.param("u", id).update();
		return id;
	}

}
