package com.linkr.backend.user;

/** Token valido ma utente non piu' esistente. Diventa una risposta 401. */
public class UserNotFoundException extends RuntimeException {

	public UserNotFoundException() {
		super("User not found");
	}

}
