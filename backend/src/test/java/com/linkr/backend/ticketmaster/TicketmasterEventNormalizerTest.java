package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.ARTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.FILM_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.MISC_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.NOW;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.SPORTS_ID;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.WINDOW_END;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.event;
import static com.linkr.backend.ticketmaster.TicketmasterTestData.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linkr.backend.event.EventCategory;
import com.linkr.backend.ticketmaster.TicketmasterEventNormalizer.Result;
import com.linkr.backend.ticketmaster.TicketmasterTestData.EventJson;

/** Il normalizzatore e' una funzione pura: nessun database, nessuna rete. */
class TicketmasterEventNormalizerTest {

	private final TicketmasterEventNormalizer normalizer = new TicketmasterEventNormalizer(TicketmasterTestData.properties().area());

	private Result normalize(EventJson event) {
		return normalizer.normalize(event.parsed(), NOW, WINDOW_END);
	}

	private void assertExcluded(EventJson event, ExclusionReason reason) {
		Result result = normalize(event);
		assertThat(result.reason()).isEqualTo(reason);
		assertThat(result.accepted()).isFalse();
	}

	// ---------- evento valido ----------

	@Test
	void validEventBecomesACandidateWithEveryField() {
		Result result = normalize(event("ev1").put("pleaseNote", "Porta un documento"));

		assertThat(result.accepted()).isTrue();
		NormalizedEvent e = result.event();
		assertThat(e.externalId()).isEqualTo("ev1");
		assertThat(e.title()).isEqualTo("Concerto ev1");
		assertThat(e.description()).isEqualTo("Una bella serata di musica dal vivo.");
		assertThat(e.category()).isEqualTo(EventCategory.MUSIC);
		assertThat(e.segment()).isEqualTo("Music");
		assertThat(e.genre()).isEqualTo("Rock");
		assertThat(e.startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));
		assertThat(e.endsAt()).isNull();
		assertThat(e.venueName()).isEqualTo("Fabrique");
		assertThat(e.address()).isEqualTo("Via Fantoli 9");
		assertThat(e.city()).isEqualTo("Milano");
		assertThat(e.latitude()).isEqualTo(45.435);
		assertThat(e.longitude()).isEqualTo(9.2315);
		assertThat(e.sourceStatus()).isEqualTo("onsale");
		assertThat(e.hasImage()).isTrue();
		assertThat(e.hasSourceUrl()).isTrue();
	}

	@Test
	void imageUrlsAreNeverKeptAndTheSourceLinkIsKeptOnlyWhenItIsAValidHttpsUrl() {
		// Nessun campo per URL di immagini: solo "esiste / non esiste".
		assertThat(NormalizedEvent.class.getRecordComponents()).extracting(c -> c.getName())
				.filteredOn(name -> !name.startsWith("has"))
				.noneMatch(name -> name.toLowerCase().contains("image"));
		NormalizedEvent event = normalize(event("ev1")).event();
		assertThat(event.toString()).doesNotContain("ticketm.net");
		assertThat(event.hasImage()).isTrue();
		// Il link all'annuncio si conserva (serve a "Dettagli ufficiali"), ma solo se valido.
		assertThat(event.sourceUrl()).isEqualTo("https://www.ticketmaster.it/event/ev1?utm=secret-looking");
		assertThat(event.hasSourceUrl()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "   ", "http://www.ticketmaster.it/event/1", "ftp://www.ticketmaster.it/e", "javascript:alert(1)",
			"data:text/html,<b>x</b>", "//www.ticketmaster.it/e", "/event/1", "https://", "https://localhost/e", "https://user:pw@www.ticketmaster.it/e",
			"https://www.ticketmaster.it/a b", "https://www.ticketmaster.it/e?apikey=SECRET", "https://www.ticketmaster.it/e?api_key=SECRET",
			"https://www.ticketmaster.it/e?token=abc", "https://www.ticketmaster.it/\"onmouseover=\"x", "https://www.ticketmaster.it/<script>" })
	void sourceLinksThatAreNotSafeHttpsUrlsAreDropped(String url) {
		Result result = normalize(event("ev1").put("url", url));

		assertThat(result.accepted()).isTrue();           // il link non valido non rende l'evento non valido per il dry-run...
		assertThat(result.event().sourceUrl()).isNull();  // ...ma l'importazione lo salta (nessun "Dettagli ufficiali" possibile)
		assertThat(result.event().hasSourceUrl()).isFalse();
	}

	@Test
	void sourceLinkIsTrimmedAndTooLongOnesAreDropped() {
		assertThat(normalize(event("a").put("url", "  https://www.ticketone.it/event/x  ")).event().sourceUrl()).isEqualTo("https://www.ticketone.it/event/x");
		assertThat(normalize(event("b").put("url", "https://www.ticketmaster.it/e/" + "x".repeat(990))).event().sourceUrl()).isNull();
		assertThat(normalize(event("c").removeKey("url")).event().sourceUrl()).isNull();
	}

	@Test
	void missingDescriptionAndImageDoNotExcludeTheEvent() {
		Result result = normalize(event("ev1").removeKey("info").noImages());

		assertThat(result.accepted()).isTrue();
		assertThat(result.event().hasDescription()).isFalse();
		assertThat(result.event().hasImage()).isFalse();
	}

	@Test
	void descriptionFallsBackToPleaseNote() {
		Result result = normalize(event("ev1").removeKey("info").put("pleaseNote", "  Ingresso   dalle 19  "));

		assertThat(result.event().description()).isEqualTo("Ingresso dalle 19");
	}

	@Test
	void missingVenueNameOrAddressIsTolerated() {
		Result result = normalize(event("ev1").venueName(null));

		assertThat(result.accepted()).isTrue();
		assertThat(result.event().venueName()).isNull();
	}

	@Test
	void unknownAndMissingFieldsNeverThrow() {
		Result result = normalizer.normalize(TicketmasterResponseParser
				.parse("{\"_embedded\":{\"events\":[{\"id\":\"x\",\"somethingNew\":{\"a\":1}}]}}").events().get(0), NOW, WINDOW_END);

		assertThat(result.accepted()).isFalse();
		assertThat(result.reason()).isEqualTo(ExclusionReason.MISSING_ID_OR_TITLE);
	}

	// ---------- categoria ----------

	@ParameterizedTest
	@CsvSource({ "KZFzniwnSyZfZ7v7nJ,Music,MUSIC", "KZFzniwnSyZfZ7v7nE,Sports,SPORT", "KZFzniwnSyZfZ7v7na,Arts & Theatre,CULTURE",
			"KZFzniwnSyZfZ7v7nn,Film,CULTURE" })
	void recognisedSegmentsMapToLinkrCategories(String id, String name, EventCategory expected) {
		assertThat(normalize(event("ev1").segment(id, name)).event().category()).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "Music,MUSIC", "Musica,MUSIC", "sports,SPORT", "Sport,SPORT", "Arts & Theatre,CULTURE", "Arts & Theater,CULTURE",
			"Arte e Teatro,CULTURE", "Cinema,CULTURE", "FILM,CULTURE" })
	void segmentNameAloneIsEnoughWhenTheIdIsMissing(String name, EventCategory expected) {
		Result result = normalize(event("ev1").segment(null, name));

		assertThat(result.accepted()).isTrue();
		assertThat(result.event().category()).isEqualTo(expected);
	}

	@Test
	void unrecognisedSegmentIsExcludedAndKeepsItsName() {
		Result result = normalize(event("ev1").segment(MISC_ID, "Miscellaneous"));

		assertThat(result.reason()).isEqualTo(ExclusionReason.CATEGORY_UNRECOGNIZED);
		assertThat(result.event().segment()).isEqualTo("Miscellaneous");
		assertThat(result.event().category()).isNull();
	}

	@Test
	void missingClassificationsAreAnUnrecognisedCategory() {
		assertExcluded(event("ev1").noClassifications(), ExclusionReason.CATEGORY_UNRECOGNIZED);
		assertExcluded(event("ev2").segment(null, null), ExclusionReason.CATEGORY_UNRECOGNIZED);
	}

	@Test
	void primaryClassificationWinsOverTheFirstOne() {
		EventJson event = event("ev1").put("classifications", new java.util.ArrayList<>(java.util.List.of(
				map("primary", false, "segment", map("id", MISC_ID, "name", "Miscellaneous")),
				map("primary", true, "segment", map("id", SPORTS_ID, "name", "Sports")))));

		assertThat(normalize(event).event().category()).isEqualTo(EventCategory.SPORT);
	}

	@Test
	void artsAndFilmIdsStillMapWhenTheNameIsLocalised() {
		assertThat(normalize(event("a").segment(ARTS_ID, "Arte e spettacolo")).event().category()).isEqualTo(EventCategory.CULTURE);
		assertThat(normalize(event("b").segment(FILM_ID, "Pellicole")).event().category()).isEqualTo(EventCategory.CULTURE);
	}

	// ---------- stato ----------

	@Test
	void onsaleAndOffsaleAreCandidates() {
		assertThat(normalize(event("a").status("onsale")).accepted()).isTrue();
		assertThat(normalize(event("b").status("offsale")).accepted()).isTrue();
		assertThat(normalize(event("c").status("OnSale")).accepted()).isTrue();
	}

	@Test
	void cancelledPostponedAndRescheduledAreNeverCandidates() {
		assertExcluded(event("a").status("canceled"), ExclusionReason.STATUS_CANCELLED);
		assertExcluded(event("b").status("cancelled"), ExclusionReason.STATUS_CANCELLED);
		assertExcluded(event("c").status("postponed"), ExclusionReason.STATUS_POSTPONED);
		assertExcluded(event("d").status("rescheduled"), ExclusionReason.STATUS_RESCHEDULED);
	}

	@Test
	void unknownOrMissingStatusIsExcluded() {
		assertExcluded(event("a").status("weird-new-status"), ExclusionReason.STATUS_UNKNOWN);
		assertExcluded(event("b").status(null), ExclusionReason.STATUS_UNKNOWN);
		assertExcluded(event("c").status("  "), ExclusionReason.STATUS_UNKNOWN);
	}

	// ---------- data e fuso orario ----------

	@ParameterizedTest
	@ValueSource(strings = { "dateTBD", "dateTBA", "timeTBA", "noSpecificTime" })
	void datesToBeDefinedAreNotReliable(String flag) {
		assertExcluded(event("a").start(flag, true), ExclusionReason.DATE_UNRELIABLE);
	}

	@Test
	void missingStartIsExcluded() {
		assertExcluded(event("a").noStart(), ExclusionReason.DATE_MISSING);
		assertExcluded(event("b").removeStart("dateTime").removeStart("localTime"), ExclusionReason.DATE_MISSING);
		assertExcluded(event("c").removeStart("dateTime").removeStart("localDate").removeStart("localTime"), ExclusionReason.DATE_MISSING);
	}

	@Test
	void localDateAndTimeUseTheEventTimezoneInWinter() {
		// 20:00 a Milano il 5 novembre (CET, UTC+1) = 19:00 UTC
		Result result = normalize(event("a").removeStart("dateTime"));

		assertThat(result.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));
	}

	@Test
	void localDateAndTimeUseTheEventTimezoneInSummerTime() {
		// 21:00 a Milano il 24 ottobre (ancora CEST, UTC+2) = 19:00 UTC; il passaggio all'ora solare e' il 25
		Result result = normalize(event("a").removeStart("dateTime").start("localDate", "2026-10-24").start("localTime", "21:00:00"));

		assertThat(result.accepted()).isTrue();
		assertThat(result.event().startsAt()).isEqualTo(Instant.parse("2026-10-24T19:00:00Z"));
	}

	@Test
	void theRepeatedHourOfTheClockChangeNightIsAcceptedInBothOccurrences() {
		// Il 25 ottobre 2026 alle 03:00 CEST le lancette tornano alle 02:00 CET: 02:30 locale esiste due volte (00:30Z e 01:30Z).
		// Per UTC fa fede l'istante della fonte; l'ora locale ambigua deve essere compatibile con uno dei due.
		Instant firstClock = Instant.parse("2026-10-25T00:30:00Z");
		Instant secondClock = Instant.parse("2026-10-25T01:30:00Z");
		for (Instant instant : new Instant[] { firstClock, secondClock }) {
			Result result = normalizer.normalize(event("dst").start("dateTime", instant.toString()).start("localDate", "2026-10-25")
					.start("localTime", "02:30:00").parsed(), NOW, WINDOW_END);

			assertThat(result.accepted()).as(instant.toString()).isTrue();
			assertThat(result.event().startsAt()).isEqualTo(instant);
		}
		// un'ora locale che non c'e' nemmeno in una delle due interpretazioni resta un errore di coerenza
		assertExcluded(event("bad").start("dateTime", "2026-10-25T05:30:00Z").start("localDate", "2026-10-25").start("localTime", "02:30:00"),
				ExclusionReason.DATE_INCONSISTENT);
		// solo ora locale ambigua, senza UTC: si prende la prima occorrenza (documentato), l'evento non viene perso
		Result onlyLocal = normalize(event("local").removeStart("dateTime").start("localDate", "2026-10-25").start("localTime", "02:30:00"));
		assertThat(onlyLocal.accepted()).isTrue();
		assertThat(onlyLocal.event().startsAt()).isEqualTo(firstClock);
	}

	@Test
	void eventTimezoneWinsOverTheVenueTimezoneAndMissingOnesFallBackToRome() {
		Result london = normalize(event("a").removeStart("dateTime").timezone("Europe/London"));
		assertThat(london.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T20:00:00Z"));

		Result venueOnly = normalize(event("b").removeStart("dateTime").timezone(null));
		assertThat(venueOnly.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));

		Result neither = normalize(event("c").removeStart("dateTime").timezone(null).removeVenueTimezone());
		assertThat(neither.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));

		Result invalidZone = normalize(event("d").removeStart("dateTime").timezone("Not/AZone").removeVenueTimezone());
		assertThat(invalidZone.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));
	}

	@Test
	void utcAndLocalTimesThatDisagreeAreNotReliable() {
		// UTC dice 19:00Z ma l'ora locale 18:00 a Milano sarebbe 17:00Z
		assertExcluded(event("a").start("localTime", "18:00:00"), ExclusionReason.DATE_INCONSISTENT);
	}

	@Test
	void unparseableDatesAreNotReliable() {
		assertExcluded(event("a").start("dateTime", "domani sera"), ExclusionReason.DATE_UNRELIABLE);
		assertExcluded(event("b").removeStart("dateTime").start("localDate", "2026-13-45"), ExclusionReason.DATE_UNRELIABLE);
		assertExcluded(event("c").removeStart("dateTime").start("localTime", "25:99"), ExclusionReason.DATE_UNRELIABLE);
	}

	@Test
	void offsetDateTimesAreAccepted() {
		Result result = normalize(event("a").start("dateTime", "2026-11-05T20:00:00+01:00"));

		assertThat(result.event().startsAt()).isEqualTo(Instant.parse("2026-11-05T19:00:00Z"));
	}

	@Test
	void eventsOutsideTheWindowAreExcluded() {
		assertExcluded(event("past").start("dateTime", "2026-10-22T09:59:00Z").start("localTime", "11:59:00").start("localDate", "2026-10-22"),
				ExclusionReason.DATE_PAST);
		assertExcluded(event("far").start("dateTime", "2026-11-21T10:00:01Z").start("localDate", "2026-11-21").start("localTime", "11:00:01"),
				ExclusionReason.DATE_BEYOND_WINDOW);
	}

	@Test
	void theWindowEdgesAreInclusive() {
		assertThat(normalize(event("now").start("dateTime", "2026-10-22T10:00:00Z").start("localDate", "2026-10-22")
				.start("localTime", "12:00:00")).accepted()).isTrue();
		assertThat(normalize(event("end").start("dateTime", "2026-11-21T10:00:00Z").start("localDate", "2026-11-21")
				.start("localTime", "11:00:00")).accepted()).isTrue();
	}

	@Test
	void endTimeIsOptionalAndOnlyUsedWhenAfterTheStart() {
		EventJson withEnd = event("a").put("dates", map(
				"start", map("dateTime", "2026-11-05T19:00:00Z", "localDate", "2026-11-05", "localTime", "20:00:00"),
				"end", map("dateTime", "2026-11-05T22:00:00Z"),
				"timezone", "Europe/Rome", "status", map("code", "onsale")));
		assertThat(normalize(withEnd).event().endsAt()).isEqualTo(Instant.parse("2026-11-05T22:00:00Z"));

		EventJson badEnd = event("b").put("dates", map(
				"start", map("dateTime", "2026-11-05T19:00:00Z", "localDate", "2026-11-05", "localTime", "20:00:00"),
				"end", map("dateTime", "2026-11-05T18:00:00Z"),
				"timezone", "Europe/Rome", "status", map("code", "onsale")));
		assertThat(normalize(badEnd).event().endsAt()).isNull();
	}

	// ---------- coordinate ----------

	@Test
	void missingCoordinatesAreExcluded() {
		assertExcluded(event("a").noVenue(), ExclusionReason.COORDINATES_MISSING);
		assertExcluded(event("b").noLocation(), ExclusionReason.COORDINATES_MISSING);
		assertExcluded(event("c").coordinates(null, "9.2"), ExclusionReason.COORDINATES_MISSING);
		assertExcluded(event("d").coordinates("45.4", " "), ExclusionReason.COORDINATES_MISSING);
	}

	@ParameterizedTest
	@CsvSource({ "abc,9.2", "45.4,xyz", "NaN,9.2", "45.4,Infinity", "95.0,9.2", "45.4,181.0", "-91,9.2", "0,0", "0.0,0.0", "1e1,9.2", "45.4d,9.2",
			"'45,4',9.2" })
	void invalidCoordinatesAreExcluded(String latitude, String longitude) {
		assertExcluded(event("a").coordinates(latitude, longitude), ExclusionReason.COORDINATES_INVALID);
	}

	@Test
	void coordinatesOutsideMilanAreExcludedButStillReportedAsOutsideTheArea() {
		Result rome = normalize(event("a").coordinates("41.9028", "12.4964"));

		assertThat(rome.reason()).isEqualTo(ExclusionReason.OUTSIDE_AREA);
		assertThat(rome.event().latitude()).isEqualTo(41.9028);
	}

	@Test
	void coordinatesAsJsonNumbersAreAccepted() {
		Result result = normalize(event("a").coordinates(45.4642, 9.19));

		assertThat(result.accepted()).isTrue();
		assertThat(result.event().latitude()).isEqualTo(45.4642);
	}

	@Test
	void areaBoundaryIsInclusive() {
		assertThat(normalize(event("a").coordinates("45.35", "8.95")).accepted()).isTrue();
		assertThat(normalize(event("b").coordinates("45.60", "9.45")).accepted()).isTrue();
		assertExcluded(event("c").coordinates("45.6001", "9.2"), ExclusionReason.OUTSIDE_AREA);
	}

	// ---------- altri motivi e ordine ----------

	@Test
	void testEventsAreExcluded() {
		assertExcluded(event("a").put("test", true), ExclusionReason.TEST_EVENT);
	}

	@Test
	void missingIdOrTitleIsExcluded() {
		assertExcluded(event("a").removeKey("id"), ExclusionReason.MISSING_ID_OR_TITLE);
		assertExcluded(event("b").title("   "), ExclusionReason.MISSING_ID_OR_TITLE);
		assertExcluded(event("c").removeKey("name"), ExclusionReason.MISSING_ID_OR_TITLE);
	}

	@Test
	void eachExcludedEventHasOneReasonFollowingTheDocumentedOrder() {
		// annullato E senza coordinate E senza data: vince lo stato
		assertExcluded(event("a").status("canceled").noLocation().noStart(), ExclusionReason.STATUS_CANCELLED);
		// data inaffidabile E senza coordinate: vince la data
		assertExcluded(event("b").start("dateTBA", true).noLocation(), ExclusionReason.DATE_UNRELIABLE);
		// senza coordinate E categoria sconosciuta: vincono le coordinate
		assertExcluded(event("c").noLocation().segment(MISC_ID, "Miscellaneous"), ExclusionReason.COORDINATES_MISSING);
		// evento di test vince su tutto
		assertExcluded(event("d").put("test", true).status("canceled"), ExclusionReason.TEST_EVENT);
	}

	// ---------- prodotti che non sono una singola uscita ----------

	@ParameterizedTest
	@ValueSource(strings = { "Abbonamento Stagione 2026/27", "ABBONAMENTI Olimpia Milano", "Season Ticket Inter 2026-27", "Season Pass Estate",
			"Pacchetto VIP + Hotel", "Concerto con pacchetti hospitality", "Hospitality Premium - Milan vs Inter", "Parcheggio Stadio San Siro",
			"Parking Forum di Assago", "Voucher regalo per due", "Gift Card Teatro", "Carta Regalo Forum", "VIP Upgrade - Mahmood", "Tessera del tifoso",
			"Event Package: Concerto + Cena" })
	void productsThatAreNotASingleOutingAreExcluded(String title) {
		assertExcluded(event("ev1").title(title), ExclusionReason.NOT_SINGLE_EVENT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "Inter - Milan", "Concerto di Mahmood", "Regular Season: Olimpia Milano - Virtus Bologna", "Il Pacco di Natale",
			"Packaging Design Expo", "Abbonati alla gioia - Spettacolo", "Parkour Show", "Vouchers? No: Festival del Jazz", "Mostra Hospitalia" })
	void realEventsWithSimilarWordsAreNotExcluded(String title) {
		assertThat(normalize(event("ev1").title(title)).accepted()).as(title).isTrue();
	}

	@Test
	void classificationTypeAndSubTypeCanMarkAProduct() {
		assertExcluded(event("a").classificationType("Package", null), ExclusionReason.NOT_SINGLE_EVENT);
		assertExcluded(event("b").classificationType("Individual", "Season Ticket"), ExclusionReason.NOT_SINGLE_EVENT);
		assertExcluded(event("c").classificationType("Undefined", "Parking"), ExclusionReason.NOT_SINGLE_EVENT);
		assertExcluded(event("d").classificationType(null, "Merchandise"), ExclusionReason.NOT_SINGLE_EVENT);
		// una partita di campionato e' una singola uscita anche se il sottotipo dice "Regular Season"
		assertThat(normalize(event("e").classificationType("Individual", "Regular Season")).accepted()).isTrue();
		assertThat(normalize(event("f").classificationType("Group", "Undefined")).accepted()).isTrue();
	}

	@Test
	void aProductIsExcludedBeforeStatusDateOrCategoryAreEvenLookedAt() {
		assertExcluded(event("a").title("Abbonamento 2026").status("canceled").noLocation().segment(MISC_ID, "Miscellaneous"),
				ExclusionReason.NOT_SINGLE_EVENT);
	}

	@Test
	void textFromTheSourceIsCleanedAndSecretLookingParametersAreMasked() {
		Result result = normalize(event("a").title("Rock\u0000 ‮fest\n night  https://x.example/?apikey=abc123&y=1")
				.venueName("Sala\tUno"));

		assertThat(result.event().title()).isEqualTo("Rock fest night https://x.example/?apikey=***&y=1");
		assertThat(result.event().venueName()).isEqualTo("Sala Uno");
	}

}
