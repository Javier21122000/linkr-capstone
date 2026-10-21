package com.linkr.backend.upload;

import org.springframework.http.MediaType;

/** Formati accettati. Estensione e Content-Type sono SEMPRE quelli canonici qui, mai quelli scelti dal client. */
public enum ImageType {

	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
	WEBP("image/webp", "webp");

	private final String contentType;
	private final String extension;

	ImageType(String contentType, String extension) {
		this.contentType = contentType;
		this.extension = extension;
	}

	public String contentType() {
		return contentType;
	}

	public String extension() {
		return extension;
	}

	public MediaType mediaType() {
		return MediaType.parseMediaType(contentType);
	}

	/** Tipo dall'estensione del nome generato da noi (vedi EventImageStorage); vuoto per qualsiasi altra estensione. */
	static java.util.Optional<ImageType> fromExtension(String extension) {
		for (ImageType type : values()) {
			if (type.extension.equals(extension)) {
				return java.util.Optional.of(type);
			}
		}
		return java.util.Optional.empty();
	}

}
