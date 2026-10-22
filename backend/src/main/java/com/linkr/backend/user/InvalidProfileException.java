package com.linkr.backend.user;

import java.util.Map;

/** Dati del profilo non validi. Diventa una risposta 400 con l'elenco dei campi sbagliati (mai i valori inviati). */
public class InvalidProfileException extends RuntimeException {

	private final Map<String, String> errors;

	public InvalidProfileException(Map<String, String> errors) {
		super("Invalid profile data.");
		this.errors = errors;
	}

	public Map<String, String> getErrors() {
		return errors;
	}

}
