package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linkr.backend.ticketmaster.DuplicateMatcher.Candidate;

/** Piccole funzioni pure: geohash, pulizia dei testi, stima dei duplicati. */
class TicketmasterUtilitiesTest {

	// ---------- Geohash ----------

	@Test
	void geohashMatchesTheReferenceVector() {
		// vettore di riferimento classico (Wikipedia): 57.64911, 10.40744 -> u4pruydqqvj
		assertThat(Geohash.encode(57.64911, 10.40744, 11)).isEqualTo("u4pruydqqvj");
		assertThat(Geohash.encode(57.64911, 10.40744, 5)).isEqualTo("u4pru");
	}

	@Test
	void geohashOfMilanCentreStartsWithTheMilanCell() {
		assertThat(Geohash.encode(45.4642, 9.19, 7)).startsWith("u0nd");
		assertThat(Geohash.encode(45.4642, 9.19, 7)).hasSize(7);
	}

	// ---------- TextSanitizer ----------

	@Test
	void cleanRemovesControlAndBidiCharactersAndCollapsesSpaces() {
		assertThat(TextSanitizer.clean("  a\u0000b‮c\n\td   e  ")).isEqualTo("a b c d e");
		assertThat(TextSanitizer.clean("\u0000‮ \n")).isNull();
		assertThat(TextSanitizer.clean(null)).isNull();
		assertThat(TextSanitizer.clean("")).isNull();
	}

	@Test
	void cleanMasksSecretLookingParameters() {
		assertThat(TextSanitizer.clean("https://app.ticketmaster.com/x?apikey=SECRET123&size=5")).isEqualTo("https://app.ticketmaster.com/x?apikey=***&size=5");
		assertThat(TextSanitizer.clean("see api_key=abc and token=def")).isEqualTo("see api_key=*** and token=***");
		assertThat(TextSanitizer.clean("APIKEY=XYZ")).isEqualTo("APIKEY=***");
	}

	@Test
	void cleanTruncatesWithAnEllipsisAndNeverSplitsASurrogatePair() {
		assertThat(TextSanitizer.clean("abcdefghij", 5)).isEqualTo("abcd…");
		assertThat(TextSanitizer.clean("abc", 5)).isEqualTo("abc");
		String emojis = "🎶🎶🎶"; // tre note musicali (2 char ciascuna)
		assertThat(TextSanitizer.clean(emojis, 4)).isEqualTo("🎶…");
	}

	@Test
	void comparisonFormIgnoresCaseAccentsAndPunctuation() {
		assertThat(TextSanitizer.normalizeForComparison("  Café & Co.: Live!  ")).isEqualTo("cafe co live");
		assertThat(TextSanitizer.normalizeForComparison(null)).isEmpty();
	}

	// ---------- DuplicateMatcher ----------

	private static final Instant START = Instant.parse("2026-11-05T19:00:00Z");

	private static Candidate at(String title, double lat, double lon, Instant start) {
		return new Candidate(title, lat, lon, start);
	}

	@Test
	void sameShowAtTheSameVenueAndTimeIsLikelyTheSameEvent() {
		assertThat(DuplicateMatcher.likelySameEvent(at("Mahmood - Live a Milano", 45.4350, 9.2315, START),
				at("MAHMOOD live", 45.4352, 9.2317, START.plusSeconds(1800)))).isTrue();
	}

	@Test
	void accentsPunctuationAndGenericWordsDoNotMatter() {
		assertThat(DuplicateMatcher.titlesSimilar("Notte di Jazz al Blue Note", "Jazz: Blue Note - notte")).isTrue();
		assertThat(DuplicateMatcher.titlesSimilar("Café Concert", "cafe concert")).isTrue();
	}

	@Test
	void differentTitlesAreNotDuplicatesEvenAtTheSameVenueAndTime() {
		assertThat(DuplicateMatcher.likelySameEvent(at("Concerto di Mahmood", 45.435, 9.2315, START),
				at("Yoga all'alba", 45.435, 9.2315, START))).isFalse();
	}

	@Test
	void sameTitleFarAwayOrFarApartInTimeIsNotADuplicate() {
		Candidate base = at("Jazz night", 45.435, 9.2315, START);
		assertThat(DuplicateMatcher.likelySameEvent(base, at("Jazz night", 45.50, 9.2315, START))).isFalse(); // ~7 km
		assertThat(DuplicateMatcher.likelySameEvent(base, at("Jazz night", 45.435, 9.2315, START.plusSeconds(4 * 3600)))).isFalse();
		assertThat(DuplicateMatcher.likelySameEvent(base, at("Jazz night", 45.435, 9.2315, null))).isFalse();
	}

	@Test
	void aSingleSharedWordIsNotEnough() {
		assertThat(DuplicateMatcher.titlesSimilar("Inter - Milan", "Inter Club Milano party")).isFalse();
		assertThat(DuplicateMatcher.titlesSimilar("", "Jazz")).isFalse();
		assertThat(DuplicateMatcher.titlesSimilar("live tour", "live show")).isFalse(); // solo parole generiche
	}

	@ParameterizedTest
	@CsvSource({ "0,true", "399,true", "450,false" })
	void distanceThresholdIsAboutFourHundredMetres(int metres, boolean expected) {
		double lonOffset = metres / (111_320.0 * Math.cos(Math.toRadians(45.435)));
		assertThat(DuplicateMatcher.likelySameEvent(at("Jazz night", 45.435, 9.2315, START),
				at("Jazz night", 45.435, 9.2315 + lonOffset, START))).isEqualTo(expected);
	}

}
