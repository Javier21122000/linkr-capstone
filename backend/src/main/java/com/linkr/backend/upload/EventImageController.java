package com.linkr.backend.upload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.linkr.backend.upload.dto.UploadedImageResponse;

/**
 * Immagini di copertina degli eventi.
 *  - POST /api/uploads/event-images (multipart, campo "file"): richiede il JWT (vedi SecurityConfig, "anyRequest").
 *  - GET  /api/uploads/event-images/{filename}: pubblico, sola lettura, SOLO per i file caricati da qui (nome generato dal server).
 *
 * La lettura passa da questo controller e non da un gestore di file statici: cosi' e' impossibile leggere altro,
 * non esiste l'elenco della cartella, e ogni risposta ha Content-Type fisso e intestazioni di sicurezza:
 *  - X-Content-Type-Options: nosniff (il browser non reinterpreta il contenuto);
 *  - Content-Security-Policy: default-src 'none'; sandbox (se aperta direttamente non puo' eseguire nulla);
 *  - Cross-Origin-Resource-Policy: cross-origin (un frontend su un altro dominio puo' mostrarla in un <img>);
 *  - Cache-Control lungo e "immutable": un file con quel nome non cambia mai.
 */
@RestController
@RequestMapping("/api/uploads/event-images")
public class EventImageController {

	private final EventImageService service;
	private final EventImageStorage storage;

	public EventImageController(EventImageService service, EventImageStorage storage) {
		this.service = service;
		this.storage = storage;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public UploadedImageResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
		return service.upload(UUID.fromString(jwt.getSubject()), file);
	}

	@GetMapping("/{filename}")
	public ResponseEntity<Resource> read(@PathVariable String filename) throws IOException {
		Path file = storage.find(filename).orElseThrow(UploadException::notFound);
		// il tipo si ricava dall'estensione che abbiamo generato noi (l'unica ammessa dal nome), mai dal contenuto o dal client
		ImageType type = ImageType.fromExtension(filename.substring(filename.lastIndexOf('.') + 1))
				.orElseThrow(UploadException::notFound);
		return ResponseEntity.ok()
				.contentType(type.mediaType())
				.contentLength(Files.size(file))
				.cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
				.header("X-Content-Type-Options", "nosniff")
				.header("Content-Security-Policy", "default-src 'none'; sandbox")
				.header("Cross-Origin-Resource-Policy", "cross-origin")
				.header("Content-Disposition", "inline")
				.body(new FileSystemResource(file));
	}

}
