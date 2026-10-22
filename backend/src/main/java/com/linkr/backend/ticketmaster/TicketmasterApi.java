package com.linkr.backend.ticketmaster;

import java.time.Instant;

import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;

/**
 * Accesso in sola lettura alla Discovery API. E' un'interfaccia perche' nei test si sostituisce con un client finto:
 * i test non devono mai arrivare a Ticketmaster.
 */
interface TicketmasterApi {

	/** Parametri di una ricerca: finestra temporale (inizio evento tra from e to) e dimensione pagina. */
	record EventSearch(Instant from, Instant to, int pageSize) {
	}

	/**
	 * Legge una pagina (numero base 0) di eventi. Aggiorna i contatori di {@code stats}.
	 *
	 * @throws TicketmasterException per qualunque errore (messaggio sempre privo di URL e credenziali)
	 */
	EventsPage fetchEvents(EventSearch search, int page, CallStats stats);

}
