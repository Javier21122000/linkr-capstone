package com.linkr.backend.ticketmaster;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lettura (SOLA lettura) degli eventi che LINKR ha gia', per segnalare i potenziali duplicati nel dry-run.
 * E' un'interfaccia: nei test unitari si sostituisce con un elenco in memoria.
 */
interface ExistingEventsSource {

	/** Un evento gia' presente in LINKR (demo, ufficiale o della community), con i soli campi utili al confronto. */
	record ExistingEvent(UUID id, String title, double latitude, double longitude, Instant startsAt) {
	}

	/** Quali di questi id di Ticketmaster sono gia' collegati a un evento (tabella event_source). */
	Set<String> importedExternalIds(Collection<String> externalIds);

	/** Eventi non in bozza che iniziano nell'intervallo, al massimo un numero fisso (protezione). */
	List<ExistingEvent> eventsStartingBetween(Instant from, Instant to);

}
