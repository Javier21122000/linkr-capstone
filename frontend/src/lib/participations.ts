import axios from "axios";

import { getProblemDetail } from "./auth";
import { apiClient } from "./api";
import type {
  ParticipationResponse,
  UpdateParticipationRequest,
  UserParticipationsResponse,
} from "../types/participation";

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function getEventParticipation(
  accessToken: string,
  eventId: string,
  signal?: AbortSignal,
): Promise<ParticipationResponse> {
  const { data } = await apiClient.get<ParticipationResponse>(
    `/api/events/${encodeURIComponent(eventId)}/participation`,
    { headers: authorization(accessToken), signal },
  );
  return data;
}

export async function updateEventParticipation(
  accessToken: string,
  eventId: string,
  request: UpdateParticipationRequest,
): Promise<ParticipationResponse> {
  const { data } = await apiClient.put<ParticipationResponse>(
    `/api/events/${encodeURIComponent(eventId)}/participation`,
    request,
    { headers: authorization(accessToken) },
  );
  return data;
}

export async function deleteEventParticipation(accessToken: string, eventId: string): Promise<void> {
  await apiClient.delete(`/api/events/${encodeURIComponent(eventId)}/participation`, {
    headers: authorization(accessToken),
  });
}

export async function getMyParticipations(
  accessToken: string,
  signal?: AbortSignal,
): Promise<UserParticipationsResponse> {
  const { data } = await apiClient.get<UserParticipationsResponse>("/api/users/me/participations", {
    headers: authorization(accessToken),
    signal,
  });
  return data;
}

export function isParticipationNotFound(error: unknown): boolean {
  const problem = getProblemDetail(error);
  return problem?.status === 404 && problem.title === "Participation not found";
}

export function isEventEndedConflict(error: unknown): boolean {
  const problem = getProblemDetail(error);
  return problem?.status === 409 && problem.title === "Event has ended";
}

export function getParticipationErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (isEventEndedConflict(error)) return "L’evento è terminato e la partecipazione non può più essere modificata.";
    if (error.response.status === 404) return "L’evento non è più disponibile.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile aggiornare la partecipazione. Riprova.";
}

export function getParticipationsErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile caricare i tuoi eventi. Riprova.";
}
