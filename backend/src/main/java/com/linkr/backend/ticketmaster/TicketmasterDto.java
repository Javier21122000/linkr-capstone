package com.linkr.backend.ticketmaster;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Forma (parziale) della risposta della Discovery API v2. Tutti i campi sono facoltativi: quello che manca resta null
 * e lo decide poi il normalizzatore. I campi sconosciuti sono ignorati. Qui non si tiene nulla che non serva al dry-run
 * (per le immagini e il link all'annuncio basta sapere se esistono).
 */
final class TicketmasterDto {

	private TicketmasterDto() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Event(String id, String name, Boolean test, String url, String info, String pleaseNote, List<Image> images,
			Dates dates, List<Classification> classifications, @JsonProperty("_embedded") EventEmbedded embedded) {

		Venue firstVenue() {
			if (embedded == null || embedded.venues() == null || embedded.venues().isEmpty()) {
				return null;
			}
			return embedded.venues().get(0);
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Image(String url) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Dates(Start start, End end, String timezone, Status status) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Start(String localDate, String localTime, String dateTime, Boolean dateTBD, Boolean dateTBA, Boolean timeTBA,
			Boolean noSpecificTime) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record End(String localDate, String localTime, String dateTime, Boolean noSpecificTime) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Status(String code) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Classification(Boolean primary, Named segment, Named genre, Named subGenre, Named type, Named subType) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Named(String id, String name) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record EventEmbedded(List<Venue> venues) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Venue(String id, String name, String timezone, Address address, Named city, Location location) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Address(String line1, String line2) {
	}

	/** Nella risposta reale latitudine e longitudine sono stringhe. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Location(String latitude, String longitude) {
	}

	/** Dati di paginazione ("page" della risposta). */
	record PageInfo(int number, int size, long totalElements, int totalPages) {
	}

	/** Una pagina gia' letta: gli eventi leggibili e quanti non lo erano (forma inattesa). */
	record EventsPage(List<Event> events, int unreadableEvents, PageInfo page) {
	}

}
