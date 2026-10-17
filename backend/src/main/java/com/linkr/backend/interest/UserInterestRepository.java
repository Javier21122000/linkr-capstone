package com.linkr.backend.interest;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserInterestRepository extends JpaRepository<UserInterest, UserInterestId> {

	/** Cancella la selezione precedente di UN utente (eseguito subito, prima dei nuovi inserimenti). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserInterest ui where ui.id.userId = :userId")
	void deleteAllByUserId(@Param("userId") UUID userId);

}
