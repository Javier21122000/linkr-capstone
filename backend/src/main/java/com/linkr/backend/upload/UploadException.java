package com.linkr.backend.upload;

import org.springframework.http.HttpStatus;

/**
 * Errori di dominio degli upload. Ogni caso ha il suo codice HTTP e un titolo fisso; i messaggi sono generici:
 * non ripetono mai il nome del file ne' il suo contenuto.
 */
public class UploadException extends RuntimeException {

	private final HttpStatus status;
	private final String title;

	private UploadException(HttpStatus status, String title, String message) {
		super(message);
		this.status = status;
		this.title = title;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getTitle() {
		return title;
	}

	/** Nessun file, o file vuoto (400). */
	static UploadException empty() {
		return new UploadException(HttpStatus.BAD_REQUEST, "Invalid upload", "A non-empty file is required in the 'file' field.");
	}

	/** File oltre il limite (413). */
	static UploadException tooLarge() {
		return new UploadException(HttpStatus.CONTENT_TOO_LARGE, "Upload too large",
				"The image must be at most " + (EventImageService.MAX_SIZE_BYTES / (1024 * 1024)) + " MB.");
	}

	/** Contenuto non riconosciuto come JPEG, PNG o WebP, oppure MIME dichiarato diverso dal contenuto (415). */
	static UploadException unsupported() {
		return new UploadException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported image",
				"Only JPEG, PNG and WebP images are accepted.");
	}

	/** Formato riconosciuto ma file danneggiato, troncato, animato o con dimensioni eccessive (400). */
	static UploadException malformed() {
		return new UploadException(HttpStatus.BAD_REQUEST, "Invalid image", "The file is not a valid, supported JPEG, PNG or WebP image.");
	}

	/** Limiti per utente superati (429). */
	static UploadException tooMany() {
		return new UploadException(HttpStatus.TOO_MANY_REQUESTS, "Too many uploads",
				"You have reached the limit of image uploads. Try again later.");
	}

	/** Immagine inesistente o nome non valido: stessa risposta per entrambi i casi (404). */
	static UploadException notFound() {
		return new UploadException(HttpStatus.NOT_FOUND, "Image not found", "The image does not exist.");
	}

	/** Errore del disco: al client non si dice altro (500). */
	static UploadException storageFailure() {
		return new UploadException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed", "The image could not be saved. Try again later.");
	}

}
