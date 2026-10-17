package com.linkr.backend.interest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.interest.dto.InterestCategoryResponse;
import com.linkr.backend.interest.dto.InterestResponse;

@Service
@Transactional(readOnly = true)
public class InterestService {

	private final InterestCategoryRepository categoryRepository;
	private final InterestRepository interestRepository;

	public InterestService(InterestCategoryRepository categoryRepository, InterestRepository interestRepository) {
		this.categoryRepository = categoryRepository;
		this.interestRepository = interestRepository;
	}

	/** Catalogo completo: 2 query (categorie e interessi gia' ordinati), raggruppate in memoria. */
	public List<InterestCategoryResponse> getCatalog() {
		Map<UUID, List<InterestResponse>> byCategory = new LinkedHashMap<>();
		for (Interest interest : interestRepository.findCatalog()) {
			byCategory.computeIfAbsent(interest.getCategory().getId(), id -> new ArrayList<>())
					.add(new InterestResponse(interest.getId(), interest.getSlug(), interest.getName(),
							interest.getSortOrder()));
		}
		return categoryRepository.findAllByOrderBySortOrderAsc().stream()
				.map(c -> new InterestCategoryResponse(c.getId(), c.getSlug(), c.getName(), c.getSortOrder(),
						byCategory.getOrDefault(c.getId(), List.of())))
				.toList();
	}

}
