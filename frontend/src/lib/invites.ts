import axios from "axios";

import { apiClient } from "./api";
import { getProblemDetail } from "./auth";
import type { IssuedInviteResponse, InviteResponse } from "../types/invite";
import type { LinkRequestResponse } from "../types/linkRequest";

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

/** Il token di chi condivide l'evento, sempre a nome di chi è autenticato. */
export async function issueInvite(accessToken: string, eventId: string, signal?: AbortSignal): Promise<IssuedInviteResponse> {
  const { data } = await apiClient.post<IssuedInviteResponse>(`/api/events/${encodeURIComponent(eventId)}/invites`, undefined, {
    headers: authorization(accessToken),
    signal,
  });
  return data;
}

export async function getInvite(accessToken: string, token: string, signal?: AbortSignal): Promise<InviteResponse> {
  const { data } = await apiClient.get<InviteResponse>(`/api/invites/${encodeURIComponent(token)}`, {
    headers: authorization(accessToken),
    signal,
  });
  return data;
}

export async function sendInviteLinkRequest(accessToken: string, token: string): Promise<LinkRequestResponse> {
  const { data } = await apiClient.post<LinkRequestResponse>(`/api/invites/${encodeURIComponent(token)}/link-request`, undefined, {
    headers: authorization(accessToken),
  });
  return data;
}

/** Testo per chi ha premuto "Connettiti": mai il dettaglio del servizio, mai un perché che riveli la visibilità altrui. */
export function getInviteLinkErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riesco a contattare il servizio. Riprova tra poco.";
    const status = getProblemDetail(error)?.status ?? error.response.status;
    if (status === 429) return "Hai raggiunto il limite di richieste Link. Riprova più tardi.";
    if (status === 409) return "Esiste già una richiesta Link tra voi due.";
    if (status === 404) return "Non è possibile inviare questa richiesta adesso.";
  }
  return "Non è stato possibile inviare la richiesta. Riprova.";
}
