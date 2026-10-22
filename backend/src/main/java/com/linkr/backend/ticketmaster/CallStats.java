package com.linkr.backend.ticketmaster;

import java.time.Duration;

/**
 * Contatori di UNA esecuzione del dry-run (un'istanza per esecuzione, usata da un solo thread).
 * Il client li aggiorna, il servizio li copia nel report.
 */
final class CallStats {

	/** Richieste HTTP effettivamente inviate, retry compresi. */
	int calls;
	/** Richieste ripetute dopo un errore temporaneo. */
	int retries;
	/** Pagine ricevute e lette correttamente. */
	int pagesFetched;
	/** Risposte 429 ricevute. */
	int rateLimitedResponses;
	/** Attesa piu' lunga chiesta dalla fonte (Retry-After), se mai. */
	Duration longestRetryAfter;
	/** Quota giornaliera dichiarata dalla fonte negli header (Rate-Limit / Rate-Limit-Available), se presenti. */
	Integer quotaLimit;
	Integer quotaAvailable;

}
