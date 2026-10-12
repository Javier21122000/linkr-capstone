package com.linkr.backend.event;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {

	public EventNotFoundException(UUID id) {
		super("Event " + id + " not found");
	}

}
