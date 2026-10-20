package com.linkr.backend.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	Optional<Event> findByIdAndStatus(UUID id, EventStatus status);

	/** Eventi creati da un utente, con gli stati richiesti, dal piu' recente per data di inizio. */
	List<Event> findByCreatedByAndStatusInOrderByStartsAtDescTitleAsc(UUID createdBy, Collection<EventStatus> statuses);

	/** Quanti eventi ha creato l'utente da un certo istante (limite anti-spam). */
	long countByCreatedByAndCreatedAtAfter(UUID createdBy, Instant since);

}
