package com.linkr.backend.event;

import java.time.Duration;
import java.time.Instant;

import org.springframework.data.jpa.domain.Specification;

/** Piccoli filtri riutilizzabili per la ricerca degli eventi (vengono combinati con AND). */
final class EventSpecifications {

	private EventSpecifications() {
	}

	static Specification<Event> hasStatus(EventStatus status) {
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	static Specification<Event> hasCategory(EventCategory category) {
		return (root, query, cb) -> cb.equal(root.get("category"), category);
	}

	/** L'evento inizia prima di "end" (estremo escluso). */
	static Specification<Event> startsBefore(Instant end) {
		return (root, query, cb) -> cb.lessThan(root.get("startsAt"), end);
	}

	/**
	 * L'evento non e' ancora finito a "moment".
	 * Con endsAt: finisce dopo "moment". Senza endsAt: e' iniziato da meno di "defaultDuration".
	 */
	static Specification<Event> notEndedAt(Instant moment, Duration defaultDuration) {
		return (root, query, cb) -> cb.or(
				cb.greaterThan(root.get("endsAt"), moment),
				cb.and(
						cb.isNull(root.get("endsAt")),
						cb.greaterThan(root.get("startsAt"), moment.minus(defaultDuration))));
	}

}
