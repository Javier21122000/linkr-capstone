package com.linkr.backend.upload;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Limite di upload per utente (finestre scorrevoli di 1 ora e 24 ore), IN MEMORIA: va bene per una sola istanza e per il
 * Capstone, ma si azzera a ogni riavvio. Conta ogni tentativo ammesso a elaborazione, anche se poi il file e' rifiutato,
 * perche' leggere e analizzare file da 5 MB ha un costo.
 */
@Component
public class UploadRateLimiter {

	private final Map<UUID, Deque<Instant>> attempts = new ConcurrentHashMap<>();
	private final UploadProperties properties;
	private final Clock clock;

	public UploadRateLimiter(UploadProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** true = tentativo registrato e consentito; false = limite superato (non si registra nulla). */
	boolean tryAcquire(UUID userId) {
		Instant now = clock.instant();
		Instant dayAgo = now.minus(Duration.ofHours(24));
		Instant hourAgo = now.minus(Duration.ofHours(1));
		Deque<Instant> userAttempts = attempts.computeIfAbsent(userId, id -> new ArrayDeque<>());
		synchronized (userAttempts) {
			while (!userAttempts.isEmpty() && userAttempts.peekFirst().isBefore(dayAgo)) {
				userAttempts.pollFirst();
			}
			long lastHour = userAttempts.stream().filter(t -> !t.isBefore(hourAgo)).count();
			if (userAttempts.size() >= properties.maxPerDay() || lastHour >= properties.maxPerHour()) {
				return false;
			}
			userAttempts.addLast(now);
			return true;
		}
	}

}
