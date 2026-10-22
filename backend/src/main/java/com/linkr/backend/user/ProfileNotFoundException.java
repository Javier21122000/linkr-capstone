package com.linkr.backend.user;

/** Profilo inesistente OPPURE non visibile a chi lo chiede: stessa risposta (404), cosi' non si scopre chi esiste. */
public class ProfileNotFoundException extends RuntimeException {

	public ProfileNotFoundException() {
		super("Profile not found");
	}

}
