package com.linkr.backend.invite;

/**
 * Invito non utilizzabile: token alterato o scaduto, evento non piu' disponibile, mittente non visibile o coincidente con chi guarda.
 * Una sola risposta (404) per tutti i casi: non si puo' sapere perche' ne' sondare chi esiste o com'e' impostata la sua visibilita'.
 */
public class InviteNotFoundException extends RuntimeException {

	public InviteNotFoundException() {
		super("The invitation is not available.");
	}

}
