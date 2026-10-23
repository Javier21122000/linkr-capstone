import axios from "axios";

import type {
  CreateLinkRequest,
  LinkRequestDirectionFilter,
  LinkRequestResponse,
  LinkRequestStatus,
} from "../types/linkRequest";
import type { PageResponse } from "../types/people";
import { apiClient } from "./api";
import { getProblemDetail } from "./auth";
import { isStaleLinkStateTitle } from "./linkState.ts";

/** L'errore dice che lo stato mostrato non è più quello del servizio: conviene rileggerlo invece di far ripetere il clic. */
export function isStaleLinkState(error: unknown): boolean {
  return isStaleLinkStateTitle(getProblemDetail(error)?.title);
}

interface GetLinkRequestsOptions {
  readonly direction: LinkRequestDirectionFilter;
  readonly status?: LinkRequestStatus;
  readonly page: number;
  readonly size?: number;
  readonly signal?: AbortSignal;
}

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function sendLinkRequest(accessToken: string, request: CreateLinkRequest): Promise<LinkRequestResponse> {
  const { data } = await apiClient.post<LinkRequestResponse>("/api/link-requests", request, {
    headers: authorization(accessToken),
  });
  return data;
}

export async function getLinkRequests(
  accessToken: string,
  { direction, status, page, size = 20, signal }: GetLinkRequestsOptions,
): Promise<PageResponse<LinkRequestResponse>> {
  const { data } = await apiClient.get<PageResponse<LinkRequestResponse>>("/api/link-requests", {
    headers: authorization(accessToken),
    params: { direction, status, page, size },
    signal,
  });
  return data;
}

export async function acceptLinkRequest(accessToken: string, requestId: string): Promise<LinkRequestResponse> {
  const { data } = await apiClient.post<LinkRequestResponse>(
    `/api/link-requests/${encodeURIComponent(requestId)}/accept`,
    undefined,
    { headers: authorization(accessToken) },
  );
  return data;
}

export async function declineLinkRequest(accessToken: string, requestId: string): Promise<LinkRequestResponse> {
  const { data } = await apiClient.post<LinkRequestResponse>(
    `/api/link-requests/${encodeURIComponent(requestId)}/decline`,
    undefined,
    { headers: authorization(accessToken) },
  );
  return data;
}

/** `context`: dove si trova chi agisce. Cambia solo il testo di "persona non disponibile", che per un evento parla dell'evento. */
export function getLinkRequestErrorMessage(error: unknown, context: "event" | "profile" = "event"): string {
  const problem = getProblemDetail(error);
  if (problem?.title === "Event has ended") return "L’evento è terminato e non accetta nuove richieste Link.";
  if (problem?.title === "Link request already exists") return "Esiste già una richiesta Link con questa persona.";
  if (problem?.title === "Link request already answered") return "La richiesta Link è già stata gestita.";
  if (problem?.title === "Person not available") {
    return context === "profile"
      ? "Non puoi inviare un Link a questa persona in questo momento."
      : "La persona non è più disponibile per questo evento.";
  }
  if (problem?.title === "Link request not found") return "La richiesta Link non è più disponibile.";
  if (problem?.title === "Too many link requests") return "Hai raggiunto il limite di richieste Link. Riprova più tardi.";

  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (error.response.status === 401) return "La sessione è scaduta. Accedi di nuovo.";
    if (error.response.status === 403) return "Non puoi completare questa azione senza una partecipazione attiva.";
    if (error.response.status === 404) return "La persona o la richiesta non è più disponibile.";
    if (error.response.status === 409) return "La richiesta Link esiste già oppure è già stata gestita.";
    if (error.response.status === 429) return "Hai raggiunto il limite di richieste Link. Riprova più tardi.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile completare l’azione. Riprova.";
}
