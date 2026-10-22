package com.linkr.backend.ticketmaster;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.ticketmaster.TicketmasterDto.Classification;
import com.linkr.backend.ticketmaster.TicketmasterDto.Dates;
import com.linkr.backend.ticketmaster.TicketmasterDto.Event;
import com.linkr.backend.ticketmaster.TicketmasterDto.Named;
import com.linkr.backend.ticketmaster.TicketmasterDto.Start;
import com.linkr.backend.ticketmaster.TicketmasterDto.Venue;
import com.linkr.backend.ticketmaster.TicketmasterProperties.Area;

/**
 * Trasforma un evento di Ticketmaster in un {@link NormalizedEvent} oppure dice perche' va escluso. Funzione pura (nessun I/O,
 * nessun database, nessuna eccezione per dati mancanti): provata in TicketmasterEventNormalizerTest.
 *
 * Regole:
 *  - Stato: onsale/offsale = candidato (l'evento si tiene; LINKR non vende biglietti); canceled, postponed, rescheduled e stati
 *    sconosciuti = esclusi (mai pubblicati o riattivati in automatico).
 *  - Data: affidabile solo se completa (data e ora) e non "da definire". Si usa l'istante UTC della fonte; se manca, data+ora locali
 *    con il fuso dell'evento (poi quello della sede, poi Europe/Rome). Se UTC e locale sono entrambi presenti devono coincidere.
 *    Deve cadere tra adesso e la fine della finestra.
 *  - Coordinate: dalla sede (stringhe decimali), valide e dentro il riquadro di Milano.
 *  - Prodotti: abbonamenti, pacchetti, parcheggi, voucher e simili non sono una singola uscita e si escludono (NOT_SINGLE_EVENT),
 *    riconosciuti dalle parole del titolo e dal tipo/sottotipo della classificazione. E' un'euristica: nel dubbio non si importa un
 *    prodotto; i falsi positivi si vedono nel dry-run (excludedSamples) e si correggono nelle liste qui sotto.
 *  - Link: l'annuncio e' conservato solo se e' un URL https valido, senza credenziali, di al massimo 1000 caratteri.
 *  - Categoria: dal segmento primario (per id, poi per nome in inglese o italiano). Music -> MUSIC, Sports -> SPORT,
 *    Arts & Theatre e Film -> CULTURE. Tutto il resto (es. Miscellaneous) non e' riconosciuto: escluso e contato.
 */
final class TicketmasterEventNormalizer {

	static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Rome");

