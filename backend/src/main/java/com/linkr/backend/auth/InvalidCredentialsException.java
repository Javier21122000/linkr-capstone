package com.linkr.backend.auth;

/** Email o password errate. Il messaggio e' volutamente identico nei due casi. Diventa una risposta 401. */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid email or password");
	}

}
