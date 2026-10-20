package com.linkr.backend.event;

/** L'evento non e' piu' modificabile: annullato o terminato. Diventa una risposta 409. */
public class EventNotEditableException extends RuntimeException {

	private EventNotEditableException(String message) {
		super(message);
	}

	static EventNotEditableException cancelled() {
		return new EventNotEditableException("The event has been cancelled and can no longer be changed.");
	}

	static EventNotEditableException ended() {
		return new EventNotEditableException("The event has ended and can no longer be changed.");
	}

}