	private static final Duration DATE_TOLERANCE = Duration.ofMinutes(1);
	private static final int MAX_SOURCE_URL = 1000;
	private static final Pattern UNSAFE_URL_CHARS = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\s<>\"']");
	private static final Pattern CREDENTIAL_LOOKING = Pattern.compile("(?i)(api[_-]?key|apikey|token|secret|password)=");

	/** Frasi (in forma di confronto: minuscole, senza accenti) che nel titolo indicano un prodotto e non una singola uscita. */
	private static final List<String> PRODUCT_PHRASES = List.of("abbonamento", "abbonamenti", "season ticket", "season tickets", "season pass",
			"pacchetto", "pacchetti", "package", "packages", "parcheggio", "parcheggi", "parking", "voucher", "gift card", "buono regalo",
			"carta regalo", "hospitality", "vip upgrade", "tessera");
	/** Come sopra, per il nome di tipo/sottotipo della classificazione (niente "season" da solo: "Regular Season" e' una partita vera). */
	private static final List<String> PRODUCT_TYPES = List.of("package", "packages", "parking", "merchandise", "voucher", "gift card", "membership",
			"subscription", "season ticket", "season tickets", "season pass");
	private static final Pattern DECIMAL = Pattern.compile("^[+-]?\\d{1,3}(\\.\\d{1,15})?$");

	// Id dei segmenti pubblicati da Ticketmaster (stabili); il nome fa da rete di sicurezza se l'id manca o cambia.
	private static final Map<String, EventCategory> CATEGORY_BY_SEGMENT_ID = Map.of(
			"KZFzniwnSyZfZ7v7nJ", EventCategory.MUSIC,
			"KZFzniwnSyZfZ7v7nE", EventCategory.SPORT,
			"KZFzniwnSyZfZ7v7na", EventCategory.CULTURE,
			"KZFzniwnSyZfZ7v7nn", EventCategory.CULTURE);
	private static final Map<String, EventCategory> CATEGORY_BY_SEGMENT_NAME = Map.ofEntries(
			Map.entry("music", EventCategory.MUSIC),
			Map.entry("musica", EventCategory.MUSIC),
			Map.entry("sports", EventCategory.SPORT),
			Map.entry("sport", EventCategory.SPORT),
			Map.entry("arts theatre", EventCategory.CULTURE),
			Map.entry("arts theater", EventCategory.CULTURE),
			Map.entry("arte e teatro", EventCategory.CULTURE),
			Map.entry("arti e teatro", EventCategory.CULTURE),
			Map.entry("film", EventCategory.CULTURE),
			Map.entry("cinema", EventCategory.CULTURE));

	private final Area area;

	TicketmasterEventNormalizer(Area area) {
		this.area = area;
	}

	/** Esito: l'evento (completo se valido, parziale se escluso) e il motivo di esclusione (null = candidato valido). */
	record Result(NormalizedEvent event, ExclusionReason reason) {

		boolean accepted() {
			return reason == null;
		}

	}

	private record DateOutcome(Instant start, Instant end, ExclusionReason failure) {
	}

	private record CoordinateOutcome(Double latitude, Double longitude, ExclusionReason failure) {
	}

	private record CategoryOutcome(EventCategory category, String segment, String genre) {
	}

	Result normalize(Event raw, Instant now, Instant windowEnd) {
		Venue venue = raw.firstVenue();
		String id = TextSanitizer.clean(raw.id(), 100);
		String title = TextSanitizer.clean(raw.name());
		String statusCode = raw.dates() != null && raw.dates().status() != null && raw.dates().status().code() != null
				? raw.dates().status().code().strip().toLowerCase(Locale.ROOT) : null;

		DateOutcome date = resolveDates(raw.dates(), venue, now, windowEnd);
		CoordinateOutcome coordinates = resolveCoordinates(venue);
		CategoryOutcome category = resolveCategory(raw);

		NormalizedEvent event = new NormalizedEvent(
				id,
				title,
				description(raw),
				category.category(),
				category.segment(),
				category.genre(),
				date.start(),
				date.end(),
				venue == null ? null : TextSanitizer.clean(venue.name()),
				address(venue),
				venue == null || venue.city() == null ? null : TextSanitizer.clean(venue.city().name()),
				coordinates.latitude(),
				coordinates.longitude(),
				TextSanitizer.clean(statusCode),
				raw.images() != null && raw.images().stream().anyMatch(image -> image != null && image.url() != null && !image.url().isBlank()),
				sourceUrl(raw.url()));

		ExclusionReason reason = firstFailure(raw, id, title, statusCode, date, coordinates, category);
		return new Result(event, reason);
	}

	private static ExclusionReason firstFailure(Event raw, String id, String title, String statusCode, DateOutcome date,
			CoordinateOutcome coordinates, CategoryOutcome category) {
		if (Boolean.TRUE.equals(raw.test())) {
			return ExclusionReason.TEST_EVENT;
		}
		if (id == null || title == null) {
			return ExclusionReason.MISSING_ID_OR_TITLE;
		}
		if (isNotSingleEvent(raw, title)) {
			return ExclusionReason.NOT_SINGLE_EVENT;
		}
		ExclusionReason status = statusFailure(statusCode);
		if (status != null) {
			return status;
		}
		if (date.failure() != null) {
			return date.failure();
		}
		if (coordinates.failure() != null) {
			return coordinates.failure();
		}
		if (category.category() == null) {
			return ExclusionReason.CATEGORY_UNRECOGNIZED;
		}
		return null;
	}

	private static ExclusionReason statusFailure(String statusCode) {
		if (statusCode == null) {
			return ExclusionReason.STATUS_UNKNOWN;
		}
		return switch (statusCode) {
			case "onsale", "offsale" -> null;
			case "canceled", "cancelled" -> ExclusionReason.STATUS_CANCELLED;
			case "postponed" -> ExclusionReason.STATUS_POSTPONED;
			case "rescheduled" -> ExclusionReason.STATUS_RESCHEDULED;
			default -> ExclusionReason.STATUS_UNKNOWN;
		};
	}

	// --- date ---

	private DateOutcome resolveDates(Dates dates, Venue venue, Instant now, Instant windowEnd) {
		Start start = dates == null ? null : dates.start();
		if (start == null) {
			return new DateOutcome(null, null, ExclusionReason.DATE_MISSING);
		}
		if (Boolean.TRUE.equals(start.dateTBD()) || Boolean.TRUE.equals(start.dateTBA()) || Boolean.TRUE.equals(start.timeTBA())
				|| Boolean.TRUE.equals(start.noSpecificTime())) {
			return new DateOutcome(null, null, ExclusionReason.DATE_UNRELIABLE);
		}

		ZoneId zone = zone(dates.timezone(), venue == null ? null : venue.timezone());
		boolean utcPresent = hasText(start.dateTime());
		boolean localPresent = hasText(start.localDate()) && hasText(start.localTime());
		Instant utc = utcPresent ? parseInstant(start.dateTime()) : null;
		// Con l'ora locale ripetuta (la notte del cambio ora) le interpretazioni sono due: l'istante UTC e' corretto se coincide con una.
		List<Instant> locals = localPresent ? parseLocalAll(start.localDate(), start.localTime(), zone) : List.of();
		Instant local = locals.isEmpty() ? null : locals.get(0);

		if ((utcPresent && utc == null) || (localPresent && local == null)) {
			return new DateOutcome(null, null, ExclusionReason.DATE_UNRELIABLE);
		}
		if (utc == null && local == null) {
			return new DateOutcome(null, null, ExclusionReason.DATE_MISSING);
		}
		if (utc != null && local != null && locals.stream().noneMatch(candidate -> Duration.between(utc, candidate).abs().compareTo(DATE_TOLERANCE) <= 0)) {
			return new DateOutcome(utc, null, ExclusionReason.DATE_INCONSISTENT);
		}

		Instant startsAt = utc != null ? utc : local;
		Instant endsAt = resolveEnd(dates, zone, startsAt);
		if (startsAt.isBefore(now)) {
			return new DateOutcome(startsAt, endsAt, ExclusionReason.DATE_PAST);
		}
		if (startsAt.isAfter(windowEnd)) {
			return new DateOutcome(startsAt, endsAt, ExclusionReason.DATE_BEYOND_WINDOW);
		}
		return new DateOutcome(startsAt, endsAt, null);
	}

	/** La fine e' facoltativa: la si usa solo se completa e successiva all'inizio, altrimenti si ignora. */
	private static Instant resolveEnd(Dates dates, ZoneId zone, Instant startsAt) {
		TicketmasterDto.End end = dates.end();
		if (end == null || Boolean.TRUE.equals(end.noSpecificTime())) {
			return null;
		}
		Instant value = hasText(end.dateTime()) ? parseInstant(end.dateTime())
				: hasText(end.localDate()) && hasText(end.localTime()) ? parseLocal(end.localDate(), end.localTime(), zone) : null;
		return value != null && value.isAfter(startsAt) ? value : null;
	}

	private static Instant parseInstant(String text) {
		try {
			return Instant.parse(text.strip());
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(text.strip()).toInstant();
			}
			catch (DateTimeParseException ignored) {
				return null;
			}
		}
	}

	/** Istanti possibili per data e ora locali: uno solo, o due se l'ora locale si ripete (fine dell'ora legale). Vuoto se non leggibile. */
	private static List<Instant> parseLocalAll(String date, String time, ZoneId zone) {
		try {
			LocalDateTime local = LocalDate.parse(date.strip()).atTime(LocalTime.parse(time.strip()));
			ZonedDateTime first = local.atZone(zone);               // nella sovrapposizione sceglie l'offset precedente; nel "buco" sposta avanti
			List<Instant> instants = new ArrayList<>(2);
			instants.add(first.toInstant());
			Instant later = first.withLaterOffsetAtOverlap().toInstant();
			if (!later.equals(first.toInstant())) {
				instants.add(later);
			}
			return instants;
		}
		catch (DateTimeParseException e) {
			return List.of();
		}
	}

	private static Instant parseLocal(String date, String time, ZoneId zone) {
		try {
			return LocalDate.parse(date.strip()).atTime(LocalTime.parse(time.strip())).atZone(zone).toInstant();
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Fuso dell'evento, poi quello della sede, poi Europe/Rome (l'area e' Milano). */
	private static ZoneId zone(String eventZone, String venueZone) {
		for (String candidate : new String[] { eventZone, venueZone }) {
			if (hasText(candidate)) {
				try {
					return ZoneId.of(candidate.strip());
				}
				catch (java.time.DateTimeException ignored) {
					// fuso non valido: si prova il successivo
				}
			}
		}
		return DEFAULT_ZONE;
	}

	// --- coordinate ---

	private CoordinateOutcome resolveCoordinates(Venue venue) {
		if (venue == null || venue.location() == null || !hasText(venue.location().latitude()) || !hasText(venue.location().longitude())) {
			return new CoordinateOutcome(null, null, ExclusionReason.COORDINATES_MISSING);
		}
		String rawLatitude = venue.location().latitude().strip();
		String rawLongitude = venue.location().longitude().strip();
		if (!DECIMAL.matcher(rawLatitude).matches() || !DECIMAL.matcher(rawLongitude).matches()) {
			return new CoordinateOutcome(null, null, ExclusionReason.COORDINATES_INVALID);
		}
		double latitude = Double.parseDouble(rawLatitude);
		double longitude = Double.parseDouble(rawLongitude);
		if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180
				|| (latitude == 0 && longitude == 0)) {
			return new CoordinateOutcome(null, null, ExclusionReason.COORDINATES_INVALID);
		}
		if (!area.contains(latitude, longitude)) {
			return new CoordinateOutcome(latitude, longitude, ExclusionReason.OUTSIDE_AREA);
		}
		return new CoordinateOutcome(latitude, longitude, null);
	}

	// --- categoria ---

	private static CategoryOutcome resolveCategory(Event raw) {
		Classification classification = primaryClassification(raw);
		if (classification == null) {
			return new CategoryOutcome(null, null, null);
		}
		Named segment = classification.segment();
		String segmentName = segment == null ? null : TextSanitizer.clean(segment.name(), 60);
		String genre = classification.genre() == null ? null : TextSanitizer.clean(classification.genre().name(), 60);
		EventCategory category = null;
		if (segment != null && segment.id() != null) {
			category = CATEGORY_BY_SEGMENT_ID.get(segment.id().strip());
		}
		if (category == null && segmentName != null) {
			category = CATEGORY_BY_SEGMENT_NAME.get(TextSanitizer.normalizeForComparison(segmentName));
		}
		return new CategoryOutcome(category, segmentName, genre);
	}

	private static Classification primaryClassification(Event raw) {
		if (raw.classifications() == null) {
			return null;
		}
		Classification first = null;
		for (Classification classification : raw.classifications()) {
			if (classification == null) {
				continue;
			}
			if (Boolean.TRUE.equals(classification.primary())) {
				return classification;
			}
			if (first == null) {
				first = classification;
			}
		}
		return first;
	}

	// --- prodotti che non sono una singola uscita ---

	static boolean isNotSingleEvent(Event raw, String title) {
		String paddedTitle = " " + TextSanitizer.normalizeForComparison(title) + " ";
		for (String phrase : PRODUCT_PHRASES) {
			if (paddedTitle.contains(" " + phrase + " ")) {
				return true;
			}
		}
		if (raw.classifications() != null) {
			for (Classification classification : raw.classifications()) {
				if (classification == null) {
					continue;
				}
				for (Named kind : new Named[] { classification.type(), classification.subType() }) {
					if (kind == null || kind.name() == null) {
						continue;
					}
					String paddedKind = " " + TextSanitizer.normalizeForComparison(kind.name()) + " ";
					for (String product : PRODUCT_TYPES) {
						if (paddedKind.contains(" " + product + " ")) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	// --- link all'annuncio ---

	/** URL https valido e ripulito, oppure null. Mai un URL con spazi/caratteri di controllo, credenziali nell'indirizzo o parametri tipo apikey=. */
	static String sourceUrl(String url) {
		if (url == null) {
			return null;
		}
		String cleaned = url.strip();
		if (cleaned.isEmpty() || cleaned.length() > MAX_SOURCE_URL || UNSAFE_URL_CHARS.matcher(cleaned).find()
				|| CREDENTIAL_LOOKING.matcher(cleaned).find()) {
			return null;
		}
		try {
			URI uri = new URI(cleaned);
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !uri.getHost().contains(".")
					|| uri.getRawUserInfo() != null) {
				return null;
			}
			String ascii = uri.toASCIIString();
			return ascii.length() > MAX_SOURCE_URL ? null : ascii;
		}
		catch (URISyntaxException e) {
			return null;
		}
	}

	// --- testi ---

	private static String description(Event raw) {
		String info = TextSanitizer.clean(raw.info());
		return info != null ? info : TextSanitizer.clean(raw.pleaseNote());
	}

	private static String address(Venue venue) {
		if (venue == null || venue.address() == null) {
			return null;
		}
		String line1 = TextSanitizer.clean(venue.address().line1());
		String line2 = TextSanitizer.clean(venue.address().line2());
		if (line1 == null) {
			return line2;
		}
		return line2 == null ? line1 : line1 + ", " + line2;
	}

	private static boolean hasText(String text) {
		return text != null && !text.isBlank();
	}

}
