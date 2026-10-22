package com.linkr.backend.ticketmaster;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;
import com.linkr.backend.ticketmaster.TicketmasterProperties.LocationFilter;

/**
 * Client HTTP della Discovery API v2 (Spring RestClient), in sola lettura.
 *
 * Regole che questa classe garantisce (tutte provate in TicketmasterClientTest):
 *  - nessuna richiesta all'avvio o alla costruzione: parte solo quando il dry-run chiama fetchEvents();
 *  - timeout di connessione e di lettura espliciti, nessun redirect seguito, risposta limitata a 8 MB;
 *  - la API Key viene aggiunta all'URL SOLO da un interceptor, all'ultimo istante: gli URL che Spring compone per messaggi di
 *    errore e log (anche "I/O error on GET request for ...") non la contengono mai. In piu' ogni errore viene riconvertito in
 *    una TicketmasterException con un messaggio fisso, senza URL, corpo della risposta ne' causa originale;
 *  - 401/403/4xx: nessun retry; 429: al massimo un retry e solo se la fonte chiede un'attesa breve; 5xx, timeout e errori di rete:
 *    al massimo maxRetries retry con attesa crescente; JSON malformato: nessun retry;
 *  - pausa minima tra due richieste (minCallInterval) e tetto assoluto di richieste per esecuzione (maxCallsPerRun).
 */
