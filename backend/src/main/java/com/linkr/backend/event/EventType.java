package com.linkr.backend.event;

/**
 * Origine dell'evento. OFFICIAL = curato da LINKR (compresi gli eventi demo); COMMUNITY = creato da un utente.
 * Un utente normale crea sempre COMMUNITY; OFFICIAL e' riservato agli amministratori.
 */
public enum EventType {
	OFFICIAL,
	COMMUNITY
}
