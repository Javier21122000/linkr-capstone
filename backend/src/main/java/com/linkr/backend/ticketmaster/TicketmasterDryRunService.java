package com.linkr.backend.ticketmaster;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import com.linkr.backend.config.TimeConfig;
import com.linkr.backend.event.EventCategory;
import com.linkr.backend.ticketmaster.ExistingEventsSource.ExistingEvent;
import com.linkr.backend.ticketmaster.TicketmasterApi.EventSearch;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Api;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.ApiError;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Area;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Counts;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Excluded;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Limits;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.PotentialDuplicates;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Quality;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Sample;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Status;
import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Window;
import com.linkr.backend.ticketmaster.TicketmasterDto.Event;
import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;

/**
 * Dry-run dell'importazione da Ticketmaster: legge gli eventi reali, li normalizza e produce un report. NON SCRIVE NULLA.
 *
 * La garanzia e' strutturale: questa classe dipende solo da {@link TicketmasterApi} (HTTP in lettura) e da
 * {@link ExistingEventsSource} (SELECT in transazione di sola lettura). Non ha repository JPA, EntityManager ne' istruzioni di
 * scrittura; il test TicketmasterDryRunIntegrationTest lo verifica anche sul database (eventi, partecipazioni, event_source invariati).
 *
 * Con l'integrazione spenta o senza chiave risponde subito con un report "DISABLED"/"NOT_CONFIGURED", senza nessuna richiesta
 * e senza toccare il database. Un solo dry-run alla volta.
 */
@Service
public class TicketmasterDryRunService {

	private static final Logger log = LoggerFactory.getLogger(TicketmasterDryRunService.class);

	private static final int EVENT_TITLE_LIMIT = 120;
	private static final int VALID_SAMPLES = 10;
	private static final int DUPLICATE_SAMPLES = 5;
	private static final int EXCLUDED_SAMPLES = 10;
	private static final int EXCLUDED_PER_REASON = 2;
	private static final int MAX_DISTINCT_SEGMENTS = 30;
	private static final int SAMPLE_TITLE_LENGTH = 120;
	private static final int SAMPLE_TEXT_LENGTH = 80;

	private final TicketmasterProperties properties;
	private final TicketmasterApi api;
	private final ExistingEventsSource existingEvents;
	private final Clock clock;
	private final ReentrantLock runLock = new ReentrantLock();

	TicketmasterDryRunService(TicketmasterProperties properties, TicketmasterApi api, ExistingEventsSource existingEvents, Clock clock) {
		this.properties = properties;
		this.api = api;
		this.existingEvents = existingEvents;
		this.clock = clock;
	}

	/**
	 * @param days            facoltativo: finestra piu' corta di quella configurata (1..windowDays)
	 * @param maxCandidates   facoltativo: meno candidati di quelli configurati (1..maxCandidates)
	 */
	public TicketmasterDryRunReport run(Integer days, Integer maxCandidates) {
		return analyse(days, maxCandidates).report();
	}

	/**
	 * Esito completo di un'analisi: il report (quello del dry-run) piu' i dati veri che servono all'importazione.
	 * statusExcluded = id Ticketmaster -> stato (canceled, postponed, rescheduled...) degli eventi esclusi per stato.
	 */
	record Analysis(TicketmasterDryRunReport report, List<NormalizedEvent> candidates, Map<String, String> statusExcluded) {

		static Analysis of(TicketmasterDryRunReport report) {
			return new Analysis(report, List.of(), Map.of());
		}

	}