@Component
class TicketmasterClient implements TicketmasterApi {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterClient.class);

	private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
	private static final Duration MAX_BACKOFF = Duration.ofSeconds(10);
	private static final int GEOHASH_PRECISION = 7;
	private static final DateTimeFormatter API_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

	/** Attesa (iniettabile per i test, che non devono dormire davvero). */
	@FunctionalInterface
	interface Sleeper {

		void sleep(Duration duration) throws InterruptedException;

	}

	private record RawResponse(int status, String retryAfter, String quotaLimit, String quotaAvailable, String body, boolean tooLarge) {
	}

	private final TicketmasterProperties properties;
	private final RestClient.Builder builder;
	private final LongSupplier nanoClock;
	private final Sleeper sleeper;
	private long lastCallNanos;
	private boolean hasLastCall;
	/** Costruito alla prima richiesta, non all'avvio: con l'integrazione spenta un base-url sbagliato non deve impedire il boot dell'app. */
	private volatile RestClient restClient;

	@Autowired
	TicketmasterClient(TicketmasterProperties properties) {
		this(properties, RestClient.builder().requestFactory(requestFactory(properties)), System::nanoTime,
				duration -> Thread.sleep(duration.toMillis()));
	}

	/** Per i test: il builder puo' essere collegato a un server finto (MockRestServiceServer) prima di arrivare qui. */
	TicketmasterClient(TicketmasterProperties properties, RestClient.Builder builder, LongSupplier nanoClock, Sleeper sleeper) {
		this.properties = properties;
		this.builder = builder;
		this.nanoClock = nanoClock;
		this.sleeper = sleeper;
	}

	private RestClient restClient() {
		RestClient client = restClient;
		if (client == null) {
			synchronized (this) {
				client = restClient;
				if (client == null) {
					client = builder
							.baseUrl(properties.baseUrl() == null ? "" : properties.baseUrl().strip())
							.requestInterceptor(apiKeyInterceptor(properties))
							.build();
					restClient = client;
				}
			}
		}
		return client;
	}

	private static SimpleClientHttpRequestFactory requestFactory(TicketmasterProperties properties) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				// Niente redirect: la richiesta con la chiave non deve mai essere rigirata a un altro indirizzo.
				connection.setInstanceFollowRedirects(false);
			}
		};
		factory.setConnectTimeout(toMillis(properties.connectTimeout(), 3000));
		factory.setReadTimeout(toMillis(properties.readTimeout(), 10000));
		return factory;
	}

	private static int toMillis(Duration duration, int fallback) {
		return duration == null ? fallback : (int) Math.min(duration.toMillis(), Integer.MAX_VALUE);
	}

	/** Aggiunge "apikey" all'URL nel momento dell'invio. Prima di questo punto la chiave non compare in nessun URL di Spring. */
	private static ClientHttpRequestInterceptor apiKeyInterceptor(TicketmasterProperties properties) {
		return (request, body, execution) -> {
			URI original = request.getURI();
			String separator = original.getRawQuery() == null ? "?" : "&";
			URI withKey;
			try {
				withKey = URI.create(original + separator + "apikey=" + URLEncoder.encode(properties.apiKey(), StandardCharsets.UTF_8));
			}
			catch (IllegalArgumentException e) {
				// Il messaggio di URI.create() riporta l'URI intero, chiave compresa: non si propaga.
				throw new IOException("The request URI could not be built");
			}
			return execution.execute(new HttpRequestWrapper(request) {
				@Override
				public URI getURI() {
					return withKey;
				}
			}, body);
		};
	}

	@Override
	public EventsPage fetchEvents(EventSearch search, int page, CallStats stats) {
		if (!properties.hasApiKey()) {
			throw new TicketmasterException(Kind.UNAUTHORIZED, null, null);
		}
		int attempt = 0;
		while (true) {
			if (stats.calls >= properties.maxCallsPerRun()) {
				throw new TicketmasterException(Kind.CALL_BUDGET_EXHAUSTED, null, null).withAttempts(attempt);
			}
			attempt++;
			awaitTurn();
			stats.calls++;
			try {
				return requestOnce(search, page, stats);
			}
			catch (TicketmasterException e) {
				Duration wait = waitBeforeRetry(e, attempt);
				if (wait == null) {
					log.warn("Ticketmaster request failed: kind={} httpStatus={} attempts={}", e.kind(), e.httpStatus(), attempt);
					throw e.withAttempts(attempt);
				}
				stats.retries++;
				log.info("Ticketmaster request will be retried: kind={} httpStatus={} attempt={} wait={}ms", e.kind(), e.httpStatus(),
						attempt, wait.toMillis());
				sleep(wait);
			}
		}
	}

	/** Quanto attendere prima di ripetere la richiesta, oppure null se non si deve ripetere. */
	private Duration waitBeforeRetry(TicketmasterException e, int attempt) {
		int allowedRetries;
		switch (e.kind()) {
			case SERVER_ERROR, TIMEOUT, NETWORK -> allowedRetries = properties.maxRetries();
			case RATE_LIMITED -> allowedRetries = Math.min(1, properties.maxRetries()); // contro un 429 non si insiste
			default -> {
				return null;
			}
		}
		if (attempt > allowedRetries) {
			return null;
		}
		Duration backoff = backoff(attempt);
		if (e.kind() == Kind.RATE_LIMITED) {
			Duration wait = e.retryAfter() != null ? e.retryAfter() : backoff.multipliedBy(2);
			return wait.compareTo(properties.maxRetryAfter()) > 0 ? null : wait;
		}
		return backoff;
	}

	private Duration backoff(int attempt) {
		Duration base = properties.retryBackoff();
		Duration wait = base.multipliedBy(1L << Math.min(attempt - 1, 10));
		return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
	}

	/** Pausa minima tra due richieste consecutive (anche tra un retry e il successivo). */
	private synchronized void awaitTurn() {
		long interval = properties.minCallInterval().toNanos();
		if (interval > 0 && hasLastCall) {
			long elapsed = nanoClock.getAsLong() - lastCallNanos;
			if (elapsed < interval) {
				sleep(Duration.ofNanos(interval - elapsed));
			}
		}
		lastCallNanos = nanoClock.getAsLong();
		hasLastCall = true;
	}

	private void sleep(Duration duration) {
		if (duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			sleeper.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new TicketmasterException(Kind.INTERRUPTED, null, null);
		}
	}

	private EventsPage requestOnce(EventSearch search, int page, CallStats stats) {
		RawResponse raw;
		try {
			raw = restClient().get()
					.uri(uriBuilder -> {
						uriBuilder.path("/events.json")
								.queryParam("startDateTime", API_DATE_TIME.format(search.from()))
								.queryParam("endDateTime", API_DATE_TIME.format(search.to()))
								.queryParam("size", search.pageSize())
								.queryParam("page", page)
								.queryParam("sort", "date,asc")
								.queryParam("locale", properties.locale())
								.queryParam("countryCode", "IT")
								.queryParam("radius", properties.area().coveringRadiusKm())
								.queryParam("unit", "km");
						if (properties.locationFilter() == LocationFilter.LATLONG) {
							uriBuilder.queryParam("latlong", properties.area().centerLatitude() + "," + properties.area().centerLongitude());
						}
						else {
							uriBuilder.queryParam("geoPoint", Geohash.encode(properties.area().centerLatitude(),
									properties.area().centerLongitude(), GEOHASH_PRECISION));
						}
						return uriBuilder.build();
					})
					.accept(MediaType.APPLICATION_JSON)
					.exchange((request, response) -> {
						int status = response.getStatusCode().value();
						HttpHeaders headers = response.getHeaders();
						String body = null;
						boolean tooLarge = false;
						if (status >= 200 && status < 300) {
							byte[] bytes = readBounded(response.getBody());
							if (bytes == null) {
								tooLarge = true;
							}
							else {
								body = new String(bytes, StandardCharsets.UTF_8);
							}
						}
						return new RawResponse(status, headers.getFirst("Retry-After"), headers.getFirst("Rate-Limit"),
								headers.getFirst("Rate-Limit-Available"), body, tooLarge);
					});
		}
		catch (RestClientException e) {
			// Il messaggio di queste eccezioni contiene l'URL della richiesta: si usa solo il tipo della causa.
			throw new TicketmasterException(isTimeout(e) ? Kind.TIMEOUT : Kind.NETWORK, null, null);
		}

		recordQuota(stats, raw);
		int status = raw.status();
		if (status >= 200 && status < 300) {
			if (raw.tooLarge()) {
				throw new TicketmasterException(Kind.MALFORMED_RESPONSE, status, null);
			}
			EventsPage result = TicketmasterResponseParser.parse(raw.body());
			stats.pagesFetched++;
			return result;
		}
		switch (status) {
			case 401 -> throw new TicketmasterException(Kind.UNAUTHORIZED, status, null);
			case 403 -> throw new TicketmasterException(Kind.FORBIDDEN, status, null);
			case 429 -> {
				stats.rateLimitedResponses++;
				Duration retryAfter = parseRetryAfter(raw.retryAfter());
				if (retryAfter != null && (stats.longestRetryAfter == null || retryAfter.compareTo(stats.longestRetryAfter) > 0)) {
					stats.longestRetryAfter = retryAfter;
				}
				throw new TicketmasterException(Kind.RATE_LIMITED, status, retryAfter);
			}
			default -> {
				if (status >= 500) {
					throw new TicketmasterException(Kind.SERVER_ERROR, status, null);
				}
				throw new TicketmasterException(Kind.CLIENT_ERROR, status, null);
			}
		}
	}

	/** Legge al massimo MAX_BODY_BYTES; null se la risposta e' piu' grande. */
	private static byte[] readBounded(InputStream in) throws IOException {
		byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
		return bytes.length > MAX_BODY_BYTES ? null : bytes;
	}

	private static boolean isTimeout(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
				return true;
			}
		}
		return false;
	}

	private static Duration parseRetryAfter(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		try {
			long seconds = Long.parseLong(header.strip());
			return Duration.ofSeconds(Math.max(0, Math.min(seconds, 86_400)));
		}
		catch (NumberFormatException e) {
			return null; // formato data HTTP o testo libero: non lo interpretiamo
		}
	}

	private static void recordQuota(CallStats stats, RawResponse raw) {
		Integer limit = parseInt(raw.quotaLimit());
		Integer available = parseInt(raw.quotaAvailable());
		if (limit != null) {
			stats.quotaLimit = limit;
		}
		if (available != null) {
			stats.quotaAvailable = available;
		}
	}

	private static Integer parseInt(String value) {
		try {
			return value == null ? null : Integer.valueOf(value.strip());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
