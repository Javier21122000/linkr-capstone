package com.linkr.backend.ticketmaster;

/** Un altro dry-run e' ancora in corso: non se ne avviano due insieme, per non raddoppiare le richieste a Ticketmaster (mappata in 409). */
public class ImportAlreadyRunningException extends RuntimeException {

	public ImportAlreadyRunningException() {
		super("A Ticketmaster dry-run is already running. Wait for it to finish and try again.");
	}

}