	/** Stessa logica di run() (stesso blocco, stessi limiti, stessi controlli), ma restituisce anche i candidati. Non scrive nulla. */
	Analysis analyse(Integer days, Integer maxCandidates) {
		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		// L'interruttore viene prima di tutto: spento, o senza chiave, si risponde subito e non si guarda nemmeno il resto.
		if (!properties.enabled()) {
			return Analysis.of(notRun(Status.DISABLED, "The Ticketmaster integration is disabled (LINKR_TICKETMASTER_ENABLED=false). "
					+ "No request was sent to Ticketmaster and nothing was read or written.", now));
		}
		if (!properties.hasApiKey()) {
			return Analysis.of(notRun(Status.NOT_CONFIGURED, "The Ticketmaster integration is enabled but LINKR_TICKETMASTER_API_KEY is not set. "
					+ "No request was sent to Ticketmaster and nothing was read or written.", now));
		}

		int effectiveDays = days == null ? properties.windowDays() : days;
		int effectiveMax = maxCandidates == null ? properties.maxCandidates() : maxCandidates;
		if (effectiveDays < 1 || effectiveDays > properties.windowDays()) {
			throw new InvalidImportParameterException("days must be between 1 and " + properties.windowDays());
		}
		if (effectiveMax < 1 || effectiveMax > properties.maxCandidates()) {
			throw new InvalidImportParameterException("maxCandidates must be between 1 and " + properties.maxCandidates());
		}
		if (!runLock.tryLock()) {
			throw new ImportAlreadyRunningException();
		}
		try {
			return execute(now, effectiveDays, effectiveMax);
		}
		finally {
			runLock.unlock();
		}
	}

	private TicketmasterDryRunReport notRun(Status status, String message, Instant now) {
		return TicketmasterDryRunReport.notRun(status, message, local(now), properties.maxCandidates(), properties.pageSize(),
				properties.maxPages(), properties.maxCallsPerRun());
	}

	// --- esecuzione ---

	private Analysis execute(Instant now, int days, int maxCandidates) {
		Instant windowEnd = now.plus(days, ChronoUnit.DAYS);
		TicketmasterEventNormalizer normalizer = new TicketmasterEventNormalizer(properties.area());
		EventSearch search = new EventSearch(now, windowEnd, properties.pageSize());
		CallStats stats = new CallStats();

		List<NormalizedEvent> candidates = new ArrayList<>();
		Map<ExclusionReason, Integer> excludedByReason = new EnumMap<>(ExclusionReason.class);
		Map<ExclusionReason, List<NormalizedEvent>> excludedExamples = new EnumMap<>(ExclusionReason.class);
		Map<String, Integer> excludedBySegment = new LinkedHashMap<>();
		Map<String, String> statusExcluded = new LinkedHashMap<>();
		Set<String> seenIds = new HashSet<>();
		List<ApiError> errors = new ArrayList<>();

		int received = 0;
		int skippedAfterLimit = 0;
		long totalAvailable = 0;
		boolean hasMore = true;
		String stoppedBy = null;
		Instant lastEventStart = null;

		for (int page = 0; page < properties.maxPages(); page++) {
			if (candidates.size() >= maxCandidates) {
				stoppedBy = "CANDIDATE_LIMIT";
				break;
			}
			EventsPage result;
			try {
				result = api.fetchEvents(search, page, stats);
			}
			catch (TicketmasterException e) {
				errors.add(new ApiError(e.kind().name(), e.httpStatus(), e.kind().safeMessage(), page, e.attempts()));
				stoppedBy = "ERROR";
				break;
			}

			if (page == 0) {
				totalAvailable = result.page().totalElements();
			}
			received += result.events().size() + result.unreadableEvents();
			if (result.unreadableEvents() > 0) {
				excludedByReason.merge(ExclusionReason.UNREADABLE, result.unreadableEvents(), Integer::sum);
			}

			for (Event raw : result.events()) {
				if (candidates.size() >= maxCandidates) {
					skippedAfterLimit++;
					continue;
				}
				TicketmasterEventNormalizer.Result normalized = normalizer.normalize(raw, now, windowEnd);
				NormalizedEvent event = normalized.event();
				ExclusionReason reason = normalized.reason();
				// Lo stesso id puo' ripresentarsi in pagine diverse: si conta una volta sola.
				if (event.externalId() != null && !seenIds.add(event.externalId())) {
					reason = ExclusionReason.DUPLICATE_EXTERNAL_ID;
				}
				if (event.startsAt() != null && (lastEventStart == null || event.startsAt().isAfter(lastEventStart))) {
					lastEventStart = event.startsAt();
				}
				if (reason == null) {
					candidates.add(event);
					continue;
				}
				excludedByReason.merge(reason, 1, Integer::sum);
				if (reason.group() == ExclusionReason.Group.STATUS && event.externalId() != null && event.sourceStatus() != null) {
					statusExcluded.putIfAbsent(event.externalId(), event.sourceStatus());
				}
				List<NormalizedEvent> examples = excludedExamples.computeIfAbsent(reason, r -> new ArrayList<>());
				if (examples.size() < EXCLUDED_PER_REASON) {
					examples.add(event);
				}
				if (reason == ExclusionReason.CATEGORY_UNRECOGNIZED) {
					countSegment(excludedBySegment, event.segment());
				}
			}

			hasMore = result.page().totalPages() > page + 1
					|| (result.page().totalPages() == 0 && result.events().size() + result.unreadableEvents() >= properties.pageSize());
			if (!hasMore) {
				stoppedBy = "SOURCE_EXHAUSTED";
				break;
			}
		}
		if (stoppedBy == null) {
			stoppedBy = "PAGE_LIMIT";
		}
		boolean truncated = skippedAfterLimit > 0 || (hasMore && !stoppedBy.equals("SOURCE_EXHAUSTED") && !stoppedBy.equals("ERROR"));

		DuplicateAnalysis duplicates = analyseDuplicates(candidates, errors);

		Status status = errors.isEmpty() ? Status.OK : stats.pagesFetched == 0 ? Status.FAILED : Status.PARTIAL;
		TicketmasterDryRunReport report = new TicketmasterDryRunReport(
				status,
				true,
				messageFor(status),
				local(now),
				new Window(local(now), local(windowEnd), days, new Area(properties.area().minLatitude(), properties.area().maxLatitude(),
						properties.area().minLongitude(), properties.area().maxLongitude())),
				new Limits(maxCandidates, properties.pageSize(), properties.maxPages(), properties.maxCallsPerRun()),
				new Counts(received, candidates.size(), totalExcluded(excludedByReason), skippedAfterLimit),
				excluded(excludedByReason),
				excludedBySegment,
				duplicates.summary(),
				distribution(candidates),
				quality(candidates),
				new Api(stats.calls, stats.pagesFetched, stats.retries, stats.rateLimitedResponses > 0, stats.rateLimitedResponses,
						stats.longestRetryAfter == null ? null : stats.longestRetryAfter.toSeconds(), stats.quotaLimit, stats.quotaAvailable,
						totalAvailable, truncated, stoppedBy, lastEventStart == null ? null : local(lastEventStart)),
				List.copyOf(errors),
				validSamples(candidates, duplicates),
				duplicateSamples(candidates, duplicates),
				excludedSamples(excludedExamples));

		log.info("Ticketmaster dry-run finished: status={} received={} candidates={} excluded={} calls={} stoppedBy={}", status, received,
				candidates.size(), report.counts().excluded(), stats.calls, stoppedBy);
		return new Analysis(report, List.copyOf(candidates), Map.copyOf(statusExcluded));
	}

