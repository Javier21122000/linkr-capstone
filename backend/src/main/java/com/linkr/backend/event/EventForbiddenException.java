package com.linkr.backend.event;

/** L'utente e' autenticato ma non puo' fare questa operazione sull'evento. Diventa una risposta 403. */
public class EventForbiddenException extends RuntimeException {

	public EventForbiddenException(String message) {
		super(message);
	}

	static EventForbiddenException notOwner() {
		return new EventForbiddenException("Only the creator of the event or an administrator can do this.");
	}

}
