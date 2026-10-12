package com.linkr.backend.event;

/** Filtri di ricerca incoerenti (es. "from" dopo "to"). Diventa una risposta 400. */
public class InvalidEventFilterException extends RuntimeException {

	public InvalidEventFilterException(String message) {
		super(message);
	}

}
