package com.linkr.backend.ticketmaster;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.linkr.backend.event.EventCategory;

/**
 * Report aggregato del dry-run. Contiene solo conteggi e alcuni esempi ripuliti: mai la API Key, mai un URL (nemmeno quello
 * dell'annuncio o delle immagini: di queste si dice solo se esistono), mai il corpo grezzo di una risposta di Ticketmaster.
 *
 * Tornano sempre i conti: received = validCandidates + excluded.total + skippedAfterLimit.
 */
public record TicketmasterDryRunReport(
		Status status,
		/** Sempre true: questo endpoint non scrive nulla. */
		boolean dryRun,
		String message,
		OffsetDateTime generatedAt,
		Window window,
		Limits limits,
		Counts counts,
		Excluded excluded,
		/** Segmenti non riconosciuti (nome come arrivato, ripulito) con il numero di eventi esclusi. */
		Map<String, Integer> excludedBySegment,
		PotentialDuplicates potentialDuplicates,
		/** Candidati validi per categoria LINKR (tutte le categorie, anche a zero). */
		Map<EventCategory, Integer> categoryDistribution,
		Quality quality,
		Api api,
		List<ApiError> errors,
		/** Candidati validi, uno o due per categoria a turno. */
		List<Sample> validSamples,
		/** Candidati segnalati come potenziali duplicati. */
		List<Sample> duplicateSamples,
		/** Eventi esclusi, uno o due per motivo a turno. */
		List<Sample> excludedSamples) {

	public enum Status {
		/** Eseguito per intero. */
		OK,
		/** Eseguito in parte (errore dopo almeno una pagina, o controllo duplicati non riuscito). */
		PARTIAL,
		/** Nessun dato ricevuto per un errore. */
		FAILED,
		/** Interruttore spento (LINKR_TICKETMASTER_ENABLED=false): nessuna richiesta, nessuna lettura. */
		DISABLED,
		/** Attivo ma senza API Key: nessuna richiesta. */
		NOT_CONFIGURED
	}

	public record Window(OffsetDateTime from, OffsetDateTime to, int days, Area area) {
	}

	public record Area(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
	}

	public record Limits(int maxCandidates, int pageSize, int maxPages, int maxCallsPerRun) {
	}

	public record Counts(int received, int validCandidates, int excluded, int skippedAfterLimit) {
	}

	public record Excluded(
			int total,
			/** Coordinate mancanti, non valide o fuori dal riquadro. */
			int coordinates,
			int coordinatesMissingOrInvalid,
			int outsideArea,
			/** Data mancante, da definire, incoerente, passata o oltre la finestra. */
			int date,
			/** Segmento non riconosciuto. */
			int category,
			/** Annullato, rinviato, ripianificato o stato sconosciuto. */
			int status,
			/** Evento di test, id/titolo mancante, id ripetuto, evento illeggibile. */
			int other,
			/** Dettaglio per motivo (nome dell'enum). */
			Map<String, Integer> byReason) {
	}

	/** Conteggi di CANDIDATI segnalati (un candidato puo' comparire in piu' conteggi). */
	public record PotentialDuplicates(
			/** false se il controllo sul database non e' riuscito (vedi errors). */
			boolean checked,
			/** Id Ticketmaster gia' collegato a un evento in event_source. */
			int alreadyImported,
			/** Simile a un evento LINKR esistente (stesso orario e luogo, titolo simile). */
			int withExistingEvents,
			/** Simile a un altro candidato della stessa esecuzione. */
			int withinCandidates) {
	}

	/** Completezza dei dati tra i candidati validi (utile a stimare la qualita' prima di un import). */
	public record Quality(int withDescription, int withImage, int withVenueName, int withAddress, int withSourceUrl, int withEndTime,
			/** Titoli piu' lunghi del limite di LINKR (120): andrebbero accorciati. */
			int titleLongerThanLimit) {
	}

	public record Api(
			/** Richieste HTTP inviate, retry compresi. */
			int calls,
			int pagesFetched,
			int retries,
			boolean rateLimited,
			int rateLimitedResponses,
			Long retryAfterSeconds,
			/** Quota dichiarata dalla fonte negli header; null se non comunicata. */
			Integer quotaLimit,
			Integer quotaAvailable,
			/** Eventi che la fonte dichiara per la ricerca (anche oltre quelli letti). */
			long totalAvailableAtSource,
			/** true se ci sono altri eventi che il dry-run non ha letto (limite di candidati o di pagine). */
			boolean truncated,
			/** SOURCE_EXHAUSTED, CANDIDATE_LIMIT, PAGE_LIMIT, ERROR o NOT_RUN. */
			String stoppedBy,
			/** Inizio dell'ultimo evento letto: fin dove arriva davvero la copertura della finestra. */
			OffsetDateTime lastEventStartsAt) {
	}

	/** Errore sanitizzato: codice, esito HTTP e un testo fisso. Mai URL, chiavi o testo della fonte. */
	public record ApiError(String code, Integer httpStatus, String message, Integer page, int attempts) {
	}

	public record Sample(
			String externalId,
			String title,
			OffsetDateTime startsAt,
			String category,
			String segment,
			String genre,
			String venue,
			String city,
			String sourceStatus,
			Double latitude,
			Double longitude,
			boolean hasDescription,
			boolean hasImage,
			/** Motivo di esclusione, solo per gli esclusi. */
			String excludedReason,
			/** "already-imported", "existing:<id evento LINKR>" o "candidate:<id Ticketmaster>", se segnalato come duplicato. */
			String potentialDuplicateOf) {
	}

	/** Report di una esecuzione che non e' partita (interruttore spento o chiave mancante): tutto a zero. */
	static TicketmasterDryRunReport notRun(Status status, String message, OffsetDateTime generatedAt, int maxCandidates, int pageSize,
			int maxPages, int maxCallsPerRun) {
		Map<EventCategory, Integer> distribution = new EnumMap<>(EventCategory.class);
		for (EventCategory category : EventCategory.values()) {
			distribution.put(category, 0);
		}
		return new TicketmasterDryRunReport(status, true, message, generatedAt, null,
				new Limits(maxCandidates, pageSize, maxPages, maxCallsPerRun),
				new Counts(0, 0, 0, 0),
				new Excluded(0, 0, 0, 0, 0, 0, 0, 0, Map.of()),
				Map.of(),
				new PotentialDuplicates(false, 0, 0, 0),
				distribution,
				new Quality(0, 0, 0, 0, 0, 0, 0),
				new Api(0, 0, 0, false, 0, null, null, null, 0, false, "NOT_RUN", null),
				List.of(), List.of(), List.of(), List.of());
	}

}
