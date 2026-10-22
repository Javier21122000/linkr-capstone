import type { ParticipationStatus } from "../types/participation";

export type QuickParticipationAction =
  | { readonly kind: "remove" }
  | { readonly kind: "save"; readonly status: ParticipationStatus };

export function getQuickParticipationAction(
  current: ParticipationStatus | undefined,
  requested: ParticipationStatus,
): QuickParticipationAction {
  return current === requested ? { kind: "remove" } : { kind: "save", status: requested };
}

export function getQuickParticipationFeedback(status: ParticipationStatus | null): string {
  if (status === "INTERESTED") return "Aggiunto alla tua agenda · Ti interessa";
  if (status === "GOING") return "Perfetto! Ci sarai";
  return "Rimosso dalla tua agenda";
}
