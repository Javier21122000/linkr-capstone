import { apiClient } from "./api";
import type { HealthResponse } from "../types/health";

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== "object" || value === null) return false;

  const response = value as Record<string, unknown>;
  return (
    typeof response.status === "string" &&
    typeof response.application === "string" &&
    typeof response.service === "string"
  );
}

export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  const { data } = await apiClient.get<unknown>("/api/health", { signal });

  if (!isHealthResponse(data)) {
    throw new Error("Formato della risposta health non valido");
  }

  return data;
}
