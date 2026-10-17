package com.linkr.backend.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.linkr.backend.auth.DuplicateUserException;
import com.linkr.backend.auth.InvalidCredentialsException;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.user.UserNotFoundException;

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

	@ExceptionHandler(DuplicateUserException.class)
	public ProblemDetail handleDuplicateUser(DuplicateUserException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Conflict");
		problem.setProperty("field", ex.getField());
		return problem;
	}

	@ExceptionHandler({ InvalidCredentialsException.class, UserNotFoundException.class })
	public ProblemDetail handleUnauthorized(RuntimeException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, ex.getMessage());
		problem.setTitle("Unauthorized");
		return problem;
	}

	/** Dati di input non validi (400): elenca i campi sbagliati, senza mai ripetere i valori inviati (es. password). */
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (FieldError error : ex.getBindingResult().getFieldErrors()) {
			errors.putIfAbsent(error.getField(), error.getDefaultMessage());
		}
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, status, request);
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
