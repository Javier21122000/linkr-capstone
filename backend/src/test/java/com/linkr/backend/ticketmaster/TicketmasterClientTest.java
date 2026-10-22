package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.KEY;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.NOW;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.WINDOW_END;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.linkr.backend.ticketmaster.TicketmasterApi.EventSearch;
import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;

/**
 * Il client contro un server finto (MockRestServiceServer) e, per i timeout veri, contro un mini server HTTP su localhost.
 * Nessun test arriva a Ticketmaster.
 */
class TicketmasterClientTest {

	private static final String BASE = "https://app.ticketmaster.com/discovery/v2";
	private static final EventSearch SEARCH = new EventSearch(NOW, WINDOW_END, 50);

	private final List<Duration> sleeps = new ArrayList<>();
	private final AtomicLong nanos = new AtomicLong();
	private final CallStats stats = new CallStats();

	private HttpServer localServer;

	@AfterEach
	void stopLocalServer() {
		if (localServer != null) {
			localServer.stop(0);
		}
	}

	private record Fixture(TicketmasterClient client, MockRestServiceServer server) {
	}

	private Fixture fixture(String... properties) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		TicketmasterClient client = new TicketmasterClient(TicketmasterTestData.properties(properties), builder, nanos::get, sleeps::add);
		return new Fixture(client, server);
	}

	private static String okPage() {
		return page(0, 1, 1, event("ev1").json());
	}

	// ---------- richiesta ----------

	@Test
	void sendsTheDocumentedQueryWithTheKeyAndReadsThePage() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE + "/events.json?")))
				.andExpect(method(HttpMethod.GET))
				.andExpect(queryParam("apikey", KEY))
				.andExpect(queryParam("startDateTime", "2026-10-22T10:00:00Z"))
				.andExpect(queryParam("endDateTime", "2026-11-21T10:00:00Z"))
				.andExpect(queryParam("size", "50"))
				.andExpect(queryParam("page", "3"))
				.andExpect(queryParam("sort", "date,asc"))
				.andExpect(queryParam("locale", "it-it,it,*"))
				.andExpect(queryParam("countryCode", "IT"))
				.andExpect(queryParam("unit", "km"))
				.andExpect(queryParam("radius", "24"))
				.andExpect(queryParam("geoPoint", Geohash.encode(45.475, 9.2, 7)))
				.andExpect(header(HttpHeaders.ACCEPT, containsString("application/json")))
				.andRespond(withSuccess(page(3, 5, 230, event("ev1").json()), MediaType.APPLICATION_JSON));

		EventsPage result = f.client().fetchEvents(SEARCH, 3, stats);

		assertThat(result.events()).hasSize(1);
		assertThat(result.page().totalElements()).isEqualTo(230);
		assertThat(stats.calls).isEqualTo(1);
		assertThat(stats.pagesFetched).isEqualTo(1);
		assertThat(stats.retries).isZero();
		f.server().verify();
	}

	@Test
	void canUseTheLegacyLatlongFilter() {
		Fixture f = fixture("location-filter", "LATLONG");
		f.server().expect(requestTo(containsString("latlong=45.475,9.2")))
				.andExpect(requestTo(not(containsString("geoPoint"))))
				.andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		f.client().fetchEvents(SEARCH, 0, stats);

		f.server().verify();
	}

	@Test
	void readsQuotaHeaders() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE)))
				.andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON).header("Rate-Limit", "5000").header("Rate-Limit-Available", "4987"));

		f.client().fetchEvents(SEARCH, 0, stats);

		assertThat(stats.quotaLimit).isEqualTo(5000);
		assertThat(stats.quotaAvailable).isEqualTo(4987);
	}

	@Test
	void constructionAndIdleTimeSendNothing() {
		Fixture f = fixture();

		f.server().verify(); // nessuna richiesta attesa, nessuna ricevuta
		assertThat(stats.calls).isZero();
	}

	@Test
	void withoutAnApiKeyNoRequestIsSent() {
		Fixture f = fixture("api-key", "   ");

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAUTHORIZED));
		f.server().verify();
		assertThat(stats.calls).isZero();
	}

	// ---------- 401 / 403 / altri 4xx: nessun retry ----------

	@Test
	void unauthorizedIsNotRetried() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"fault\":{\"faultstring\":\"Invalid ApiKey\"}}"));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.UNAUTHORIZED);
					assertThat(e.httpStatus()).isEqualTo(401);
					assertThat(e.attempts()).isEqualTo(1);
					assertThat(e.getMessage()).doesNotContain("Invalid ApiKey"); // mai il testo della fonte
				});
		assertThat(stats.calls).isEqualTo(1);
		assertThat(stats.retries).isZero();
		assertThat(sleeps).isEmpty();
		f.server().verify();
	}

	@Test
	void forbiddenIsNotRetried() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.FORBIDDEN);
					assertThat(e.httpStatus()).isEqualTo(403);
				});
		assertThat(stats.calls).isEqualTo(1);
		f.server().verify();
	}

	@Test
	void otherClientErrorsAreNotRetried() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_REQUEST));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR);
					assertThat(e.httpStatus()).isEqualTo(400);
				});
		assertThat(stats.calls).isEqualTo(1);
	}

	@Test
	void redirectsAreReportedNotFollowed() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.FOUND).header(HttpHeaders.LOCATION, "https://elsewhere.example/"));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR));
		assertThat(stats.calls).isEqualTo(1);
	}

	// ---------- 429 ----------

	@Test
	void rateLimitWithShortRetryAfterIsRetriedOnceAfterWaiting() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2"));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		EventsPage result = f.client().fetchEvents(SEARCH, 0, stats);

		assertThat(result.events()).hasSize(1);
		assertThat(sleeps).containsExactly(Duration.ofSeconds(2));
		assertThat(stats.calls).isEqualTo(2);
		assertThat(stats.retries).isEqualTo(1);
		assertThat(stats.rateLimitedResponses).isEqualTo(1);
		assertThat(stats.longestRetryAfter).isEqualTo(Duration.ofSeconds(2));
		f.server().verify();
	}

	@Test
	void rateLimitWithALongRetryAfterStopsWithoutWaiting() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60"));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
					assertThat(e.httpStatus()).isEqualTo(429);
					assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(60));
				});
		assertThat(sleeps).isEmpty();
		assertThat(stats.calls).isEqualTo(1);
		assertThat(stats.rateLimitedResponses).isEqualTo(1);
		assertThat(stats.longestRetryAfter).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void persistentRateLimitIsRetriedAtMostOnceEvenWithMoreRetriesAllowed() {
		Fixture f = fixture("max-retries", "5");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.RATE_LIMITED);
					assertThat(e.attempts()).isEqualTo(2);
				});
		assertThat(stats.calls).isEqualTo(2);
		assertThat(stats.rateLimitedResponses).isEqualTo(2);
		f.server().verify();
	}

	@Test
	void rateLimitWithoutRetryAfterUsesABackoffWithinTheLimit() {
		Fixture f = fixture("retry-backoff", "100ms");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		f.client().fetchEvents(SEARCH, 0, stats);

		assertThat(sleeps).containsExactly(Duration.ofMillis(200));
	}

	// ---------- 5xx ----------

	@Test
	void serverErrorsAreRetriedWithGrowingBackoffUntilItWorks() {
		Fixture f = fixture("retry-backoff", "100ms");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withServerError());
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		EventsPage result = f.client().fetchEvents(SEARCH, 0, stats);

		assertThat(result.events()).hasSize(1);
		assertThat(stats.calls).isEqualTo(3);
		assertThat(stats.retries).isEqualTo(2);
		assertThat(sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
		f.server().verify();
	}

	@Test
	void persistentServerErrorGivesUpAfterTheConfiguredRetries() {
		Fixture f = fixture();
		for (int i = 0; i < 3; i++) {
			f.server().expect(requestTo(startsWith(BASE))).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		}

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.SERVER_ERROR);
					assertThat(e.httpStatus()).isEqualTo(503);
					assertThat(e.attempts()).isEqualTo(3);
				});
		assertThat(stats.calls).isEqualTo(3);
		assertThat(stats.retries).isEqualTo(2);
		f.server().verify();
	}

	@Test
	void zeroRetriesMeansASingleAttempt() {
		Fixture f = fixture("max-retries", "0");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withServerError());

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.attempts()).isEqualTo(1));
		assertThat(stats.calls).isEqualTo(1);
	}

	// ---------- timeout e rete ----------

	@Test
	void timeoutsAreRetriedAndThenReported() {
		Fixture f = fixture();
		for (int i = 0; i < 3; i++) {
			f.server().expect(requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("Read timed out")));
		}

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
					assertThat(e.httpStatus()).isNull();
					assertThat(e.attempts()).isEqualTo(3);
				});
		assertThat(stats.calls).isEqualTo(3);
		f.server().verify();
	}

	@Test
	void aTimeoutFollowedBySuccessRecovers() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withException(new SocketTimeoutException("connect timed out")));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		assertThat(f.client().fetchEvents(SEARCH, 0, stats).events()).hasSize(1);
		assertThat(stats.retries).isEqualTo(1);
	}

	@Test
	void networkErrorsAreRetriedAndClassifiedAsNetwork() {
		Fixture f = fixture("max-retries", "1");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withException(new ConnectException("Connection refused")));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withException(new IOException("Connection reset")));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.NETWORK);
					assertThat(e.attempts()).isEqualTo(2);
				});
	}

	@Test
	void aRealReadTimeoutIsEnforcedAgainstASlowServer() throws IOException {
		localServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		localServer.createContext("/", exchange -> {
			try {
				Thread.sleep(2000);
				exchange.sendResponseHeaders(200, -1);
			}
			catch (InterruptedException | IOException ignored) {
				// il client ha gia' chiuso
			}
			finally {
				exchange.close();
			}
		});
		localServer.start();
		TicketmasterProperties properties = TicketmasterTestData.properties("base-url",
				"http://127.0.0.1:" + localServer.getAddress().getPort() + "/discovery/v2", "read-timeout", "300ms", "max-retries", "0");
		TicketmasterClient client = new TicketmasterClient(properties);

		long start = System.nanoTime();
		assertThatThrownBy(() -> client.fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.TIMEOUT);
					assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("127.0.0.1");
					assertThat(e.getCause()).isNull();
				});
		// il server risponderebbe dopo 2 s: il timeout di lettura (300 ms) deve scattare molto prima
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
	}

	@Test
	void aRefusedConnectionIsANetworkErrorWithoutLeakingTheUrl() throws IOException {
		int port;
		try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();
		} // chiuso: su questa porta nessuno ascolta piu'
		TicketmasterClient client = new TicketmasterClient(TicketmasterTestData.properties("base-url",
				"http://127.0.0.1:" + port + "/discovery/v2", "max-retries", "0"));

		assertThatThrownBy(() -> client.fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.NETWORK);
					assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("127.0.0.1");
					assertThat(e.getCause()).isNull();
				});
	}

	@Test
	void aRealRedirectIsNeverFollowedSoTheKeyCannotBeForwarded() throws IOException {
		AtomicInteger otherHits = new AtomicInteger();
		localServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		localServer.createContext("/other", exchange -> {
			otherHits.incrementAndGet();
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		localServer.createContext("/discovery", exchange -> {
			exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + localServer.getAddress().getPort() + "/other");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		localServer.start();
		TicketmasterClient client = new TicketmasterClient(TicketmasterTestData.properties("base-url",
				"http://127.0.0.1:" + localServer.getAddress().getPort() + "/discovery/v2", "max-retries", "0"));

		assertThatThrownBy(() -> client.fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CLIENT_ERROR));
		assertThat(otherHits).hasValue(0);
	}

	// ---------- JSON malformato ----------

	@Test
	void malformedJsonIsReportedAndNotRetried() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess("{\"_embedded\": {\"events\": [", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> {
					assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE);
					assertThat(e.httpStatus()).isEqualTo(200);
					assertThat(e.attempts()).isEqualTo(1);
				});
		assertThat(stats.calls).isEqualTo(1);
		assertThat(stats.pagesFetched).isZero();
		f.server().verify();
	}

	@Test
	void anHtmlErrorPageWithStatus200IsMalformed() {
		Fixture f = fixture();
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess("<html><body>Maintenance</body></html>", MediaType.TEXT_HTML));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE));
	}

	@Test
	void aResponseLargerThanTheLimitIsRejected() {
		Fixture f = fixture();
		byte[] huge = new byte[8 * 1024 * 1024 + 1];
		java.util.Arrays.fill(huge, (byte) ' ');
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(huge, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.MALFORMED_RESPONSE));
	}

	// ---------- limiti: richieste per esecuzione e pausa minima ----------

	@Test
	void theCallBudgetIsAHardCeilingEvenWithRetriesAvailable() {
		Fixture f = fixture("max-calls-per-run", "2", "max-retries", "5");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withServerError());
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withServerError());

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CALL_BUDGET_EXHAUSTED));
		assertThat(stats.calls).isEqualTo(2);
		f.server().verify();
	}

	@Test
	void theBudgetIsSharedAcrossPagesOfTheSameRun() {
		Fixture f = fixture("max-calls-per-run", "2");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		f.client().fetchEvents(SEARCH, 0, stats);
		f.client().fetchEvents(SEARCH, 1, stats);
		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 2, stats))
				.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.CALL_BUDGET_EXHAUSTED));
		assertThat(stats.calls).isEqualTo(2);
	}

	@Test
	void consecutiveCallsAreSpacedByTheMinimumInterval() {
		Fixture f = fixture("min-call-interval", "500ms");
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));
		f.server().expect(requestTo(startsWith(BASE))).andRespond(withSuccess(okPage(), MediaType.APPLICATION_JSON));

		f.client().fetchEvents(SEARCH, 0, stats);
		assertThat(sleeps).isEmpty(); // la prima richiesta parte subito
		nanos.addAndGet(Duration.ofMillis(100).toNanos());
		f.client().fetchEvents(SEARCH, 1, stats);
		assertThat(sleeps).containsExactly(Duration.ofMillis(400));
		nanos.addAndGet(Duration.ofSeconds(2).toNanos()); // abbastanza tempo e' passato: nessuna pausa
		f.client().fetchEvents(SEARCH, 2, stats);
		assertThat(sleeps).containsExactly(Duration.ofMillis(400));
	}

	@Test
	void interruptionStopsTheRunAndKeepsTheInterruptFlag() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		TicketmasterClient client = new TicketmasterClient(TicketmasterTestData.properties("retry-backoff", "100ms"), builder, nanos::get, d -> {
			throw new InterruptedException();
		});
		server.expect(requestTo(startsWith(BASE))).andRespond(withServerError());

		try {
			assertThatThrownBy(() -> client.fetchEvents(SEARCH, 0, stats))
					.isInstanceOfSatisfying(TicketmasterException.class, e -> assertThat(e.kind()).isEqualTo(Kind.INTERRUPTED));
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		}
		finally {
			Thread.interrupted(); // pulisce il flag per i test successivi
		}
	}

	// ---------- redazione delle credenziali ----------

	@Test
	void theKeyNeverAppearsInErrorsLogsOrToString() {
		Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		Level previous = root.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		root.addAppender(appender);
		root.setLevel(Level.TRACE); // anche i log piu' dettagliati di Spring
		try {
			List<Throwable> errors = new ArrayList<>();
			for (var response : List.of(
					withStatus(HttpStatus.UNAUTHORIZED), withStatus(HttpStatus.FORBIDDEN), withStatus(HttpStatus.TOO_MANY_REQUESTS),
					withServerError(), withException(new SocketTimeoutException("Read timed out")),
					withException(new IOException("I/O error on GET request for \"https://app.ticketmaster.com/?apikey=" + KEY + "\"")),
					withSuccess("not json", MediaType.APPLICATION_JSON))) {
				Fixture f = fixture("max-retries", "0");
				f.server().expect(requestTo(startsWith(BASE))).andRespond(response);
				try {
					f.client().fetchEvents(SEARCH, 0, new CallStats());
				}
				catch (TicketmasterException e) {
					errors.add(e);
				}
			}

			assertThat(errors).hasSize(7);
			for (Throwable error : errors) {
				assertThat(error.getMessage()).doesNotContain(KEY).doesNotContain("apikey");
				assertThat(error.toString()).doesNotContain(KEY);
				assertThat(error.getCause()).isNull();
				assertThat(java.util.Arrays.toString(error.getStackTrace())).doesNotContain(KEY);
			}
			assertThat(appender.list).isNotEmpty(); // il controllo e' significativo: qualcosa e' stato registrato
			assertThat(appender.list).noneMatch(event -> event.getFormattedMessage().contains(KEY));
			assertThat(appender.list).noneMatch(event -> String.valueOf(event.getThrowableProxy()).contains(KEY));
			assertThat(appender.list).noneMatch(event -> java.util.Arrays.toString(event.getArgumentArray()).contains(KEY));
		}
		finally {
			root.detachAppender(appender);
			root.setLevel(previous);
		}
	}

	@Test
	void theKeyTravelsOnTheWireAddedByTheInterceptorAtTheLastMoment() {
		// L'interceptor aggiunge "apikey" all'ultimo istante: il server finto la vede. Che l'URL con cui Spring costruisce messaggi di
		// errore e log ("I/O error on GET request for ...") ne sia privo lo prova il test precedente (nessuna traccia della chiave nei log).
		Fixture f = fixture("max-retries", "0");
		List<String> seenByTheWire = new ArrayList<>();
		f.server().expect(requestTo(startsWith(BASE))).andExpect(request -> seenByTheWire.add(request.getURI().toString()))
				.andRespond(withException(new IOException("boom")));

		assertThatThrownBy(() -> f.client().fetchEvents(SEARCH, 0, stats)).isInstanceOf(TicketmasterException.class);

		assertThat(seenByTheWire).singleElement().asString().contains("apikey=" + KEY);
	}

}
