package com.linkr.backend.ticketmaster;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pulizia dei testi che arrivano da Ticketmaster prima di metterli in un report o in un log: sono dati di terzi, quindi
 * si tolgono i caratteri di controllo e di formattazione (anche bidirezionali), si comprimono gli spazi, si accorcia e,
 * per prudenza, si oscura qualunque cosa assomigli a "apikey=..." (nessun URL con la chiave deve mai uscire dal backend).
 */
final class TextSanitizer {

	private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");
	private static final Pattern SECRET_PARAM = Pattern.compile("(?i)\\b(api[_-]?key|apikey|key|token|secret|consumer[_-]?secret)=[^&\\s\"'<>]*");
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

	private TextSanitizer() {
	}

	/** Testo ripulito, oppure null se non resta nulla. */
	static String clean(String text) {
		if (text == null) {
			return null;
		}
		String cleaned = CONTROL_OR_FORMAT.matcher(text).replaceAll(" ");
		cleaned = WHITESPACE.matcher(cleaned).replaceAll(" ").strip();
		cleaned = SECRET_PARAM.matcher(cleaned).replaceAll("$1=***");
		return cleaned.isEmpty() ? null : cleaned;
	}

	/** Come clean(), ma lungo al massimo maxLength caratteri (l'ultimo e' "…" se il testo e' stato accorciato). */
	static String clean(String text, int maxLength) {
		String cleaned = clean(text);
		if (cleaned == null || cleaned.length() <= maxLength) {
			return cleaned;
		}
		int end = maxLength - 1;
		if (end > 0 && Character.isHighSurrogate(cleaned.charAt(end - 1))) {
			end--; // non spezzare una coppia surrogata
		}
		return cleaned.substring(0, Math.max(end, 0)).stripTrailing() + "…";
	}

	/** Forma di confronto: minuscolo, senza accenti, solo lettere e cifre separate da uno spazio ("Café & Co." -> "cafe co"). */
	static String normalizeForComparison(String text) {
		if (text == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return NON_ALNUM.matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}

}
