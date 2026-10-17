import axios from "axios";

import type { AuthResponse, LoginRequest, ProblemDetail, RegisterRequest, UserResponse } from "../types/auth";
import { apiClient } from "./api";

export async function login(request: LoginRequest): Promise<AuthResponse> {
  const { data } = await apiClient.post<AuthResponse>("/api/auth/login", request);
  return data;
}

export async function register(request: RegisterRequest): Promise<AuthResponse> {
  const { data } = await apiClient.post<AuthResponse>("/api/auth/register", request);
  return data;
}

export async function getCurrentUser(accessToken: string, signal?: AbortSignal): Promise<UserResponse> {
  const { data } = await apiClient.get<UserResponse>("/api/users/me", {
    headers: { Authorization: `Bearer ${accessToken}` },
    signal,
  });
  return data;
}

export function getProblemDetail(error: unknown): ProblemDetail | undefined {
  if (!axios.isAxiosError(error) || typeof error.response?.data !== "object" || error.response.data === null) {
    return undefined;
  }

  return error.response.data as ProblemDetail;
}

export function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401;
}

export function getAuthRequestErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    }

    if (error.response.status >= 500) {
      return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
    }
  }

  return "Non è stato possibile completare la richiesta. Controlla i dati e riprova.";
}
