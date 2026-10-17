import type { EventSummary } from "./event";

export type ParticipationStatus = "INTERESTED" | "GOING";

export interface UpdateParticipationRequest {
  readonly status: ParticipationStatus;
  readonly visibleToOthers: boolean;
}

export interface ParticipationResponse extends UpdateParticipationRequest {
  readonly eventId: string;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface UserParticipation {
  readonly status: ParticipationStatus;
  readonly visibleToOthers: boolean;
  readonly updatedAt: string;
  readonly event: EventSummary;
}

export interface UserParticipationsResponse {
  readonly participations: readonly UserParticipation[];
}
