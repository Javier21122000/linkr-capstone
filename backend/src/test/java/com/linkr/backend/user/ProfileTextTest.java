package com.linkr.backend.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ProfileTextTest {

	private final Map<String, String> errors = new LinkedHashMap<>();

	@Test
	void displayNameIsTrimmedAndSpacesCollapsed() {
		assertThat(ProfileText.displayName("  Anna   Rossi \t", errors)).isEqualTo("Anna Rossi");
		assertThat(errors).isEmpty();
	}

	@Test
	void displayNameRejectsTooShortTooLongMarkupAndInvisibleCharacters() {
		for (String bad : new String[] { " ", "A", "x".repeat(61), "<b>Anna</b>", "An‮na", "An\u0000na", "An​na" }) {
			Map<String, String> e = new LinkedHashMap<>();
			ProfileText.displayName(bad, e);
			assertThat(e).as(bad).containsKey("displayName");
		}
	}

	@Test
	void displayNameAcceptsAccentsAndEmojiCountedAsCharacters() {
		assertThat(ProfileText.displayName("Zoë Müller 🎧", errors)).isEqualTo("Zoë Müller 🎧");
		assertThat(errors).isEmpty();
	}

	@Test
	void bioBlankBecomesNullAndNewlinesAreNormalised() {
		assertThat(ProfileText.bio("   \n ", errors)).isNull();
		assertThat(ProfileText.bio(null, errors)).isNull();
		assertThat(ProfileText.bio("Ciao\r\n\r\n\r\n\r\nMondo", errors)).isEqualTo("Ciao\n\nMondo");
		assertThat(errors).isEmpty();
	}

	@Test
	void bioRejectsOverLimitAndControlCharacters() {
		ProfileText.bio("x".repeat(ProfileText.BIO_MAX + 1), errors);
		assertThat(errors).containsKey("bio");
		errors.clear();
		ProfileText.bio("x".repeat(ProfileText.BIO_MAX), errors);
		assertThat(errors).isEmpty();
		ProfileText.bio("ciao\u0007", errors);
		assertThat(errors).containsKey("bio");
	}

}
