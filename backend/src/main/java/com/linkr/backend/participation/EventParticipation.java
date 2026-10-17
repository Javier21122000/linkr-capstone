package com.linkr.backend.participation;

import java.time.Instant;
import java.util.UUID;

import com.linkr.backend.event.Event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Partecipazione di un utente a un evento (tabella event_participation, vedi V4).
 * E' un'entita' di sola lettura: le scritture passano dalla query "upsert" del repository,
 * che e' atomica. Non va mai restituita direttamente dalle API (si usano i DTO).
 */
@Entity
@Table(name = "event_participation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EventParticipation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	/**
	 * Lo stesso campo event_id, esposto come relazione solo in lettura (LAZY, senza lato inverso):
	 * serve a caricare l'evento con un unico JOIN FETCH nella lista, senza N+1 query.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "event_id", insertable = false, updatable = false)
	private Event event;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ParticipationStatus status;

	@Column(nullable = false)
	private boolean visibleToOthers;

	@Column(nullable = false, insertable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, insertable = false, updatable = false)
	private Instant updatedAt;

}
