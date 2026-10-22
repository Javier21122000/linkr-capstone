package com.linkr.backend.ticketmaster;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.linkr.backend.ticketmaster.TicketmasterDto.Event;
import com.linkr.backend.ticketmaster.TicketmasterDto.EventsPage;
import com.linkr.backend.ticketmaster.TicketmasterDto.PageInfo;
import com.linkr.backend.ticketmaster.TicketmasterException.Kind;

/**
 * Legge il corpo JSON di una pagina di eventi. Tollerante: un evento di forma inattesa non fa fallire la pagina (viene contato
 * come "illeggibile"); una pagina senza "_embedded" e' semplicemente vuota. Solo un corpo che non e' JSON, o che non e' un
 * oggetto, e' un errore (MALFORMED_RESPONSE).
 */
final class TicketmasterResponseParser {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private TicketmasterResponseParser() {
	}

	static EventsPage parse(String body) {
		if (body == null || body.isBlank()) {
			throw new TicketmasterException(Kind.MALFORMED_RESPONSE, 200, null);
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		}
		catch (JacksonException e) {
			// Il testo dell'eccezione puo' citare il contenuto della risposta: non lo si propaga.
			throw new TicketmasterException(Kind.MALFORMED_RESPONSE, 200, null);
		}
		if (root == null || !root.isObject()) {
			throw new TicketmasterException(Kind.MALFORMED_RESPONSE, 200, null);
		}

		JsonNode eventsNode = root.path("_embedded").path("events");
		List<Event> events = new ArrayList<>();
		int unreadable = 0;
		if (eventsNode.isArray()) {
			for (JsonNode node : eventsNode) {
				try {
					Event event = MAPPER.treeToValue(node, Event.class);
					if (event == null) {
						unreadable++;
					}
					else {
						events.add(event);
					}
				}
				catch (JacksonException e) {
					unreadable++;
				}
			}
		}
		else if (!eventsNode.isMissingNode() && !eventsNode.isNull()) {
			throw new TicketmasterException(Kind.MALFORMED_RESPONSE, 200, null);
		}

		JsonNode page = root.path("page");
		PageInfo info = new PageInfo(page.path("number").asInt(0), page.path("size").asInt(0), page.path("totalElements").asLong(0),
				page.path("totalPages").asInt(0));
		return new EventsPage(List.copyOf(events), unreadable, info);
	}

}
