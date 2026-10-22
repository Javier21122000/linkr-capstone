package com.linkr.backend.user;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pulizia e regole dei testi del profilo. Il nome visualizzato e la bio sono mostrati ad altre persone e in altre schermate:
 * si rifiutano caratteri di controllo e di formattazione invisibili (anche i "bidi override" che invertono il testo),
 * e '<' '>' nel nome. L'escape in uscita resta compito del client (React): questa e' una seconda difesa, non l'unica.
 */
final class ProfileText {

	static final int DISPLAY_NAME_MIN = 2;
	static final int DISPLAY_NAME_MAX = 60;
	static final int BIO_MAX = 300;

	private static final Pattern SPACES = Pattern.compile("\\s+");

	private ProfileText() {
	}

	/** Nome pulito (spazi ai bordi tolti, spazi interni ridotti a uno) oppure aggiunge l'errore. */
	static String displayName(String raw, Map<String, String> errors) {
		String name = SPACES.matcher(raw.strip()).replaceAll(" ");
		int length = name.codePointCount(0, name.length());
		if (length < DISPLAY_NAME_MIN || length > DISPLAY_NAME_MAX) {
			errors.put("displayName", "must be between " + DISPLAY_NAME_MIN + " and " + DISPLAY_NAME_MAX + " characters");
		}
		else if (hasForbidden(name, false) || name.indexOf('<') >= 0 || name.indexOf('>') >= 0) {
			errors.put("displayName", "contains characters that are not allowed");
		}
		return name;
	}

	/** Bio pulita (a capo normalizzati, massimo una riga vuota di seguito) o null se vuota; altrimenti aggiunge l'errore. */
	static String bio(String raw, Map<String, String> errors) {
		if (raw == null) {
			return null;
		}
		String bio = raw.replace("\r\n", "\n").replace('\r', '\n').strip().replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n");
		if (bio.isEmpty()) {
			return null;
		}
		if (bio.codePointCount(0, bio.length()) > BIO_MAX) {
			errors.put("bio", "must be at most " + BIO_MAX + " characters");
		}
		else if (hasForbidden(bio, true)) {
			errors.put("bio", "contains characters that are not allowed");
		}
		return bio;
	}

	private static boolean hasForbidden(String text, boolean allowNewline) {
		return text.codePoints().anyMatch(cp -> {
			if (cp == '\n') {
				return !allowNewline;
			}
			int type = Character.getType(cp);
			return type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
					|| type == Character.PRIVATE_USE || type == Character.UNASSIGNED
					|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
		});
	}

}
