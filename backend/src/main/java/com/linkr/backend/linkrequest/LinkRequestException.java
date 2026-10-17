package com.linkr.backend.linkrequest;

import org.springframework.http.HttpStatus;

/**
 * Errori di dominio delle richieste Link. Ogni caso ha il suo codice HTTP e un titolo fisso; i messaggi sono
 * volutamente generici per non rivelare informazioni su altre persone.
 */
public class LinkRequestException extends RuntimeException {

	private final HttpStatus status;
	private final String title;

	private LinkRequestException(HttpStatus status, String title, String message) {
		super(message);
		this.status = status;
		this.title = title;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getTitle() {
		return title;
	}

	/** Richiesta a se stessi (400). */
	static LinkRequestException self() {
		return new LinkRequestException(HttpStatus.BAD_REQUEST, "Invalid link request",
				"You cannot send a link request to yourself.");
	}

	/** Destinatario inesistente, non partecipante o non visibile: STESSA risposta per tutti i casi (404). */
	static LinkRequestException recipientNotAvailable() {
		return new LinkRequestException(HttpStatus.NOT_FOUND, "Person not available",
				"The selected person is not available for this event.");
	}

	/** Esiste gia' una richiesta tra le due persone, in qualunque direzione e per qualunque evento (409). */
	static LinkRequestException duplicate() {
		return new LinkRequestException(HttpStatus.CONFLICT, "Link request already exists",
				"A link request already exists between you and this person.");
	}

	/** Richiesta inesistente o non indirizzata all'utente: non si distingue (404). */
	static LinkRequestException notFound() {
		return new LinkRequestException(HttpStatus.NOT_FOUND, "Link request not found",
				"The link request does not exist.");
	}

	/** Transizione contraddittoria, es. accettare una richiesta gia' rifiutata (409). */
	static LinkRequestException alreadyAnswered() {
		return new LinkRequestException(HttpStatus.CONFLICT, "Link request already answered",
				"This link request has already been answered.");
	}

	/** Limiti anti-spam superati (429). */
	static LinkRequestException tooMany() {
		return new LinkRequestException(HttpStatus.TOO_MANY_REQUESTS, "Too many link requests",
				"You have reached the limit of link requests. Try again later.");
	}

}
