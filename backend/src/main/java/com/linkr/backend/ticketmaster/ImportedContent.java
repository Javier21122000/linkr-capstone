package com.linkr.backend.ticketmaster;

import java.time.Instant;

import com.linkr.backend.event.EventCategory;

/**
 * I soli campi di un evento LINKR che l'importazione scrive o aggiorna. Tutto il resto (stato, tipo, demo, creatore, immagini)
 * ha un valore fisso alla creazione e non viene mai riscritto: stato PUBLISHED, tipo OFFICIAL, non demo, nessun creatore, nessuna immagine.
 *
 * Nessuna immagine di Ticketmaster viene usata (licenza non verificata): l'evento resta senza immagine e l'interfaccia mostra il
 * segnaposto LINKR della categoria. La descrizione e' un testo neutro generato qui: il testo della fonte non viene ripubblicato.
 */
record ImportedContent(
		String title,
		String description,
		EventCategory category,
		Instant startsAt,
		Instant endsAt,
		String venueName,
		String address,
		String neighbourhood,
		double latitude,
		double longitude) {

	static final int TITLE_MAX = 120;
	static final int VENUE_MAX = 120;
	static final int ADDRESS_MAX = 200;
	static final int NEIGHBOURHOOD_MAX = 60;
	private static final String DEFAULT_NEIGHBOURHOOD = "Milano";

	/**
	 * Perche' un candidato valido non si puo' comunque importare (colonne obbligatorie di event o link mancante), oppure null.
	 * Il link all'annuncio e' obbligatorio: senza non c'e' attribuzione ne' "Dettagli ufficiali".
	 */
	static String incompleteReason(NormalizedEvent event) {
		if (event.sourceUrl() == null) {
			return "NO_VALID_SOURCE_URL";
		}
		if (TextSanitizer.clean(event.venueName()) == null) {
			return "NO_VENUE_NAME";
		}
		return null;
	}

	/** Da chiamare solo se incompleteReason() e' null. */
	static ImportedContent from(NormalizedEvent event) {
		String venue = TextSanitizer.clean(event.venueName(), VENUE_MAX);
		String city = TextSanitizer.clean(event.city(), NEIGHBOURHOOD_MAX);
		String address = TextSanitizer.clean(event.address(), ADDRESS_MAX);
		return new ImportedContent(
				TextSanitizer.clean(event.title(), TITLE_MAX),
				neutralDescription(venue),
				event.category(),
				event.startsAt(),
				event.endsAt(),
				venue,
				// L'indirizzo manca? Si ripiega su citta' e sede, che sono dati veri: non si inventa nulla.
				address != null ? address : city != null ? city : venue,
				city != null ? city : DEFAULT_NEIGHBOURHOOD,
				event.latitude(),
				event.longitude());
	}

	/** Testo informativo che non aggiunge dettagli sull'evento: dice solo dove si tiene e dove trovare le informazioni vere. */
	static String neutralDescription(String venue) {
		String where = venue.length() > 100 ? venue.substring(0, 99).stripTrailing() + "…" : venue;
		return "Evento in programma presso " + where + ". Per programma, prezzi e disponibilità consulta la pagina ufficiale su Ticketmaster.";
	}

}
