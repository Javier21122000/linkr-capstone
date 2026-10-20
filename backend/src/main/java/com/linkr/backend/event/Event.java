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
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.linkr.backend.event.dto.EventRequest;
import com.linkr.backend.user.User;

/**
 * Evento di LINKR. Entita' JPA collegata alla tabella "event" (vedi V1 e V6).
 * Si modifica solo con create(), update() e cancel(): niente setter. Non va mai restituita direttamente dalle API (si usano i DTO).
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

	/** OFFICIAL (curato da LINKR, demo inclusi) o COMMUNITY (creato da un utente). Colonna event_type (V6). */
	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 20)
	private EventType type;

	/** Utente che ha creato l'evento; null per gli eventi demo/ufficiali storici o se l'utente e' stato eliminato. */
	@Column(name = "created_by")
	private UUID createdBy;

	/** Lo stesso created_by come relazione in sola lettura (LAZY): serve solo a mostrare il creatore nel dettaglio. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by", insertable = false, updatable = false)
	private User creator;

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
			boolean demo, EventType type, UUID createdBy) {
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
		this.type = type != null ? type : EventType.OFFICIAL;
		this.createdBy = createdBy;
	}

	/**
	 * Nuovo evento creato da un utente: sempre PUBLISHED (niente bozze) e mai demo.
	 * Le regole di validazione tra campi sono gia' state verificate da EventManagementService.
	 */
	public static Event create(UUID creatorId, EventType type, EventRequest request) {
		Event event = new Event();
		event.status = EventStatus.PUBLISHED;
		event.demo = false;
		event.createdBy = creatorId;
		event.type = type;
		event.apply(request);
		return event;
	}

	/** Sostituisce tutti i contenuti modificabili. Stato, creatore e demo non cambiano mai da qui. */
	public void update(EventRequest request, EventType newType) {
		apply(request);
		if (newType != null) {
			this.type = newType;
		}
	}

	/** Annullamento logico: l'evento non si cancella, cosi' i partecipanti lo riconoscono ancora. */
	public void cancel() {
		this.status = EventStatus.CANCELLED;
	}

	private void apply(EventRequest r) {
		this.title = r.title().trim();
		this.description = r.description().trim();
		this.category = r.category();
		this.startsAt = r.startsAt().toInstant();
		this.endsAt = r.endsAt() == null ? null : r.endsAt().toInstant();
		this.venueName = r.venueName().trim();
		this.address = r.address().trim();
		this.neighbourhood = r.neighbourhood().trim();
		this.latitude = r.latitude();
		this.longitude = r.longitude();
		boolean hasImage = r.imageUrl() != null && !r.imageUrl().isBlank();
		this.imageUrl = hasImage ? r.imageUrl().trim() : null;
		this.imageAlt = hasImage && r.imageAlt() != null ? r.imageAlt().trim() : null;
		this.imageCredit = hasImage && r.imageCredit() != null && !r.imageCredit().isBlank() ? r.imageCredit().trim() : null;
	}

}