	private static String messageFor(Status status) {
		return switch (status) {
			case OK -> "Dry run completed. Nothing was imported, created, updated or deleted.";
			case PARTIAL -> "Dry run completed only in part (see errors). Nothing was imported, created, updated or deleted.";
			case FAILED -> "Dry run failed before any events could be read (see errors). Nothing was imported, created, updated or deleted.";
			default -> "";
		};
	}

	private static void countSegment(Map<String, Integer> counts, String segment) {
		String key = segment == null ? "(none)" : segment;
		if (!counts.containsKey(key) && counts.size() >= MAX_DISTINCT_SEGMENTS) {
			key = "(other)";
		}
		counts.merge(key, 1, Integer::sum);
	}

	private static int totalExcluded(Map<ExclusionReason, Integer> byReason) {
		return byReason.values().stream().mapToInt(Integer::intValue).sum();
	}

	private static Excluded excluded(Map<ExclusionReason, Integer> byReason) {
		int missingOrInvalid = count(byReason, ExclusionReason.COORDINATES_MISSING) + count(byReason, ExclusionReason.COORDINATES_INVALID);
		int outside = count(byReason, ExclusionReason.OUTSIDE_AREA);
		Map<String, Integer> detail = new LinkedHashMap<>();
		for (ExclusionReason reason : ExclusionReason.values()) {
			detail.put(reason.name(), count(byReason, reason));
		}
		return new Excluded(totalExcluded(byReason), missingOrInvalid + outside, missingOrInvalid, outside,
				group(byReason, ExclusionReason.Group.DATE), group(byReason, ExclusionReason.Group.CATEGORY),
				group(byReason, ExclusionReason.Group.STATUS), group(byReason, ExclusionReason.Group.OTHER), detail);
	}

