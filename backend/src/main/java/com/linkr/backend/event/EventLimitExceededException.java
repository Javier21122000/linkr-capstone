package com.linkr.backend.event;

/** Troppi eventi creati nelle ultime 24 ore. Diventa una risposta 429. */
public class EventLimitExceededException extends RuntimeException {

	public EventLimitExceededException() {
		super("You have reached the daily limit of created events. Try again later.");
	}

}
