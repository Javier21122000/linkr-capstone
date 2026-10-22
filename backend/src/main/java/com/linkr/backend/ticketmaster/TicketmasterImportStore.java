package com.linkr.backend.ticketmaster;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Le uniche scritture dell'importazione Ticketmaster (tabelle event ed event_source). E' un'interfaccia perche' la logica
 * dell'importazione si prova con un'implementazione in memoria; l'implementazione vera e' {@link JdbcTicketmasterImportStore}.
 *
 * Regole che ogni implementazione deve rispettare (provate sul database da TicketmasterImportIntegrationTest):
 *  - si modificano solo eventi COLLEGATI a Ticketmaster tramite event_source e ancora "nostri" (OFFICIAL, non demo, senza creatore);
 *  - lo stato dell'evento, il tipo, il creatore, i flag demo e le immagini non vengono mai riscritti;
 *  - nulla viene mai cancellato.
 */
interface TicketmasterImportStore {

	/** Evento LINKR collegato a un annuncio Ticketmaster, con cio' che serve a decidere se si puo' aggiornare. */
	record Linked(
			UUID eventId,
			ImportedContent content,
			/** Stato dell'evento in LINKR (PUBLISHED, CANCELLED, DRAFT). */
			String status,
			String eventType,
			boolean demo,
			UUID createdBy,
			/** Ultima modifica dell'evento (qualunque autore: import o persona). */
			Instant eventUpdatedAt,
			/** Ultima volta in cui l'IMPORTAZIONE ha scritto il contenuto dell'evento. Se eventUpdatedAt e' successivo, qualcuno l'ha modificato. */
			Instant contentSyncedAt) {

		/** E' un evento creato dall'importazione e non adottato da altri (la creazione lo rende sempre cosi'). */
		boolean isImportedByUs() {
			return "OFFICIAL".equals(eventType) && !demo && createdBy == null;
		}

		/** Una persona l'ha modificato (o annullato) dopo l'ultima scrittura dell'importazione. */
		boolean locallyChanged() {
			return !"PUBLISHED".equals(status) || eventUpdatedAt.isAfter(contentSyncedAt);
		}

	}

	Optional<Linked> findLinked(String externalId);

	/** Crea l'evento (PUBLISHED, OFFICIAL, non demo, senza creatore, senza immagine) e il suo collegamento; entrambi o nessuno. */
	UUID insertNew(ImportedContent content, String externalId, String sourceUrl, String sourceStatus, Instant syncAt);

	/**
	 * Riscrive il contenuto dell'evento SOLO se e' ancora esattamente come lo si e' letto (updated_at invariato), PUBLISHED e nostro.
	 * Registra anche stato, link e momento della sincronizzazione. Restituisce false (senza scrivere nulla) se la condizione non vale piu'.
	 */
	boolean updateContent(Linked current, ImportedContent content, String sourceUrl, String sourceStatus, Instant syncAt);

	/**
	 * Aggiorna SOLO event_source (stato visto sulla fonte, link se noto, ultima volta visto), senza toccare l'evento ne' il momento di
	 * sincronizzazione del contenuto. Restituisce false se non esiste un collegamento per quell'id.
	 */
	boolean recordSeen(String externalId, String sourceUrl, String sourceStatus, Instant seenAt);

}
