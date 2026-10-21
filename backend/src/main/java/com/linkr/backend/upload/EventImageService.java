package com.linkr.backend.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.linkr.backend.upload.ImageInspector.ImageInfo;
import com.linkr.backend.upload.dto.UploadedImageResponse;

/**
 * Upload delle immagini di copertina degli eventi. Ordine dei controlli:
 *   file presente e non vuoto (400) -> limite per utente (429) -> dimensione (413) -> formato REALE dal contenuto
 *   (415 se non e' JPEG/PNG/WebP, 400 se e' rotto o troppo grande in pixel) -> MIME dichiarato coerente (415)
 *   -> rimozione dei metadati incorporati (ImageMetadataSanitizer) -> salvataggio.
 * Si salva SEMPRE e SOLO la versione ripulita (niente GPS, data, modello del telefono, miniature, XMP): l'originale non viene mai scritto
 * su disco ne' pubblicato. Il limite di 5 MB vale per il file ricevuto; "size" nella risposta e' quello del file salvato.
 * Il nome originale del file non viene mai usato ne' registrato; nei log non finisce nessun contenuto.
 */
@Service
public class EventImageService {

	/** 5 MiB. Lo stesso limite e' impostato anche nel parser multipart (spring.servlet.multipart): questo e' il controllo di riserva. */
	public static final int MAX_SIZE_BYTES = 5 * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(EventImageService.class);

	private final EventImageStorage storage;
	private final UploadRateLimiter rateLimiter;

	public EventImageService(EventImageStorage storage, UploadRateLimiter rateLimiter) {
		this.storage = storage;
		this.rateLimiter = rateLimiter;
	}

	public UploadedImageResponse upload(UUID userId, MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw UploadException.empty();
		}
		if (!rateLimiter.tryAcquire(userId)) {
			throw UploadException.tooMany();
		}
		if (file.getSize() > MAX_SIZE_BYTES) {
			throw UploadException.tooLarge();
		}

		byte[] content = read(file);
		if (content.length == 0) {
			throw UploadException.empty();
		}
		if (content.length > MAX_SIZE_BYTES) {
			throw UploadException.tooLarge();
		}

		ImageInfo info = ImageInspector.inspect(content);
		String declared = declaredType(file.getContentType());
		if (declared != null && !declared.equals(info.type().contentType())) {
			throw UploadException.unsupported();         // MIME falso: dice una cosa, i byte un'altra
		}

		byte[] clean = ImageMetadataSanitizer.sanitize(content, info);
		String filename = storage.store(clean, info.type());
		log.info("Event image stored: type={} size={} bytes (received {} bytes)", info.type(), clean.length, content.length);
		return new UploadedImageResponse(EventImageStorage.PUBLIC_PATH + filename, filename, info.type().contentType(), clean.length);
	}

	/** Legge al massimo un byte oltre il limite: basta per capire che e' troppo grande senza caricare tutto in memoria. */
	private byte[] read(MultipartFile file) {
		try (InputStream in = file.getInputStream()) {
			return in.readNBytes(MAX_SIZE_BYTES + 1);
		}
		catch (IOException e) {
			log.warn("Could not read an uploaded file", e);
			throw UploadException.storageFailure();
		}
	}

	/** Content-Type dichiarato, senza parametri e in minuscolo; null se il client non l'ha indicato. */
	private String declaredType(String contentType) {
		if (contentType == null || contentType.isBlank()) {
			return null;
		}
		int semicolon = contentType.indexOf(';');
		return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(Locale.ROOT);
	}

}
