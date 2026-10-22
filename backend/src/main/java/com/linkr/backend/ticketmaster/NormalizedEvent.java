package com.linkr.backend.ticketmaster;

import java.time.Instant;

import com.linkr.backend.event.EventCategory;

/**
 * Evento di Ticketmaster ricondotto al vocabolario di LINKR. Non e' un'entita' e non viene mai salvato: nel dry-run vive solo in memoria.
 * Per un evento escluso alcuni campi possono essere null (es. coordinate mancanti); per un candidato valido sono tutti presenti
 * tranne quelli facoltativi (descrizione, fine, indirizzo, citta').
 *
 * Le IMMAGINI NON sono conservate: serve solo sapere se esistono (nessuna immagine di Ticketmaster viene scaricata, copiata o
 * pubblicata). Il link all'annuncio (sourceUrl) e' conservato perche' l'importazione lo mostra come "Dettagli ufficiali": e' solo
 * https, ripulito e senza credenziali (null se quello della fonte non e' valido). Il report del dry-run non lo riporta mai.
 */
record NormalizedEvent(
		String externalId,
		String title,
		String description,
		EventCategory category,
		/** Nome del segmento di Ticketmaster com'e' arrivato (ripulito), per statistiche e campioni. */
		String segment,
		String genre,
		Instant startsAt,
		Instant endsAt,
		String venueName,
		String address,
		String city,
		Double latitude,
		Double longitude,
		/** Stato com'e' arrivato, in minuscolo (onsale, offsale, canceled, postponed, rescheduled). */
		String sourceStatus,
		boolean hasImage,
		String sourceUrl) {

	boolean hasSourceUrl() {
		return sourceUrl != null;
	}

	boolean hasDescription() {
		return description != null && !description.isBlank();
	}

}
