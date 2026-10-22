package com.linkr.backend.ticketmaster;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

/**
 * Stima se due annunci sono "probabilmente lo stesso evento". Serve SOLO a segnalare nel report (nessuna fusione automatica):
 * decide una persona. Tre condizioni insieme:
 *  - inizio a meno di 3 ore l'uno dall'altro;
 *  - luogo a meno di 400 m;
 *  - titoli simili: dopo aver tolto accenti, punteggiatura e parole generiche (articoli, "live", "tour"...), le parole in comune sono
 *    almeno il 60 % di quelle totali (Jaccard) oppure il titolo piu' corto e' contenuto (80 %) nell'altro con almeno 2 parole in
 *    comune, o con una sola parola lunga (5+ lettere).
 */
final class DuplicateMatcher {

	static final Duration MAX_START_DIFFERENCE = Duration.ofHours(3);
	static final double MAX_DISTANCE_KM = 0.4;
	static final Duration OBVIOUS_MAX_START_DIFFERENCE = Duration.ofMinutes(1);
	static final double OBVIOUS_MAX_DISTANCE_KM = 0.1;

	private static final double JACCARD_THRESHOLD = 0.6;
	private static final double CONTAINMENT_THRESHOLD = 0.8;
	private static final int LONG_WORD = 5;
	private static final Set<String> STOP_WORDS = Set.of("a", "al", "alla", "ai", "agli", "e", "ed", "i", "il", "lo", "la", "le", "gli", "un",
			"una", "di", "del", "della", "dei", "delle", "da", "in", "con", "per", "the", "of", "and", "at", "live", "tour", "concerto",
			"show", "evento", "event");

	private DuplicateMatcher() {
	}

	/** Gli elementi minimi che servono al confronto. */
	record Candidate(String title, double latitude, double longitude, Instant startsAt) {
	}

	static boolean likelySameEvent(Candidate a, Candidate b) {
		if (a.startsAt() == null || b.startsAt() == null) {
			return false;
		}
		if (Duration.between(a.startsAt(), b.startsAt()).abs().compareTo(MAX_START_DIFFERENCE) > 0) {
			return false;
		}
		if (TicketmasterProperties.distanceKm(a.latitude(), a.longitude(), b.latitude(), b.longitude()) > MAX_DISTANCE_KM) {
			return false;
		}
		return titlesSimilar(a.title(), b.title());
	}

	/**
	 * Duplicato EVIDENTE, per decidere se NON creare un evento: titolo identico (dopo la normalizzazione), stesso istante di inizio
	 * (entro un minuto) e luogo a meno di 100 m. Molto piu' stretto di likelySameEvent(): due eventi diversi nella stessa sede,
	 * anche con titoli simili o allo stesso orario, NON sono un duplicato evidente e restano entrambi.
	 */
	static boolean obviousDuplicate(Candidate a, Candidate b) {
		if (a.startsAt() == null || b.startsAt() == null) {
			return false;
		}
		String titleA = TextSanitizer.normalizeForComparison(a.title());
		if (titleA.isEmpty() || !titleA.equals(TextSanitizer.normalizeForComparison(b.title()))) {
			return false;
		}
		return Duration.between(a.startsAt(), b.startsAt()).abs().compareTo(OBVIOUS_MAX_START_DIFFERENCE) <= 0
				&& TicketmasterProperties.distanceKm(a.latitude(), a.longitude(), b.latitude(), b.longitude()) <= OBVIOUS_MAX_DISTANCE_KM;
	}

	static boolean titlesSimilar(String first, String second) {
		Set<String> a = words(first);
		Set<String> b = words(second);
		if (a.isEmpty() || b.isEmpty()) {
			return false;
		}
		Set<String> shared = new TreeSet<>(a);
		shared.retainAll(b);
		if (shared.isEmpty()) {
			return false;
		}
		int union = a.size() + b.size() - shared.size();
		double jaccard = (double) shared.size() / union;
		double containment = (double) shared.size() / Math.min(a.size(), b.size());
		// Il titolo piu' corto e' (quasi) tutto contenuto nell'altro: servono 2 parole in comune, oppure una sola ma lunga
		// (tipico nome d'artista: "Mahmood" contro "Mahmood - Live a Milano").
		boolean contained = containment >= CONTAINMENT_THRESHOLD
				&& (shared.size() >= 2 || shared.stream().anyMatch(word -> word.length() >= LONG_WORD));
		return jaccard >= JACCARD_THRESHOLD || contained;
	}

	private static Set<String> words(String title) {
		Set<String> words = new TreeSet<>();
		for (String word : TextSanitizer.normalizeForComparison(title).split(" ")) {
			if (!word.isEmpty() && !STOP_WORDS.contains(word)) {
				words.add(word);
			}
		}
		return words;
	}

}
