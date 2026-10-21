import axios from "axios";

import type { EventCategory, EventDetail, EventSummary } from "../types/event";
import { apiClient } from "./api";

export async function getEvents(category?: EventCategory, signal?: AbortSignal): Promise<EventSummary[]> {
  const { data } = await apiClient.get<EventSummary[]>("/api/events", {
    params: category ? { category } : undefined,
    signal,
  });
  return data;
}

export async function getEvent(id: string, accessToken?: string, signal?: AbortSignal): Promise<EventDetail> {
  const { data } = await apiClient.get<EventDetail>(`/api/events/${encodeURIComponent(id)}`, {
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
    signal,
  });
  return data;
}

/** Riga dell'elenco/mappa per un evento appena creato: solo i campi di `EventSummary`, niente dettagli che l'elenco non porta. */
export function toEventSummary(event: EventDetail): EventSummary {
  return {
    id: event.id,
    title: event.title,
    category: event.category,
    startsAt: event.startsAt,
    endsAt: event.endsAt,
    venueName: event.venueName,
    neighbourhood: event.neighbourhood,
    latitude: event.latitude,
    longitude: event.longitude,
    imageUrl: event.imageUrl,
    imageAlt: event.imageAlt,
    demo: event.demo,
    status: event.status,
    type: event.type,
  };
}

export function isRequestCancelled(error: unknown): boolean {
  return axios.isCancel(error);
}

export function getEventsErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return "Non è stato possibile contattare il backend. Verifica che il servizio sia avviato e riprova.";
    }
    if (error.response.status === 404) return "L’evento richiesto non esiste o non è più disponibile.";
    return `Il backend ha risposto con codice HTTP ${error.response.status}.`;
  }

  return "Si è verificato un errore imprevisto durante il caricamento degli eventi.";
}
