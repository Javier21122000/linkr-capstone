package com.linkr.backend.demo;

import java.util.List;
import java.util.UUID;

/** Personaggio dimostrativo. Interessi = slug del catalogo (V3). */
record DemoPersona(int number, String key, String displayName, String bio, List<String> interests) {

	static final String EMAIL_DOMAIN = "demo.linkr.invalid";
	static final String USERNAME_PREFIX = "demo_";
	/** Non e' un hash BCrypt valido: BCryptPasswordEncoder.matches() risponde sempre false, quindi nessun accesso e nessuna password nel repository. */
	static final String UNUSABLE_PASSWORD_HASH = "!demo-account-no-login";
	static final String BIO_SUFFIX = " Profilo demo.";

	UUID id() {
		return UUID.fromString("018f4000-0000-7000-8000-%012d".formatted(number));
	}

	String username() {
		return USERNAME_PREFIX + key;
	}

	String email() {
		return username() + "@" + EMAIL_DOMAIN;
	}

	/** Avatar fotografico locale (frontend/public/demo/avatars/<chiave>.webp). */
	String avatarUrl() {
		return DemoArt.avatarUrl(key);
	}

	String fullBio() {
		return bio + BIO_SUFFIX;
	}

}
