package com.linkr.backend.participation;

/** L'evento e' terminato: non si possono piu' dichiarare o cambiare partecipazioni. Diventa una risposta 409. */
public class EventEndedException extends RuntimeException {

	public EventEndedException() {
		super("Participation can no longer be declared or changed: the event has ended.");
	}

}
