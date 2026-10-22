package com.linkr.backend.ticketmaster;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurazione dell'integrazione Ticketmaster Discovery API (prefisso linkr.ticketmaster; vedi application.properties e docs/TICKETMASTER.md).
 *
 * L'integrazione e' SPENTA di base (enabled=false): senza LINKR_TICKETMASTER_ENABLED=true nessuna richiesta parte mai.
 * La API Key e' facoltativa all'avvio: se manca, il dry-run risponde "NOT_CONFIGURED" senza chiamare nessuno.
 *
 * Il record NON espone la chiave: toString() la oscura, cosi' un log o un'eccezione che stampa l'oggetto non la rivela.
 * I valori sono controllati solo quando l'integrazione e' attiva, cosi' un parametro sbagliato non impedisce l'avvio dell'app spenta.
 */
@ConfigurationProperties(prefix = "linkr.ticketmaster")
public record TicketmasterProperties(
		@DefaultValue("false") boolean enabled,
		String apiKey,
		@DefaultValue("https://app.ticketmaster.com/discovery/v2") String baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration readTimeout,
		/** Finestra temporale analizzata, a partire da adesso. */
		@DefaultValue("30") int windowDays,
		/** Massimo di eventi candidati validi che il dry-run raccoglie. */
		@DefaultValue("200") int maxCandidates,
		/** Interruttore dell'IMPORTAZIONE persistente: separato da `enabled`, che abilita il solo dry-run (sola lettura). Spento di base. */
		@DefaultValue("false") boolean importEnabled,
		/** Candidati importati se la richiesta non specifica maxCandidates (conservativo). */
		@DefaultValue("20") int importDefaultCandidates,
		/** Massimo di candidati importabili con una sola richiesta. */
		@DefaultValue("50") int importMaxCandidates,
		@DefaultValue("50") int pageSize,
		/** Limite assoluto di pagine richieste per esecuzione, qualunque cosa risponda la fonte. */
		@DefaultValue("8") int maxPages,
		/** Limite assoluto di richieste HTTP (retry compresi) per esecuzione. */
		@DefaultValue("15") int maxCallsPerRun,
		@DefaultValue("2") int maxRetries,
		@DefaultValue("500ms") Duration retryBackoff,
		/** Se la fonte chiede di attendere piu' di cosi' (429 + Retry-After), non si attende: ci si ferma. */
		@DefaultValue("5s") Duration maxRetryAfter,
		/** Pausa minima tra due richieste: la fonte consente ~5 richieste al secondo, qui ne facciamo al massimo 2. */
		@DefaultValue("500ms") Duration minCallInterval,
		@DefaultValue("it-it,it,*") String locale,
		@DefaultValue("GEOPOINT") LocationFilter locationFilter,
		@DefaultValue Area area) {

	/** Come restringere la ricerca all'area: geoPoint (geohash, consigliato dalla fonte) o latlong (deprecato ma ancora accettato). */
	public enum LocationFilter {
		GEOPOINT, LATLONG
	}

	/** Riquadro di Milano: stessi valori di MILAN_BOUNDS nel frontend (frontend/src/lib/map.ts). */
	public record Area(
			@DefaultValue("45.35") double minLatitude,
			@DefaultValue("45.60") double maxLatitude,
			@DefaultValue("8.95") double minLongitude,
			@DefaultValue("9.45") double maxLongitude) {

		public Area {
			if (!(minLatitude < maxLatitude) || !(minLongitude < maxLongitude) || minLatitude < -90 || maxLatitude > 90
					|| minLongitude < -180 || maxLongitude > 180) {
				throw new IllegalArgumentException("linkr.ticketmaster.area is not a valid bounding box");
			}
		}

		public boolean contains(double latitude, double longitude) {
			return latitude >= minLatitude && latitude <= maxLatitude && longitude >= minLongitude && longitude <= maxLongitude;
		}

		public double centerLatitude() {
			return (minLatitude + maxLatitude) / 2;
		}

		public double centerLongitude() {
			return (minLongitude + maxLongitude) / 2;
		}

		/** Raggio (km, arrotondato per eccesso) del cerchio che contiene tutto il riquadro: la fonte non sa filtrare per riquadro. */
		public int coveringRadiusKm() {
			double farthest = Math.max(
					Math.max(distanceKm(centerLatitude(), centerLongitude(), minLatitude, minLongitude),
							distanceKm(centerLatitude(), centerLongitude(), minLatitude, maxLongitude)),
					Math.max(distanceKm(centerLatitude(), centerLongitude(), maxLatitude, minLongitude),
							distanceKm(centerLatitude(), centerLongitude(), maxLatitude, maxLongitude)));
			return (int) Math.ceil(farthest);
		}

	}

	private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

	public TicketmasterProperties {
		apiKey = apiKey == null ? null : apiKey.strip();
		if (enabled) {
			require(windowDays >= 1 && windowDays <= 90, "window-days must be between 1 and 90");
			require(maxCandidates >= 1 && maxCandidates <= 500, "max-candidates must be between 1 and 500");
			require(importMaxCandidates >= 1 && importMaxCandidates <= 100, "import-max-candidates must be between 1 and 100");
			require(importDefaultCandidates >= 1 && importDefaultCandidates <= importMaxCandidates,
					"import-default-candidates must be between 1 and import-max-candidates");
			require(pageSize >= 1 && pageSize <= 200, "page-size must be between 1 and 200");
			require(maxPages >= 1 && maxPages <= 20, "max-pages must be between 1 and 20");
			require(maxCallsPerRun >= 1 && maxCallsPerRun <= 60, "max-calls-per-run must be between 1 and 60");
			require(maxRetries >= 0 && maxRetries <= 5, "max-retries must be between 0 and 5");
			require(connectTimeout != null && connectTimeout.isPositive(), "connect-timeout must be positive");
			require(readTimeout != null && readTimeout.isPositive(), "read-timeout must be positive");
			require(retryBackoff != null && !retryBackoff.isNegative(), "retry-backoff must not be negative");
			require(maxRetryAfter != null && !maxRetryAfter.isNegative(), "max-retry-after must not be negative");
			require(minCallInterval != null && !minCallInterval.isNegative(), "min-call-interval must not be negative");
			require(locale != null && !locale.isBlank(), "locale must not be blank");
			require(isSafeBaseUrl(baseUrl), "base-url must be an https URL (plain http is accepted only for localhost)");
		}
	}

	/** true se c'e' una API Key utilizzabile. */
	public boolean hasApiKey() {
		return apiKey != null && !apiKey.isEmpty();
	}

	/** La chiave viaggia nell'URL: mai in chiaro su una rete, quindi https obbligatorio (eccezione: localhost, per i test). */
	private static boolean isSafeBaseUrl(String baseUrl) {
		if (baseUrl == null || baseUrl.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(baseUrl.strip());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawUserInfo() != null) {
				return false;
			}
			return scheme.equals("https") || (scheme.equals("http") && LOOPBACK_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)));
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalArgumentException("Invalid linkr.ticketmaster configuration: " + message);
		}
	}

	static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
	}

	@Override
	public String toString() {
		return "TicketmasterProperties[enabled=" + enabled + ", apiKey=" + (hasApiKey() ? "***" : "<not set>") + ", baseUrl="
				+ baseUrl + ", windowDays=" + windowDays + ", maxCandidates=" + maxCandidates + ", importEnabled=" + importEnabled + "]";
	}

}
