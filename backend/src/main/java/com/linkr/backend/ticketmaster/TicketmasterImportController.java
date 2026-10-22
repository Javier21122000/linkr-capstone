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
 * Importazione manuale da Ticketmaster, solo per gli ADMIN (la regola /api/admin/** e' in SecurityConfig; vedi TicketmasterImportSecurityTest).
 * Nessuno scheduler: gira soltanto quando un admin chiama questo endpoint, e solo con LINKR_TICKETMASTER_IMPORT_ENABLED=true.
 */
@RestController
@RequestMapping("/api/admin/import/ticketmaster")
public class TicketmasterImportController {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterImportController.class);

	private final TicketmasterImportService importService;

	public TicketmasterImportController(TicketmasterImportService importService) {
		this.importService = importService;
	}

	/**
	 * Importa gli eventi validi (idempotente: rilanciarla non crea doppioni). I parametri sono facoltativi e solo conservativi:
	 * {@code days} (1..finestra, di base 30) e {@code maxCandidates} (1..50, di base 20).
	 * Risponde sempre 200 con il report (il campo "status" dice com'e' andata); 400 per parametri fuori limite; 409 se ne e' in corso una.
	 */
	@PostMapping
	public ResponseEntity<TicketmasterImportReport> importEvents(
			@RequestParam(required = false) Integer days,
			@RequestParam(required = false) Integer maxCandidates,
			Authentication authentication) {
		log.info("Ticketmaster import requested by admin {}", authentication == null ? "unknown" : authentication.getName());
		TicketmasterImportReport report = importService.run(days, maxCandidates);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(report);
	}

}
