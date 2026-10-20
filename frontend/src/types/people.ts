import type { ParticipationStatus } from "./participation";

export type PeopleSort = "shared" | "recent";

export type LinkStatus =
  | "NONE"
  | "OUTGOING_PENDING"
  | "INCOMING_PENDING"
  | "INCOMING_DECLINED"
  | "ACCEPTED";

export interface SharedInterestResponse {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly categorySlug: string;
}

export interface PersonResponse {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly avatarUrl: string | null;
  readonly status: ParticipationStatus;
  readonly sharedInterestsCount: number;
  readonly sharedInterests: readonly SharedInterestResponse[];
  readonly linkStatus: LinkStatus;
  readonly linkRequestId: string | null;
}

export interface DiscoverPersonResponse {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly avatarUrl: string | null;
  readonly sharedInterestsCount: number;
  readonly sharedInterests: readonly SharedInterestResponse[];
  readonly linkStatus: LinkStatus;
  readonly linkRequestId: string | null;
}

export interface PageResponse<T> {
  readonly items: readonly T[];
  readonly page: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
}
