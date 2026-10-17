package com.linkr.backend.interest;

import java.util.List;
import java.util.UUID;

/** Selezione di interessi non valida (duplicati o ID inesistenti). Diventa una risposta 400. */
public class InvalidInterestSelectionException extends RuntimeException {

	private final transient List<UUID> unknownIds;

	public InvalidInterestSelectionException(String message, List<UUID> unknownIds) {
		super(message);
		this.unknownIds = unknownIds;
	}

	public List<UUID> getUnknownIds() {
		return unknownIds;
	}

}
