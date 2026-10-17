package com.linkr.backend.interest;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterestRepository extends JpaRepository<Interest, UUID> {

	/** Tutto il catalogo in una query, gia' ordinato per categoria e poi per interesse. */
	@Query("select i from Interest i join fetch i.category c order by c.sortOrder, i.sortOrder")
	List<Interest> findCatalog();

	/** Interessi di un utente, con la categoria, nello stesso ordine del catalogo. */
	@Query("""
			select i from Interest i join fetch i.category c
			where i.id in (select ui.id.interestId from UserInterest ui where ui.id.userId = :userId)
			order by c.sortOrder, i.sortOrder
			""")
	List<Interest> findByUserId(@Param("userId") UUID userId);

	@Query("select i.id from Interest i where i.id in :ids")
	List<UUID> findExistingIds(@Param("ids") Collection<UUID> ids);

}
