package com.linkr.backend.event.dto;

/**
 * Da dove arriva un evento importato (oggi solo TICKETMASTER) e il link alla pagina ufficiale (https), per "Dettagli ufficiali".
 * E' null per gli eventi creati in LINKR (community, demo, ufficiali creati a mano). Non contiene mai l'id dell'annuncio sulla fonte.
 */
public record EventSourceResponse(String provider, String url) {
}