	private static int count(Map<ExclusionReason, Integer> byReason, ExclusionReason reason) {
		return byReason.getOrDefault(reason, 0);
	}

	private static int group(Map<ExclusionReason, Integer> byReason, ExclusionReason.Group group) {
		return byReason.entrySet().stream().filter(entry -> entry.getKey().group() == group).mapToInt(Map.Entry::getValue).sum();
	}

	private static Map<EventCategory, Integer> distribution(List<NormalizedEvent> candidates) {
		Map<EventCategory, Integer> distribution = new EnumMap<>(EventCategory.class);
		for (EventCategory category : EventCategory.values()) {
			distribution.put(category, 0);
		}
		for (NormalizedEvent candidate : candidates) {
			distribution.merge(candidate.category(), 1, Integer::sum);
		}
		return distribution;
	}

	private static Quality quality(List<NormalizedEvent> candidates) {
		int description = 0;
		int image = 0;
		int venue = 0;
		int address = 0;
		int sourceUrl = 0;
		int end = 0;
		int longTitle = 0;
		for (NormalizedEvent candidate : candidates) {
			description += candidate.hasDescription() ? 1 : 0;
			image += candidate.hasImage() ? 1 : 0;
			venue += candidate.venueName() != null ? 1 : 0;
			address += candidate.address() != null ? 1 : 0;
			sourceUrl += candidate.hasSourceUrl() ? 1 : 0;
			end += candidate.endsAt() != null ? 1 : 0;
			longTitle += candidate.title().length() > EVENT_TITLE_LIMIT ? 1 : 0;
		}
		return new Quality(description, image, venue, address, sourceUrl, end, longTitle);
	}

	// --- potenziali duplicati (solo letture) ---

	private record DuplicateAnalysis(PotentialDuplicates summary, Map<String, String> duplicateOf) {
	}

	private DuplicateAnalysis analyseDuplicates(List<NormalizedEvent> candidates, List<ApiError> errors) {
		Map<String, String> duplicateOf = new LinkedHashMap<>();
		if (candidates.isEmpty()) {
			return new DuplicateAnalysis(new PotentialDuplicates(true, 0, 0, 0), duplicateOf);
		}

		Set<String> imported;
		List<ExistingEvent> existing;
		try {
			imported = existingEvents.importedExternalIds(candidates.stream().map(NormalizedEvent::externalId).toList());
			Instant earliest = candidates.stream().map(NormalizedEvent::startsAt).min(Instant::compareTo).orElseThrow();
			Instant latest = candidates.stream().map(NormalizedEvent::startsAt).max(Instant::compareTo).orElseThrow();
			existing = existingEvents.eventsStartingBetween(earliest.minus(DuplicateMatcher.MAX_START_DIFFERENCE),
					latest.plus(DuplicateMatcher.MAX_START_DIFFERENCE));
		}
		catch (DataAccessException | TransactionException e) {
			// Non si riporta il messaggio (puo' contenere SQL o nomi di tabelle): il tipo basta per capire.
			log.warn("Ticketmaster dry-run: duplicate check against the database failed ({})", e.getClass().getSimpleName());
			errors.add(new ApiError("DUPLICATE_CHECK_FAILED", null,
					"The check for potential duplicates against existing events could not be completed", null, 1));
			return new DuplicateAnalysis(new PotentialDuplicates(false, 0, 0, 0), duplicateOf);
		}

		int alreadyImported = 0;
		int withExisting = 0;
		int withinCandidates = 0;
		for (int i = 0; i < candidates.size(); i++) {
			NormalizedEvent candidate = candidates.get(i);
			if (imported.contains(candidate.externalId())) {
				alreadyImported++;
				duplicateOf.putIfAbsent(candidate.externalId(), "already-imported");
			}
			DuplicateMatcher.Candidate mine = matchKey(candidate);
			for (ExistingEvent other : existing) {
				if (DuplicateMatcher.likelySameEvent(mine,
						new DuplicateMatcher.Candidate(other.title(), other.latitude(), other.longitude(), other.startsAt()))) {
					withExisting++;
					duplicateOf.putIfAbsent(candidate.externalId(), "existing:" + other.id());
					break;
				}
			}
			for (int j = 0; j < i; j++) {
				if (DuplicateMatcher.likelySameEvent(mine, matchKey(candidates.get(j)))) {
					withinCandidates++;
					duplicateOf.putIfAbsent(candidate.externalId(), "candidate:" + candidates.get(j).externalId());
					break;
				}
			}
		}
		return new DuplicateAnalysis(new PotentialDuplicates(true, alreadyImported, withExisting, withinCandidates), duplicateOf);
	}

