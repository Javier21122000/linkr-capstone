package com.linkr.backend.ticketmaster;

/** Parametro del dry-run fuori dai limiti consentiti (mappata in 400 da GlobalExceptionHandler). */
public class InvalidImportParameterException extends RuntimeException {

	public InvalidImportParameterException(String message) {
		super(message);
	}

}
