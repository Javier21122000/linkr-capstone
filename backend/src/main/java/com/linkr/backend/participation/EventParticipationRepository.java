package com.linkr.backend.participation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.linkr.backend.event.EventStatus;

public interface EventParticipationRepository extends JpaRepository<EventParticipation, UUID> {

	Optional<EventParticipation> findByUserIdAndEventId(UUID userId, UUID eventId);

	boolean existsByUserIdAndEventId(UUID userId, UUID eventId);

	/** Partecipazioni di un utente a eventi con uno degli stati richiesti, con l'evento caricato nella stessa query. */
	@Query("""
			select p from EventParticipation p join fetch p.event e
			where p.userId = :userId and e.status in (:eventStatuses)
			order by e.startsAt, e.title
			""")
	List<EventParticipation> findByUserWithEvent(@Param("userId") UUID userId,
			@Param("eventStatuses") Collection<EventStatus> eventStatuses);

	/**
	 * Crea o aggiorna la partecipazione in UN solo comando SQL atomico (INSERT ... ON CONFLICT DO UPDATE):
	 * due richieste simultanee non possono creare duplicati e nessuna delle due fallisce.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO event_participation (user_id, event_id, status, visible_to_others)
			VALUES (:userId, :eventId, :status, :visibleToOthers)
			ON CONFLICT (user_id, event_id)
			DO UPDATE SET status = EXCLUDED.status,
			              visible_to_others = EXCLUDED.visible_to_others,
			              updated_at = now()
			""", nativeQuery = true)
	void upsert(@Param("userId") UUID userId, @Param("eventId") UUID eventId, @Param("status") String status,
			@Param("visibleToOthers") boolean visibleToOthers);

	/** Cancella SOLO la riga dell'utente indicato per quell'evento. Restituisce quante righe ha cancellato (0 o 1). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from EventParticipation p where p.userId = :userId and p.eventId = :eventId")
	int deleteByUserAndEvent(@Param("userId") UUID userId, @Param("eventId") UUID eventId);

}
