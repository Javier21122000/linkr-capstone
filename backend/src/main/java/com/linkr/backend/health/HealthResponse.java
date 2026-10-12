package com.linkr.backend.health;

/**
 * Corpo JSON restituito da GET /api/health.
 * Un record e' una classe immutabile: Jackson la converte in JSON usando i nomi dei campi.
 */
public record HealthResponse(String status, String application, String service) {
}
