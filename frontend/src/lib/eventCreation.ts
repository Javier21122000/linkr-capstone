import axios from "axios";

import { apiClient } from "./api";
import type { EventCategory, EventDetail, EventSummary, EventType } from "../types/event";

export interface CreateEventPayload {
  readonly title: string;
  readonly description: string;
  readonly category: EventCategory;
  readonly startsAt: string;
  readonly endsAt?: string;
  readonly venueName: string;
  readonly address: string;
  readonly neighbourhood: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly imageUrl?: string;
  readonly imageAlt?: string;
  readonly imageCredit?: string;
  readonly type: EventType;
}

export interface CreateEventFailure {
  readonly message: string;
  readonly fieldErrors?: Readonly<Record<string, string>>;
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function createEvent(
  accessToken: string,
  payload: CreateEventPayload,
): Promise<EventDetail> {
  const { data } = await apiClient.post<EventDetail>("/api/events", payload, {
    headers: authorization(accessToken),
  });
  return data;
}

export async function updateEvent(
  accessToken: string,
  eventId: string,
  payload: CreateEventPayload,
): Promise<EventDetail> {
  const { data } = await apiClient.put<EventDetail>(`/api/events/${encodeURIComponent(eventId)}`, payload, {
    headers: authorization(accessToken),
  });
  return data;
}

export async function cancelEvent(accessToken: string, eventId: string): Promise<void> {
  await apiClient.delete(`/api/events/${encodeURIComponent(eventId)}`, {
    headers: authorization(accessToken),
  });
}

export async function getMyEvents(accessToken: string, signal?: AbortSignal): Promise<EventSummary[]> {
  const { data } = await apiClient.get<{ readonly events: EventSummary[] }>("/api/users/me/events", {
    headers: authorization(accessToken),
    signal,
  });
  return data.events;
}

function readFieldErrors(data: unknown): Readonly<Record<string, string>> | undefined {
  if (typeof data !== "object" || data === null || !("errors" in data)) return undefined;
  const errors = data.errors;
  if (typeof errors !== "object" || errors === null) return undefined;

  const entries = Object.entries(errors).filter((entry): entry is [string, string] => typeof entry[1] === "string");
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

export function getCreateEventFailure(error: unknown): CreateEventFailure {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return { message: "Non riusciamo a contattare il servizio. L’evento non è stato salvato: controlla la connessione e riprova." };
    }

    const fieldErrors = readFieldErrors(error.response.data);
    switch (error.response.status) {
      case 400:
        return { message: "Correggi i campi indicati e riprova.", fieldErrors };
      case 401:
        return { message: "La sessione non è più valida. Accedi di nuovo per pubblicare l’evento." };
      case 403:
        return { message: "Non hai i permessi necessari per pubblicare questo tipo di evento." };
      case 409:
        return { message: "L’evento è in conflitto con lo stato attuale dei dati. Aggiorna i valori e riprova." };
      case 429:
        return { message: "Hai raggiunto il limite di 5 eventi creati nelle ultime 24 ore. Riprova più tardi." };
      default:
        if (error.response.status >= 500) {
          return { message: "Il servizio non è disponibile in questo momento. L’evento non è stato salvato." };
        }
    }
  }

  return { message: "Non è stato possibile creare l’evento. I dati inseriti sono ancora disponibili: controllali e riprova." };
}

export function getUpdateEventFailure(error: unknown): CreateEventFailure {
  if (axios.isAxiosError(error)) {
    if (!error.response) return { message: "Non riusciamo a contattare il servizio. Le modifiche non sono state salvate." };
    const fieldErrors = readFieldErrors(error.response.data);
    switch (error.response.status) {
      case 400: return { message: "Correggi i campi indicati e riprova.", fieldErrors };
      case 401: return { message: "La sessione non è più valida. Accedi di nuovo per salvare le modifiche." };
      case 403: return { message: "Non hai i permessi per modificare questo evento." };
      case 404: return { message: "L’evento non esiste o non è più disponibile." };
      case 409: return { message: "L’evento è terminato, annullato o è cambiato nel frattempo e non può più essere modificato." };
      default:
        if (error.response.status >= 500) return { message: "Il servizio non è disponibile. Le modifiche non sono state salvate." };
    }
  }
  return { message: "Non è stato possibile salvare le modifiche. I dati inseriti sono ancora disponibili." };
}

export function getCancelEventFailure(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. L’evento non è stato annullato.";
    switch (error.response.status) {
      case 401: return "La sessione non è più valida. Accedi di nuovo per annullare l’evento.";
      case 403: return "Non hai i permessi per annullare questo evento.";
      case 404: return "L’evento non esiste o non è più disponibile.";
      case 409: return "L’evento è terminato o è cambiato nel frattempo e non può essere annullato.";
      default:
        if (error.response.status >= 500) return "Il servizio non è disponibile. L’evento non è stato annullato.";
    }
  }
  return "Non è stato possibile annullare l’evento. Riprova.";
}
