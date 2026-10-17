package com.linkr.backend.auth;

/** Email o username gia' in uso. Diventa una risposta 409. */
public class DuplicateUserException extends RuntimeException {

	private final String field;

	public DuplicateUserException(String field) {
		super(field.substring(0, 1).toUpperCase() + field.substring(1) + " is already in use");
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
