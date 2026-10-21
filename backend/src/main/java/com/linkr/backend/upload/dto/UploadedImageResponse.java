package com.linkr.backend.upload.dto;

/**
 * Immagine caricata. imageUrl e' un percorso relativo alla radice del sito ("/api/uploads/event-images/<uuid>.<ext>"):
 * va copiato cosi' com'e' nel campo imageUrl dell'evento. contentType e' quello REALE rilevato dal contenuto, size in byte.
 */
public record UploadedImageResponse(String imageUrl, String filename, String contentType, long size) {
}
