package com.linkr.backend.people;

/** Per vedere chi partecipa serve una propria partecipazione all'evento. Diventa una risposta 403. */
public class ParticipationRequiredException extends RuntimeException {

	public ParticipationRequiredException() {
		super("Declare your own participation in this event to see who else is going.");
	}

}
