package com.linkr.backend.interest;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestCategoryRepository extends JpaRepository<InterestCategory, UUID> {

	List<InterestCategory> findAllByOrderBySortOrderAsc();

}
