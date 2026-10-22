package com.linkr.backend.ticketmaster;

import java.time.Duration;

/**
 * Errore della chiamata a Ticketmaster. Il messaggio e' SEMPRE un testo fisso scelto qui: non contiene mai l'URL (che porta la API Key),
 * il corpo della risposta o il testo dell'eccezione originale (le eccezioni di rete di Spring/JDK ripetono l'URL completo).
 * Per lo stesso motivo non si conserva la causa originale.
 */
public class TicketmasterException extends RuntimeException {

	public enum Kind {
		UNAUTHORIZED("Ticketmaster rejected the API key (HTTP 401)"),
		FORBIDDEN("Ticketmaster denied access for this API key (HTTP 403)"),
		RATE_LIMITED("Ticketmaster rate limit reached (HTTP 429)"),
		SERVER_ERROR("Ticketmaster answered with a server error (HTTP 5xx)"),
		CLIENT_ERROR("Ticketmaster rejected the request (HTTP 4xx)"),
		TIMEOUT("The request to Ticketmaster timed out"),
		NETWORK("The request to Ticketmaster failed (network error)"),
		MALFORMED_RESPONSE("Ticketmaster returned a response that is not valid JSON or not in the expected shape"),
		CALL_BUDGET_EXHAUSTED("The maximum number of requests allowed for one run was reached"),
		INTERRUPTED("The run was interrupted");

		private final String safeMessage;

		Kind(String safeMessage) {
			this.safeMessage = safeMessage;
		}

		public String safeMessage() {
			return safeMessage;
		}
	}

	private final Kind kind;
	private final Integer httpStatus;
	private final Duration retryAfter;
	private final int attempts;

	TicketmasterException(Kind kind, Integer httpStatus, Duration retryAfter) {
		this(kind, httpStatus, retryAfter, 1);
	}

	private TicketmasterException(Kind kind, Integer httpStatus, Duration retryAfter, int attempts) {
		super(kind.safeMessage());
		this.kind = kind;
		this.httpStatus = httpStatus;
		this.retryAfter = retryAfter;
		this.attempts = attempts;
	}

	TicketmasterException withAttempts(int attempts) {
		return new TicketmasterException(kind, httpStatus, retryAfter, attempts);
	}

	public Kind kind() {
		return kind;
	}

	/** Codice HTTP, se la fonte ha risposto. */
	public Integer httpStatus() {
		return httpStatus;
	}

	/** Attesa chiesta dalla fonte (429 con Retry-After), se presente. */
	public Duration retryAfter() {
		return retryAfter;
	}

	/** Tentativi fatti per questa richiesta (1 = nessun retry). */
	public int attempts() {
		return attempts;
	}

}
