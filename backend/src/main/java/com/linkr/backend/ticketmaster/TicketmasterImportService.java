package com.linkr.backend.ticketmaster;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.linkr.backend.config.TimeConfig;
import com.linkr.backend.ticketmaster.ExistingEventsSource.ExistingEvent;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.ApiError;
import com.linkr.backend.ticketmaster.TicketmasterDryRunService.Analysis;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Action;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Item;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Params;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Result;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Source;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Status;
import com.linkr.backend.ticketmaster.TicketmasterImportStore.Linked;

/**
 * Importazione manuale degli eventi Ticketmaster in LINKR. Parte SOLO dalla chiamata dell'admin: nessuno scheduler.
 *
 * Riusa il client e il normalizzatore del dry-run ({@link TicketmasterDryRunService#analyse}), poi, per ogni candidato valido,
 * in una transazione propria:
 *  - gia' importato (esiste il collegamento in event_source):
 *      * se una persona l'ha modificato o annullato dopo l'ultima sincronizzazione, NON si riscrive (l'evento annullato non si riattiva mai);
 *      * se e' identico, si registra solo "visto ora";
 *      * altrimenti si aggiornano soltanto i campi di {@link ImportedContent};
 *  - nuovo: si crea come OFFICIAL, PUBLISHED, non demo, senza immagine, e si registra il collegamento; a meno che non sia un duplicato
 *    EVIDENTE (titolo identico, stesso istante, stessa sede) di un altro candidato o di un evento LINKR esistente: allora si salta;
 *  - privo di link valido all'annuncio o del nome della sede: si salta (colonne obbligatorie / attribuzione).
 * Non cancella mai nulla, non cambia mai lo stato di un evento, non tocca eventi COMMUNITY, demo o ufficiali creati a mano
 * (si agisce solo su eventi collegati a Ticketmaster e ancora "nostri": vedi le condizioni SQL in JdbcTicketmasterImportStore).
 *
 * Si scrive solo se la lettura da Ticketmaster e' andata a buon fine per intero. Rilanciare la stessa importazione non crea duplicati
 * (UNIQUE(source, external_id) a livello di database, piu' il controllo prima di creare). Un'importazione alla volta.
 */
