import axios from "axios";

import type { InterestCategory, UpdateUserInterestsRequest, UserInterestsResponse } from "../types/interest";
import { apiClient } from "./api";

export const MAX_INTERESTS = 15;

export async function getInterestCatalog(signal?: AbortSignal): Promise<readonly InterestCategory[]> {
  const { data } = await apiClient.get<InterestCategory[]>("/api/interests", { signal });
  return data;
}

export async function getUserInterests(accessToken: string, signal?: AbortSignal): Promise<UserInterestsResponse> {
  const { data } = await apiClient.get<UserInterestsResponse>("/api/users/me/interests", {
    headers: { Authorization: `Bearer ${accessToken}` },
    signal,
  });
  return data;
}

export async function replaceUserInterests(
  accessToken: string,
  request: UpdateUserInterestsRequest,
): Promise<UserInterestsResponse> {
  const { data } = await apiClient.put<UserInterestsResponse>("/api/users/me/interests", request, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return data;
}

export function getInterestsErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (error.response.status === 400) return "La selezione non è valida. Ricarica il catalogo e riprova.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile caricare gli interessi. Riprova.";
}
