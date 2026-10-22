package com.linkr.backend.ticketmaster;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import tools.jackson.databind.json.JsonMapper;

import com.linkr.backend.ticketmaster.TicketmasterDto.Event;

/** Dati di prova: eventi nel formato della Discovery API e configurazioni gia' pronte. Nessun dato reale, nessuna chiave vera. */
final class TicketmasterTestData {

	/** Chiave finta, riconoscibile: se compare in un log, in un'eccezione o in una risposta, il test fallisce. */
	static final String KEY = "TESTKEY-d34db33f-DO-NOT-LEAK-0123456789";

	/** "Adesso" nei test; la finestra di 30 giorni finisce il 2026-11-21T10:00:00Z. */
	static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");
	static final Instant WINDOW_END = NOW.plusSeconds(30L * 24 * 3600);

	static final String MUSIC_ID = "KZFzniwnSyZfZ7v7nJ";
	static final String SPORTS_ID = "KZFzniwnSyZfZ7v7nE";
	static final String ARTS_ID = "KZFzniwnSyZfZ7v7na";
	static final String FILM_ID = "KZFzniwnSyZfZ7v7nn";
	static final String MISC_ID = "KZFzniwnSyZfZ7v7n1";

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private TicketmasterTestData() {
	}

	/** Configurazione: attiva, con la chiave finta, senza pause (i test non devono dormire), piu' le modifiche richieste. */
	static TicketmasterProperties properties(String... keyValues) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("linkr.ticketmaster.enabled", "true");
		values.put("linkr.ticketmaster.api-key", KEY);
		values.put("linkr.ticketmaster.min-call-interval", "0");
		values.put("linkr.ticketmaster.retry-backoff", "0");
		for (int i = 0; i < keyValues.length; i += 2) {
			values.put("linkr.ticketmaster." + keyValues[i], keyValues[i + 1]);
		}
		return new Binder(new MapConfigurationPropertySource(values)).bind("linkr.ticketmaster", TicketmasterProperties.class).get();
	}

	/** Un evento valido (concerto a Milano il 5 novembre, 20:00 ora locale = 19:00 UTC) che si puo' modificare. */
	static EventJson event(String id) {
		return new EventJson(id);
	}

	static final class EventJson {

		private final Map<String, Object> root = new LinkedHashMap<>();

		EventJson(String id) {
			root.put("name", "Concerto " + id);
			root.put("type", "event");
			root.put("id", id);
			root.put("test", false);
			root.put("url", "https://www.ticketmaster.it/event/" + id + "?utm=secret-looking");
			root.put("locale", "it-it");
			root.put("images", new ArrayList<>(List.of(map("ratio", "16_9", "url", "https://s1.ticketm.net/dam/a/" + id + ".jpg", "width", 1024, "height", 576))));
			root.put("info", "Una bella serata di musica dal vivo.");
			root.put("dates", map(
					"start", map("localDate", "2026-11-05", "localTime", "20:00:00", "dateTime", "2026-11-05T19:00:00Z",
							"dateTBD", false, "dateTBA", false, "timeTBA", false, "noSpecificTime", false),
					"timezone", "Europe/Rome",
					"status", map("code", "onsale")));
			root.put("classifications", new ArrayList<>(List.of(map("primary", true,
					"segment", map("id", MUSIC_ID, "name", "Music"), "genre", map("id", "g1", "name", "Rock")))));
			root.put("_embedded", map("venues", new ArrayList<>(List.of(map(
					"name", "Fabrique", "id", "v1", "timezone", "Europe/Rome",
					"city", map("name", "Milano"),
					"address", map("line1", "Via Fantoli 9"),
					"location", map("longitude", "9.2315", "latitude", "45.4350"))))));
		}

		EventJson title(String title) {
			root.put("name", title);
			return this;
		}

		EventJson removeKey(String key) {
			root.remove(key);
			return this;
		}

		EventJson put(String key, Object value) {
			root.put(key, value);
			return this;
		}

		EventJson status(String code) {
			dates().put("status", code == null ? null : map("code", code));
			return this;
		}

		EventJson timezone(String timezone) {
			dates().put("timezone", timezone);
			return this;
		}

		EventJson removeVenueTimezone() {
			venue().remove("timezone");
			return this;
		}

		EventJson start(String key, Object value) {
			start().put(key, value);
			return this;
		}

		/** Inizio a un istante preciso (UTC e ora locale di Milano coerenti): per i test che usano l'orologio reale. */
		EventJson startsAt(Instant instant) {
			Instant whole = instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
			java.time.ZonedDateTime rome = whole.atZone(java.time.ZoneId.of("Europe/Rome"));
			start().put("dateTime", whole.toString());
			start().put("localDate", rome.toLocalDate().toString());
			start().put("localTime", rome.toLocalTime().toString());
			return this;
		}

		EventJson removeStart(String key) {
			start().remove(key);
			return this;
		}

		EventJson noStart() {
			dates().remove("start");
			return this;
		}

		EventJson segment(String id, String name) {
			classification().put("segment", id == null && name == null ? null : map("id", id, "name", name));
			return this;
		}

		EventJson classificationType(String type, String subType) {
			classification().put("type", type == null ? null : map("name", type));
			classification().put("subType", subType == null ? null : map("name", subType));
			return this;
		}

		EventJson city(String city) {
			venue().put("city", city == null ? null : map("name", city));
			return this;
		}

		EventJson noAddress() {
			venue().remove("address");
			return this;
		}

		EventJson noClassifications() {
			root.remove("classifications");
			return this;
		}

		EventJson coordinates(Object latitude, Object longitude) {
			venue().put("location", map("latitude", latitude, "longitude", longitude));
			return this;
		}

		EventJson noLocation() {
			venue().remove("location");
			return this;
		}

		EventJson noVenue() {
			root.remove("_embedded");
			return this;
		}

		EventJson venueName(String name) {
			venue().put("name", name);
			return this;
		}

		EventJson noImages() {
			root.remove("images");
			return this;
		}

		String json() {
			return MAPPER.writeValueAsString(root);
		}

		Event parsed() {
			return TicketmasterResponseParser.parse(page(0, 1, 1, json())).events().get(0);
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> dates() {
			return (Map<String, Object>) root.get("dates");
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> start() {
			return (Map<String, Object>) dates().get("start");
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> classification() {
			return ((List<Map<String, Object>>) root.get("classifications")).get(0);
		}

		@SuppressWarnings("unchecked")
		private Map<String, Object> venue() {
			Map<String, Object> embedded = (Map<String, Object>) root.get("_embedded");
			return ((List<Map<String, Object>>) embedded.get("venues")).get(0);
		}

	}

	/** Pagina di risposta con gli eventi dati (gia' in JSON). */
	static String page(int number, int totalPages, long totalElements, String... eventJsons) {
		String events = eventJsons.length == 0 ? "" : "\"_embedded\":{\"events\":[" + String.join(",", eventJsons) + "]},";
		return "{" + events + "\"_links\":{\"self\":{\"href\":\"/discovery/v2/events.json?page=" + number + "\"}},"
				+ "\"page\":{\"size\":50,\"totalElements\":" + totalElements + ",\"totalPages\":" + totalPages + ",\"number\":" + number + "}}";
	}

	/** Pagina gia' letta, come la restituisce il client. */
	static TicketmasterDto.EventsPage parsedPage(int number, int totalPages, long totalElements, EventJson... events) {
		String[] jsons = new String[events.length];
		for (int i = 0; i < events.length; i++) {
			jsons[i] = events[i].json();
		}
		return TicketmasterResponseParser.parse(page(number, totalPages, totalElements, jsons));
	}

	static Map<String, Object> map(Object... keyValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put((String) keyValues[i], keyValues[i + 1]);
		}
		return map;
	}

}
