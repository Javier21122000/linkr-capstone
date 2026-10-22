package com.linkr.backend.ticketmaster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Importazione da Ticketmaster, solo per gli ADMIN (la regola /api/admin/** e' in SecurityConfig; vedi AdminTicketmasterSecurityTest).
 * Per ora esiste solo il dry-run: analizza eventi reali e restituisce un report, senza scrivere nulla.
 */
@RestController
@RequestMapping("/api/admin/import/ticketmaster")
public class TicketmasterAdminController {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterAdminController.class);

	private final TicketmasterDryRunService dryRunService;

	public TicketmasterAdminController(TicketmasterDryRunService dryRunService) {
		this.dryRunService = dryRunService;
	}

	/**
	 * Esegue un dry-run. I parametri sono facoltativi e possono solo RESTRINGERE i limiti configurati
	 * (utile per una prima prova piccola): {@code days} (1..finestra) e {@code maxCandidates} (1..massimo).
	 * Risponde sempre 200 con il report (il campo "status" dice com'e' andata); 400 per parametri fuori limite; 409 se ne e' gia' in corso uno.
	 */
	@PostMapping("/dry-run")
	public ResponseEntity<TicketmasterDryRunReport> dryRun(
			@RequestParam(required = false) Integer days,
			@RequestParam(required = false) Integer maxCandidates,
			Authentication authentication) {
		// Nel log solo l'identificativo dell'admin (il "subject" del token): niente parametri della fonte, niente URL.
		log.info("Ticketmaster dry-run requested by admin {}", authentication == null ? "unknown" : authentication.getName());
		TicketmasterDryRunReport report = dryRunService.run(days, maxCandidates);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
	}

}
