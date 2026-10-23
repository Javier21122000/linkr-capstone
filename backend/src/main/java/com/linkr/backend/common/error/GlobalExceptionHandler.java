package com.linkr.backend.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.linkr.backend.auth.DuplicateUserException;
import com.linkr.backend.auth.InvalidCredentialsException;
import com.linkr.backend.chat.ConversationNotFoundException;
import com.linkr.backend.event.EventForbiddenException;
import com.linkr.backend.event.EventLimitExceededException;
import com.linkr.backend.event.EventNotEditableException;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.InvalidEventException;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.interest.InvalidInterestSelectionException;
import com.linkr.backend.invite.InviteNotFoundException;
import com.linkr.backend.linkrequest.LinkRequestException;
import com.linkr.backend.participation.EventEndedException;
import com.linkr.backend.participation.ParticipationNotFoundException;
import com.linkr.backend.people.DiscoveryRequiredException;
import com.linkr.backend.people.ParticipationRequiredException;
import com.linkr.backend.ticketmaster.ImportAlreadyRunningException;
import com.linkr.backend.ticketmaster.InvalidImportParameterException;
import com.linkr.backend.upload.UploadException;
import com.linkr.backend.user.InvalidProfileException;
import com.linkr.backend.user.ProfileNotFoundException;
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

	@ExceptionHandler(EventForbiddenException.class)
	public ProblemDetail handleEventForbidden(EventForbiddenException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle("Forbidden");
		return problem;
	}

	@ExceptionHandler(EventNotEditableException.class)
	public ProblemDetail handleEventNotEditable(EventNotEditableException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Event cannot be changed");
		return problem;
	}

	@ExceptionHandler(EventLimitExceededException.class)
	public ProblemDetail handleEventLimit(EventLimitExceededException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
		problem.setTitle("Too many events");
		return problem;
	}

	/** Regole tra piu' campi: stesso formato della validazione dei DTO (title "Validation failed" e mappa "errors"). */
	@ExceptionHandler(InvalidEventException.class)
	public ProblemDetail handleInvalidEvent(InvalidEventException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", ex.getErrors());
		return problem;
	}

	@ExceptionHandler(InvalidProfileException.class)
	public ProblemDetail handleInvalidProfile(InvalidProfileException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request content.");
		problem.setTitle("Validation failed");
		problem.setProperty("errors", ex.getErrors());
		return problem;
	}

	@ExceptionHandler(ProfileNotFoundException.class)
	public ProblemDetail handleProfileNotFound(ProfileNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Profile not found");
		return problem;
	}

	@ExceptionHandler(ParticipationNotFoundException.class)
	public ProblemDetail handleParticipationNotFound(ParticipationNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Participation not found");
		return problem;
	}

	/** Errori delle richieste Link: codice e titolo li decide l'eccezione, con messaggi volutamente generici. */
	@ExceptionHandler(LinkRequestException.class)
	public ProblemDetail handleLinkRequest(LinkRequestException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
		problem.setTitle(ex.getTitle());
		return problem;
	}

	/** Invito non utilizzabile: un solo 404, senza dire se e' alterato, scaduto, privato o dell'utente stesso. */
	@ExceptionHandler(InviteNotFoundException.class)
	public ProblemDetail handleInviteNotFound(InviteNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Invitation not available");
		return problem;
	}

	@ExceptionHandler(ParticipationRequiredException.class)
	public ProblemDetail handleParticipationRequired(ParticipationRequiredException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle("Participation required");
		return problem;
	}

	/** Errori degli upload: codice e titolo li decide l'eccezione, con messaggi che non ripetono mai il nome o il contenuto del file. */
	@ExceptionHandler(UploadException.class)
	public ProblemDetail handleUpload(UploadException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
		problem.setTitle(ex.getTitle());
		return problem;
	}

	/** Richiesta multipart troppo grande: rifiutata dal parser PRIMA di arrivare al controller (limiti in application.properties). */
	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, "The upload is too large.");
		problem.setTitle("Upload too large");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
	}

	/** Richiesta che non e' un multipart valido (es. corpo JSON, parti illeggibili): 400 e non un errore interno. */
	@ExceptionHandler(MultipartException.class)
	public ProblemDetail handleMultipart(MultipartException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The request must be a valid multipart/form-data upload.");
		problem.setTitle("Invalid upload");
		return problem;
	}

	/** Parametri del dry-run Ticketmaster fuori dai limiti configurati. */
	@ExceptionHandler(InvalidImportParameterException.class)
	public ProblemDetail handleInvalidImportParameter(InvalidImportParameterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid parameters");
		return problem;
	}

	/** Un dry-run Ticketmaster e' gia' in corso: non se ne avviano due insieme. */
	@ExceptionHandler(ImportAlreadyRunningException.class)
	public ProblemDetail handleImportAlreadyRunning(ImportAlreadyRunningException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Import already running");
		return problem;
	}

	@ExceptionHandler(ConversationNotFoundException.class)
	public ProblemDetail handleConversationNotFound(ConversationNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Conversation not found");
		return problem;
	}

	@ExceptionHandler(DiscoveryRequiredException.class)
	public ProblemDetail handleDiscoveryRequired(DiscoveryRequiredException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
		problem.setTitle("Discovery required");
		return problem;
	}

	@ExceptionHandler(EventEndedException.class)
	public ProblemDetail handleEventEnded(EventEndedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setTitle("Event has ended");
		return problem;
	}

	/** Violazione di un vincolo del database (es. dati cancellati nel frattempo): 409 senza dettagli SQL. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"The request conflicts with the current state of the data.");
		problem.setTitle("Conflict");
		return problem;
	}

	@ExceptionHandler(InvalidEventFilterException.class)
	public ProblemDetail handleInvalidFilter(InvalidEventFilterException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid filters");
		return problem;
	}

	@ExceptionHandler(InvalidInterestSelectionException.class)
	public ProblemDetail handleInvalidInterests(InvalidInterestSelectionException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		problem.setTitle("Invalid interests");
		if (!ex.getUnknownIds().isEmpty()) {
			problem.setProperty("unknownIds", ex.getUnknownIds());
		}
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
