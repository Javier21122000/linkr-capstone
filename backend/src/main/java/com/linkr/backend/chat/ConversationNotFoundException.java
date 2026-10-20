package com.linkr.backend.chat;

/**
 * Conversazione inesistente OPPURE non accessibile all'utente (richiesta Link altrui, ancora in attesa o rifiutata):
 * stessa risposta (404) per tutti i casi, cosi' non si scopre nulla su rapporti altrui e un rifiuto non viene rivelato.
 */
public class ConversationNotFoundException extends RuntimeException {

	public ConversationNotFoundException() {
		super("The conversation does not exist.");
	}

}
