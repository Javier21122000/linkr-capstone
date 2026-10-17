package com.linkr.backend.participation;

/** L'utente non ha dichiarato nessuna partecipazione a quell'evento. Diventa una risposta 404. */
public class ParticipationNotFoundException extends RuntimeException {

	public ParticipationNotFoundException() {
		super("No participation declared for this event");
	}

}
