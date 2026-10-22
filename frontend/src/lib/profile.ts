import axios from "axios";

import type { UserResponse } from "../types/auth";
import { getAuthRequestErrorMessage, getProblemDetail } from "./auth";
import { apiClient } from "./api";

export { BIO_MAX, DISPLAY_NAME_MAX, DISPLAY_NAME_MIN } from "./profileValidation";

export interface UpdateProfileRequest {
  readonly displayName: string;
  readonly bio: string | null;
  /** Percorso restituito da POST /api/uploads/event-images, oppure null per rimuovere l'avatar. */
  readonly avatarUrl: string | null;
}

export interface ProfileInterest {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly categorySlug: string;
}

export type ProfileLinkStatus =
  | "NONE"
  | "OUTGOING_PENDING"
  | "INCOMING_PENDING"
  | "INCOMING_DECLINED"
  | "ACCEPTED";

export interface PublicProfile {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly bio: string | null;
  readonly avatarUrl: string | null;
  readonly interests: readonly ProfileInterest[];
  readonly sharedInterests: readonly ProfileInterest[];
  readonly linkStatus: ProfileLinkStatus;
  readonly linkRequestId: string | null;
}

/** Risposta di PUT /api/users/me/profile: lo stesso UserResponse di GET /api/users/me. */
export async function updateProfile(accessToken: string, request: UpdateProfileRequest): Promise<UserResponse> {
  const { data } = await apiClient.put<UserResponse>("/api/users/me/profile", request, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  return data;
}

export function getProfileFieldErrors(error: unknown): Readonly<Record<string, string>> {
  return getProblemDetail(error)?.errors ?? {};
}

export function getProfileErrorMessage(error: unknown): string {
  const problem = getProblemDetail(error);
  if (problem?.detail) return problem.detail;
  return getAuthRequestErrorMessage(error);
}

export function isPublicProfileUnavailable(error: unknown): boolean {
  return axios.isAxiosError(error) && (error.response?.status === 403 || error.response?.status === 404);
}

/** 404 = la persona non esiste oppure non hai il diritto di vederla (le due cose non si distinguono). */
export async function getPublicProfile(accessToken: string, userId: string, signal?: AbortSignal): Promise<PublicProfile> {
  const { data } = await apiClient.get<PublicProfile>(`/api/users/${encodeURIComponent(userId)}/profile`, {
    headers: { Authorization: `Bearer ${accessToken}` },
    signal,
  });
  return data;
}
