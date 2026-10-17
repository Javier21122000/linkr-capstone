package com.linkr.backend.linkrequest;

/**
 * Stato del Link tra l'utente autenticato e un'altra persona, dal PUNTO DI VISTA di chi guarda (usato dalla discovery).
 * Privacy del rifiuto: se la richiesta e' stata rifiutata, il MITTENTE continua a vedere OUTGOING_PENDING.
 * Il destinatario che ha rifiutato vede INCOMING_DECLINED (cosi' la UI non gli ripropone "Invia Link": comunque
 * il database non permette una seconda richiesta tra le stesse due persone).
 */
public enum LinkStatus {
	NONE,
	OUTGOING_PENDING,
	INCOMING_PENDING,
	INCOMING_DECLINED,
	ACCEPTED;

	static LinkStatus of(boolean outgoing, LinkRequestStatus stored) {
		return switch (stored) {
			case ACCEPTED -> ACCEPTED;
			case PENDING -> outgoing ? OUTGOING_PENDING : INCOMING_PENDING;
			case DECLINED -> outgoing ? OUTGOING_PENDING : INCOMING_DECLINED; // il mittente non scopre il rifiuto
		};
	}
}