@Service
public class TicketmasterImportService {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterImportService.class);

	private static final int MAX_ITEMS = 200;
	private static final int ITEM_TITLE_LENGTH = 120;

	private final TicketmasterProperties properties;
	private final TicketmasterDryRunService analysisService;
	private final TicketmasterImportStore store;
	private final ExistingEventsSource existingEvents;
	private final TransactionTemplate transaction;
	private final Clock clock;
	private final ReentrantLock importLock = new ReentrantLock();

	TicketmasterImportService(TicketmasterProperties properties, TicketmasterDryRunService analysisService, TicketmasterImportStore store,
			ExistingEventsSource existingEvents, PlatformTransactionManager transactionManager, Clock clock) {
		this.properties = properties;
		this.analysisService = analysisService;
		this.store = store;
		this.existingEvents = existingEvents;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * @param days          facoltativo: finestra in giorni (1..window-days configurata)
	 * @param maxCandidates facoltativo: massimo di eventi da importare (1..import-max-candidates; di base import-default-candidates)
	 */
	public TicketmasterImportReport run(Integer days, Integer maxCandidates) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Params defaults = new Params(properties.windowDays(), properties.importDefaultCandidates());
		// Gli interruttori rispondono per primi: spento, o senza chiave, si risponde subito e non si guarda nemmeno il resto.
		if (!properties.enabled()) {
			return TicketmasterImportReport.notRun(Status.DISABLED, "The Ticketmaster integration is disabled (LINKR_TICKETMASTER_ENABLED=false). "
					+ "No request was sent to Ticketmaster and nothing was written.", local(now), defaults);
		}
		if (!properties.hasApiKey()) {
			return TicketmasterImportReport.notRun(Status.NOT_CONFIGURED, "The Ticketmaster integration is enabled but LINKR_TICKETMASTER_API_KEY "
					+ "is not set. No request was sent to Ticketmaster and nothing was written.", local(now), defaults);
		}
		if (!properties.importEnabled()) {
			return TicketmasterImportReport.notRun(Status.IMPORT_DISABLED, "The Ticketmaster import is disabled "
					+ "(LINKR_TICKETMASTER_IMPORT_ENABLED=false). A dry-run is still available. No request was sent and nothing was written.",
					local(now), defaults);
		}

		int effectiveDays = days == null ? properties.windowDays() : days;
		int effectiveMax = maxCandidates == null ? properties.importDefaultCandidates() : maxCandidates;
		if (effectiveDays < 1 || effectiveDays > properties.windowDays()) {
			throw new InvalidImportParameterException("days must be between 1 and " + properties.windowDays());
		}
		if (effectiveMax < 1 || effectiveMax > properties.importMaxCandidates()) {
			throw new InvalidImportParameterException("maxCandidates must be between 1 and " + properties.importMaxCandidates());
		}
		if (!importLock.tryLock()) {
			throw new ImportAlreadyRunningException();
		}
		try {
			return execute(new Params(effectiveDays, effectiveMax), now);
		}
		finally {
			importLock.unlock();
		}
	}

	private TicketmasterImportReport execute(Params params, Instant syncAt) {
		Analysis analysis = analysisService.analyse(params.days(), params.maxCandidates());
		TicketmasterDryRunReport read = analysis.report();
		Source source = new Source(read.counts(), read.excluded(), read.api(), read.errors());

		// Si scrive solo con una lettura completa: un errore a meta' (429, 5xx, timeout, database) lascia tutto com'e'.
		if (read.status() != TicketmasterDryRunReport.Status.OK) {
			return new TicketmasterImportReport(Status.FAILED, false,
					"The data read from Ticketmaster is not complete (see source.errors). Nothing was written.", local(syncAt), params, source,
					emptyResult(), List.of());
		}

		List<NormalizedEvent> candidates = analysis.candidates();
		List<ExistingEvent> existing;
		try {
			existing = candidates.isEmpty() ? List.of() : existingEvents.eventsStartingBetween(
					candidates.stream().map(NormalizedEvent::startsAt).min(Instant::compareTo).orElseThrow()
							.minus(DuplicateMatcher.OBVIOUS_MAX_START_DIFFERENCE),
					candidates.stream().map(NormalizedEvent::startsAt).max(Instant::compareTo).orElseThrow()
							.plus(DuplicateMatcher.OBVIOUS_MAX_START_DIFFERENCE));
		}
		catch (DataAccessException | TransactionException e) {
			// Senza poter controllare i duplicati con gli eventi esistenti non si scrive: meglio niente che doppioni.
			log.warn("Ticketmaster import: cannot read existing events, nothing written ({})", e.getClass().getSimpleName());
			List<ApiError> errors = new ArrayList<>(read.errors());
			errors.add(new ApiError("DUPLICATE_CHECK_FAILED", null, "Existing events could not be read to check for duplicates", null, 1));
			return new TicketmasterImportReport(Status.FAILED, false, "Existing events could not be read to check for duplicates. Nothing was written.",
					local(syncAt), params, new Source(read.counts(), read.excluded(), read.api(), List.copyOf(errors)), emptyResult(), List.of());
		}

		Counters counters = new Counters();
		List<Item> items = new ArrayList<>();
		List<NormalizedEvent> settled = new ArrayList<>();   // candidati gia' "sistemati" in questa esecuzione: servono a riconoscere i doppioni

		for (NormalizedEvent candidate : candidates) {
			String incomplete = ImportedContent.incompleteReason(candidate);
			if (incomplete != null) {
				counters.skippedIncomplete++;
				add(items, candidate, Action.SKIPPED_INCOMPLETE, null, incomplete);
				continue;
			}
			ImportedContent content = ImportedContent.from(candidate);
			Item item;
			try {
				item = transaction.execute(status -> processOne(candidate, content, existing, settled, syncAt));
			}
			catch (RuntimeException e) {
				// Il messaggio puo' contenere SQL o dati: nel log solo il tipo. L'evento resta com'era (la transazione e' annullata).
				log.warn("Ticketmaster import: event {} failed ({})", candidate.externalId(), e.getClass().getSimpleName());
				counters.failed++;
				add(items, candidate, Action.FAILED, null, "DATABASE_ERROR");
				continue;
			}
			counters.count(item.action());
			addItem(items, item);
			if (item.action() == Action.CREATED || item.action() == Action.UPDATED || item.action() == Action.UNCHANGED
					|| item.action() == Action.KEPT_LOCAL_EDITS) {
				settled.add(candidate);
			}
		}

		// Eventi gia' importati che la fonte ora dichiara annullati/rinviati/ripianificati: si registra lo stato, l'evento LINKR non si tocca.
		for (Map.Entry<String, String> seen : analysis.statusExcluded().entrySet()) {
			try {
				Boolean recorded = transaction.execute(status -> store.recordSeen(seen.getKey(), null, seen.getValue(), syncAt));
				if (Boolean.TRUE.equals(recorded)) {
					counters.sourceStatusRecorded++;
					addItem(items, new Item(seen.getKey(), null, Action.SOURCE_STATUS_RECORDED, null, seen.getValue()));
				}
			}
			catch (RuntimeException e) {
				log.warn("Ticketmaster import: status of {} not recorded ({})", seen.getKey(), e.getClass().getSimpleName());
				counters.failed++;
				addItem(items, new Item(seen.getKey(), null, Action.FAILED, null, "STATUS_NOT_RECORDED"));
			}
		}

		Result result = counters.toResult();
		Status status = result.failed() == 0 ? Status.OK : Status.PARTIAL;
		log.info("Ticketmaster import finished: status={} created={} updated={} unchanged={} keptLocalEdits={} duplicates={} incomplete={} failed={} statusRecorded={}",
				status, result.created(), result.updated(), result.unchanged(), result.keptLocalEdits(), result.skippedDuplicates(),
				result.skippedIncomplete(), result.failed(), result.sourceStatusRecorded());
		return new TicketmasterImportReport(status, false, status == Status.OK
				? "Import completed. Only events linked to Ticketmaster were created or updated; nothing was deleted."
				: "Import completed with errors (see items with action FAILED). Nothing was deleted.", local(syncAt), params, source, result,
				List.copyOf(items));
	}

	/** Un candidato, dentro la transazione dell'evento. Restituisce l'esito; qualunque eccezione annulla la transazione. */
	private Item processOne(NormalizedEvent candidate, ImportedContent content, List<ExistingEvent> existing, List<NormalizedEvent> settled,
			Instant syncAt) {
		String id = candidate.externalId();
		var linked = store.findLinked(id);
		if (linked.isPresent()) {
			Linked current = linked.get();
			if (!current.isImportedByUs()) {
				// Il collegamento punta a un evento che non e' (piu') un evento importato: non si tocca in alcun modo.
				return new Item(id, shortTitle(candidate), Action.KEPT_LOCAL_EDITS, current.eventId(), "NOT_AN_IMPORTED_EVENT");
			}
			if (current.locallyChanged()) {
				store.recordSeen(id, candidate.sourceUrl(), candidate.sourceStatus(), syncAt);
				return new Item(id, shortTitle(candidate), Action.KEPT_LOCAL_EDITS, current.eventId(),
						"CANCELLED".equals(current.status()) ? "CANCELLED_IN_LINKR" : "EDITED_IN_LINKR");
			}
			if (current.content().equals(content)) {
				store.recordSeen(id, candidate.sourceUrl(), candidate.sourceStatus(), syncAt);
				return new Item(id, shortTitle(candidate), Action.UNCHANGED, current.eventId(), null);
			}
			if (store.updateContent(current, content, candidate.sourceUrl(), candidate.sourceStatus(), syncAt)) {
				return new Item(id, shortTitle(candidate), Action.UPDATED, current.eventId(), null);
			}
			return new Item(id, shortTitle(candidate), Action.KEPT_LOCAL_EDITS, current.eventId(), "CHANGED_DURING_IMPORT");
		}

		DuplicateMatcher.Candidate mine = new DuplicateMatcher.Candidate(candidate.title(), candidate.latitude(), candidate.longitude(),
				candidate.startsAt());
		for (NormalizedEvent other : settled) {
			if (DuplicateMatcher.obviousDuplicate(mine,
					new DuplicateMatcher.Candidate(other.title(), other.latitude(), other.longitude(), other.startsAt()))) {
				return new Item(id, shortTitle(candidate), Action.SKIPPED_DUPLICATE, null, "DUPLICATE_OF_CANDIDATE:" + other.externalId());
			}
		}
		for (ExistingEvent other : existing) {
			if (DuplicateMatcher.obviousDuplicate(mine,
					new DuplicateMatcher.Candidate(other.title(), other.latitude(), other.longitude(), other.startsAt()))) {
				return new Item(id, shortTitle(candidate), Action.SKIPPED_DUPLICATE, other.id(), "DUPLICATE_OF_EXISTING_EVENT");
			}
		}

		return new Item(id, shortTitle(candidate), Action.CREATED,
				store.insertNew(content, id, candidate.sourceUrl(), candidate.sourceStatus(), syncAt), null);
	}

	// --- utilita' ---

	private static final class Counters {

		int created;
		int updated;
		int unchanged;
		int keptLocalEdits;
		int skippedDuplicates;
		int skippedIncomplete;
		int failed;
		int sourceStatusRecorded;

		void count(Action action) {
			switch (action) {
				case CREATED -> created++;
				case UPDATED -> updated++;
				case UNCHANGED -> unchanged++;
				case KEPT_LOCAL_EDITS -> keptLocalEdits++;
				case SKIPPED_DUPLICATE -> skippedDuplicates++;
				case SKIPPED_INCOMPLETE -> skippedIncomplete++;
				case FAILED -> failed++;
				case SOURCE_STATUS_RECORDED -> sourceStatusRecorded++;
			}
		}

		Result toResult() {
			return new Result(created, updated, unchanged, keptLocalEdits, skippedDuplicates, skippedIncomplete, failed, sourceStatusRecorded);
		}

	}

	private static Result emptyResult() {
		return new Result(0, 0, 0, 0, 0, 0, 0, 0);
	}

	private void add(List<Item> items, NormalizedEvent candidate, Action action, java.util.UUID eventId, String reason) {
		addItem(items, new Item(candidate.externalId(), shortTitle(candidate), action, eventId, reason));
	}

	private static void addItem(List<Item> items, Item item) {
		if (items.size() < MAX_ITEMS) {
			items.add(item);
		}
	}

	private static String shortTitle(NormalizedEvent candidate) {
		return TextSanitizer.clean(candidate.title(), ITEM_TITLE_LENGTH);
	}

	private static OffsetDateTime local(Instant instant) {
		return OffsetDateTime.ofInstant(instant, TimeConfig.MILAN_ZONE);
	}

}
