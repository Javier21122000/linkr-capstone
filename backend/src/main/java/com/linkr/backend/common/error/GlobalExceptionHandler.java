package com.linkr.backend.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.InvalidEventFilterException;

/**
 * Trasforma le eccezioni in risposte JSON standard (ProblemDetail, RFC 9457).
 * Estendendo ResponseEntityExceptionHandler, anche gli errori di Spring MVC
 * (parametro non valido, rotta inesistente, metodo non consentito...) usano lo stesso formato.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(EventNotFoundException.class)
	public ProblemDetail handleEventNotFound(EventNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Event not found");
		return problem;
	}

	@ExceptionHandler(InvalidEventFilterException.class)
	public ProblemDetail handleInvalidFilter(InvalidEventFilterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid filters");
		return problem;
	}

	/** Qualsiasi errore imprevisto: si registra nel log, ma al client non si mostra nulla di interno. */
	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
		problem.setTitle("Internal server error");
		return problem;
	}

}
