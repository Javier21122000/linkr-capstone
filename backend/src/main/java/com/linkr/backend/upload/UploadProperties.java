package com.linkr.backend.upload;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Configurazione degli upload (prefisso linkr.uploads).
 *  - dir: cartella ESTERNA al repository dove si salvano i file (variabile d'ambiente LINKR_UPLOAD_DIR; vedi application.properties);
 *  - maxPerHour / maxPerDay: upload che un utente puo' tentare, in memoria (si azzerano al riavvio).
 */
@Validated
@ConfigurationProperties(prefix = "linkr.uploads")
public record UploadProperties(
		@NotNull Path dir,
		@DefaultValue("20") @Min(1) int maxPerHour,
		@DefaultValue("60") @Min(1) int maxPerDay) {
}
