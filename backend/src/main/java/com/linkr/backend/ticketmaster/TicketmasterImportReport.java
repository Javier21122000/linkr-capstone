package com.linkr.backend.ticketmaster;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Api;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.ApiError;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Counts;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Excluded;

/**
 * Esito di un'importazione. Come il report del dry-run non contiene mai la API Key, URL, immagini o testo grezzo della fonte:
 * solo conteggi e, per ogni evento esaminato, id Ticketmaster, titolo ripulito, esito e id dell'evento LINKR.
 *
 * Tornano i conti dei candidati: source.counts.validCandidates = created + updated + unchanged + keptLocalEdits + skippedDuplicates
 * + skippedIncomplete + (failed relativi ai candidati). sourceStatusRecorded riguarda eventi gia' importati letti tra gli esclusi
 * per stato, non i candidati; un suo eventuale errore e' un item FAILED con reason STATUS_NOT_RECORDED.
 */
public record TicketmasterImportReport(
		Status status,
		/** Sempre false: questo endpoint scrive (ma solo eventi collegati a Ticketmaster; vedi docs/TICKETMASTER.md). */
		boolean dryRun,
		String message,
		OffsetDateTime generatedAt,
		Params params,
		/** Cio' che e' stato letto da Ticketmaster (stessi conteggi del dry-run). */
		Source source,
		Result result,
		List<Item> items) {

	public enum Status {
		/** Importazione eseguita per intero. */
		OK,
		/** Qualche evento non e' stato scritto (vedi items con FAILED). */
		PARTIAL,
		/** Lettura da Ticketmaster non riuscita o incompleta: NON e' stato scritto nulla. */
		FAILED,
		/** Integrazione spenta (LINKR_TICKETMASTER_ENABLED): nessuna richiesta, nessuna scrittura. */
		DISABLED,
		/** Integrazione attiva ma senza API Key: nessuna richiesta, nessuna scrittura. */
		NOT_CONFIGURED,
		/** Dry-run possibile ma importazione spenta (LINKR_TICKETMASTER_IMPORT_ENABLED): nessuna richiesta, nessuna scrittura. */
		IMPORT_DISABLED
	}

	public record Params(int days, int maxCandidates) {
	}

	public record Source(Counts counts, Excluded excluded, Api api, List<ApiError> errors) {
	}

	public record Result(
			int created,
			int updated,
			int unchanged,
			/** Eventi che una persona ha modificato o annullato: non riscritti (e, se annullati, mai riattivati). */
			int keptLocalEdits,
			/** Duplicati evidenti di un altro candidato o di un evento LINKR esistente: non creati. */
			int skippedDuplicates,
			/** Candidati validi ma privi di un dato obbligatorio per LINKR (link all'annuncio, nome della sede). */
			int skippedIncomplete,
			int failed,
			/** Eventi gia' importati che Ticketmaster ora segnala annullati/rinviati/ripianificati: registrato lo stato, evento LINKR NON toccato. */
			int sourceStatusRecorded) {
	}

	public enum Action {
		CREATED, UPDATED, UNCHANGED, KEPT_LOCAL_EDITS, SKIPPED_DUPLICATE, SKIPPED_INCOMPLETE, FAILED, SOURCE_STATUS_RECORDED
	}

	public record Item(String externalId, String title, Action action, UUID eventId, String reason) {
	}

	static TicketmasterImportReport notRun(Status status, String message, OffsetDateTime generatedAt, Params params) {
		TicketmasterDryRunReport empty = TicketmasterDryRunReport.notRun(TicketmasterDryRunReport.Status.DISABLED, "", generatedAt, 0, 0, 0, 0);
		return new TicketmasterImportReport(status, false, message, generatedAt, params,
				new Source(empty.counts(), empty.excluded(), empty.api(), List.of()),
				new Result(0, 0, 0, 0, 0, 0, 0, 0), List.of());
	}

}
