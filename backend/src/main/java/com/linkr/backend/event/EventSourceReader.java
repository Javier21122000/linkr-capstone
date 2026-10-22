package com.linkr.backend.event;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.linkr.backend.event.dto.EventSourceResponse;

/** Lettura (sola lettura) dell'origine esterna degli eventi, per le risposte dell'API. Gli eventi creati in LINKR non compaiono nella mappa. */
public interface EventSourceReader {

	Map<UUID, EventSourceResponse> findByEventIds(Collection<UUID> eventIds);

}
