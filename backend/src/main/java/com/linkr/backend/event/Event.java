package com.linkr.backend.event;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Evento di LINKR. Entita' JPA collegata alla tabella "event" (vedi V1__create_event_table.sql).
 * Per ora e' di sola lettura: niente setter. Non va mai restituita direttamente dalle API (si usano i DTO).
 */
@Entity
@Table(name = "event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // richiesto da JPA
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 120)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private EventCategory category;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private EventStatus status;

	/** Inizio, in UTC. */
	@Column(nullable = false)
	private Instant startsAt;

	/** Fine, in UTC. Puo' mancare (vedi EventService.DEFAULT_DURATION). */
	private Instant endsAt;

	@Column(nullable = false, length = 120)
	private String venueName;

	@Column(nullable = false, length = 200)
	private String address;

	@Column(nullable = false, length = 60)
	private String neighbourhood;

	@Column(nullable = false)
	private double latitude;

	@Column(nullable = false)
	private double longitude;

	@Column(length = 500)
	private String imageUrl;

	@Column(length = 200)
	private String imageAlt;

	@Column(length = 120)
	private String imageCredit;

	/** true = evento dimostrativo, non un evento reale. */
	@Column(nullable = false)
	private boolean demo;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	// Costruttore usato solo dai test (stesso package) per creare eventi.
	@Builder(access = AccessLevel.PACKAGE)
	Event(UUID id, String title, String description, EventCategory category, EventStatus status,
			Instant startsAt, Instant endsAt, String venueName, String address, String neighbourhood,
			double latitude, double longitude, String imageUrl, String imageAlt, String imageCredit,
			boolean demo) {
		this.id = id;
		this.title = title;
		this.description = description;
		this.category = category;
		this.status = status;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.venueName = venueName;
		this.address = address;
		this.neighbourhood = neighbourhood;
		this.latitude = latitude;
		this.longitude = longitude;
		this.imageUrl = imageUrl;
		this.imageAlt = imageAlt;
		this.imageCredit = imageCredit;
		this.demo = demo;
	}

}
