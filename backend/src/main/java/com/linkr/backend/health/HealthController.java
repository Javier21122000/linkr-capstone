package com.linkr.backend.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint semplice per verificare che il backend sia in esecuzione.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

	@GetMapping
	public HealthResponse health() {
		return new HealthResponse("UP", "LINKR", "backend");
	}

}
