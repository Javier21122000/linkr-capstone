package com.linkr.backend.people;

import com.linkr.backend.event.InvalidEventFilterException;

/**
 * Ordinamenti ammessi. La clausola SQL e' una costante scritta qui: dal client arriva solo il nome ("shared"/"recent"),
 * mai pezzi di SQL. In entrambi i casi l'ultimo criterio e' l'id utente, per un ordine stabile tra una pagina e l'altra.
 */
public enum PeopleSort {

	SHARED("shared_count DESC, CASE p.status WHEN 'GOING' THEN 0 ELSE 1 END, lower(u.display_name), u.id"),
	RECENT("p.updated_at DESC, u.id");

	private final String orderBy;

	PeopleSort(String orderBy) {
		this.orderBy = orderBy;
	}

	String orderBy() {
		return orderBy;
	}

	public static PeopleSort parse(String value) {
		return switch (value) {
			case "shared" -> SHARED;
			case "recent" -> RECENT;
			default -> throw new InvalidEventFilterException("'sort' must be 'shared' or 'recent'");
		};
	}

}
