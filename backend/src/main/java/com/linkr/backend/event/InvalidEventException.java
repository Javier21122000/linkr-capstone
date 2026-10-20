package com.linkr.backend.event;

import java.util.Map;

/** Dati dell'evento incoerenti tra loro (es. fine prima dell'inizio). Diventa una risposta 400 con l'elenco dei campi. */
public class InvalidEventException extends RuntimeException {

	private final Map<String, String> errors;

	public InvalidEventException(Map<String, String> errors) {
		super("Invalid event data.");
		this.errors = errors;
	}

	public Map<String, String> getErrors() {
		return errors;
	}

}
