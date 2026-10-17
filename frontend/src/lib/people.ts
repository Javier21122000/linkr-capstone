import axios from "axios";

import type { PageResponse, PeopleSort, PersonResponse } from "../types/people";
import { apiClient } from "./api";
import { getProblemDetail } from "./auth";

interface GetEventPeopleOptions {
  readonly sort: PeopleSort;
  readonly page: number;
  readonly size?: number;
  readonly signal?: AbortSignal;
}

export async function getEventPeople(
  accessToken: string,
  eventId: string,
  { sort, page, size = 20, signal }: GetEventPeopleOptions,
): Promise<PageResponse<PersonResponse>> {
  const { data } = await apiClient.get<PageResponse<PersonResponse>>(
    `/api/events/${encodeURIComponent(eventId)}/people`,
    {
      headers: { Authorization: `Bearer ${accessToken}` },
      params: { sort, page, size },
      signal,
    },
  );
  return data;
}

export function isPeopleParticipationRequired(error: unknown): boolean {
  const problem = getProblemDetail(error);
  return problem?.status === 403 && problem.title === "Participation required";
}

export function isPeopleEventEnded(error: unknown): boolean {
  const problem = getProblemDetail(error);
  return problem?.status === 409 && problem.title === "Event has ended";
}

export function getPeopleErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (error.response.status === 404) return "L’evento non è più disponibile.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile caricare le persone interessate. Riprova.";
}
