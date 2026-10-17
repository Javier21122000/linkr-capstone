package com.linkr.backend.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Configurazione JWT (prefisso linkr.jwt). Il segreto arriva dall'ambiente (LINKR_JWT_SECRET), mai dal codice. */
@Validated
@ConfigurationProperties(prefix = "linkr.jwt")
public record JwtProperties(@NotBlank String secret, @NotBlank String issuer, @NotNull Duration expiration) {
}
