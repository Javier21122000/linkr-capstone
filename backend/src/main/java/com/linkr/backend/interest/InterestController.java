package com.linkr.backend.interest;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.interest.dto.InterestCategoryResponse;

/** Catalogo pubblico degli interessi. */
@RestController
@RequestMapping("/api/interests")
public class InterestController {

	private final InterestService interestService;

	public InterestController(InterestService interestService) {
		this.interestService = interestService;
	}

	@GetMapping
	public List<InterestCategoryResponse> catalog() {
		return interestService.getCatalog();
	}

}
