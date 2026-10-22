package com.linkr.backend.ticketmaster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Fotografie del database per dimostrare che qualcosa NON e' cambiato:
 *  - contenuto: numero di righe e impronta (md5) del testo di ogni riga, tabella per tabella;
 *  - attivita': i contatori di INSERT/UPDATE/DELETE (pg_stat_xact_user_tables), che crescono con qualunque scrittura, anche se poi
 *    riporta i dati allo stesso valore. ATTENZIONE: da PostgreSQL 15 includono anche le statistiche ancora "in sospeso" di transazioni
 *    precedenti sulla stessa connessione, quindi i valori assoluti non significano nulla: contano solo le DIFFERENZE tra prima e dopo.
 */
final class DatabaseSnapshots {

	/** Tutte le tabelle dell'applicazione (V1-V9). */
	static final List<String> TABLES = List.of("event", "event_participation", "event_source", "app_user", "interest_category", "interest",
			"user_interest", "link_request", "chat_message");

	private DatabaseSnapshots() {
	}

	/** tabella -> "righe:impronta". */
	static Map<String, String> contents(JdbcClient jdbc) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String table : TABLES) {
			result.put(table, jdbc.sql("SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM " + table + " t")
					.query(String.class).single());
		}
		return result;
	}

	/** tabella -> "inserite/modificate/cancellate" nella transazione corrente. */
	static Map<String, String> writesInThisTransaction(JdbcClient jdbc) {
		Map<String, String> result = new LinkedHashMap<>();
		jdbc.sql("SELECT relname, n_tup_ins, n_tup_upd, n_tup_del FROM pg_stat_xact_user_tables ORDER BY relname")
				.query((rs, rowNum) -> {
					result.put(rs.getString("relname"), rs.getLong("n_tup_ins") + "/" + rs.getLong("n_tup_upd") + "/" + rs.getLong("n_tup_del"));
					return null;
				}).list();
		return result;
	}

}