	private static DuplicateMatcher.Candidate matchKey(NormalizedEvent event) {
		return new DuplicateMatcher.Candidate(event.title(), event.latitude(), event.longitude(), event.startsAt());
	}

	// --- campioni ---

	private List<Sample> validSamples(List<NormalizedEvent> candidates, DuplicateAnalysis duplicates) {
		Map<EventCategory, Deque<NormalizedEvent>> byCategory = new EnumMap<>(EventCategory.class);
		for (NormalizedEvent candidate : candidates) {
			byCategory.computeIfAbsent(candidate.category(), c -> new ArrayDeque<>()).add(candidate);
		}
		List<Sample> samples = new ArrayList<>();
		while (samples.size() < VALID_SAMPLES && byCategory.values().stream().anyMatch(queue -> !queue.isEmpty())) {
			for (Deque<NormalizedEvent> queue : byCategory.values()) {
				if (!queue.isEmpty() && samples.size() < VALID_SAMPLES) {
					NormalizedEvent event = queue.poll();
					samples.add(sample(event, null, duplicates.duplicateOf().get(event.externalId())));
				}
			}
		}
		return samples;
	}

	private List<Sample> duplicateSamples(List<NormalizedEvent> candidates, DuplicateAnalysis duplicates) {
		return candidates.stream()
				.filter(candidate -> duplicates.duplicateOf().containsKey(candidate.externalId()))
				.limit(DUPLICATE_SAMPLES)
				.map(candidate -> sample(candidate, null, duplicates.duplicateOf().get(candidate.externalId())))
				.toList();
	}

	private List<Sample> excludedSamples(Map<ExclusionReason, List<NormalizedEvent>> examples) {
		List<Sample> samples = new ArrayList<>();
		for (int round = 0; round < EXCLUDED_PER_REASON && samples.size() < EXCLUDED_SAMPLES; round++) {
			for (Map.Entry<ExclusionReason, List<NormalizedEvent>> entry : examples.entrySet()) {
				if (round < entry.getValue().size() && samples.size() < EXCLUDED_SAMPLES) {
					samples.add(sample(entry.getValue().get(round), entry.getKey().name(), null));
				}
			}
		}
		return samples;
	}

	private Sample sample(NormalizedEvent event, String excludedReason, String duplicateOf) {
		return new Sample(
				TextSanitizer.clean(event.externalId(), 40),
				TextSanitizer.clean(event.title(), SAMPLE_TITLE_LENGTH),
				event.startsAt() == null ? null : local(event.startsAt()),
				event.category() == null ? null : event.category().name(),
				TextSanitizer.clean(event.segment(), SAMPLE_TEXT_LENGTH),
				TextSanitizer.clean(event.genre(), SAMPLE_TEXT_LENGTH),
				TextSanitizer.clean(event.venueName(), SAMPLE_TEXT_LENGTH),
				TextSanitizer.clean(event.city(), SAMPLE_TEXT_LENGTH),
				event.sourceStatus(),
				event.latitude() == null ? null : Math.round(event.latitude() * 10_000) / 10_000.0,
				event.longitude() == null ? null : Math.round(event.longitude() * 10_000) / 10_000.0,
				event.hasDescription(),
				event.hasImage(),
				excludedReason,
				duplicateOf);
	}

	private static OffsetDateTime local(Instant instant) {
		return OffsetDateTime.ofInstant(instant, TimeConfig.MILAN_ZONE);
	}

}
