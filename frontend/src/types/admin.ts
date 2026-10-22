import type { EventCategory } from "./event";

export type TicketmasterRunStatus = "OK" | "PARTIAL" | "FAILED" | "DISABLED" | "NOT_CONFIGURED";
export type TicketmasterImportStatus = TicketmasterRunStatus | "IMPORT_DISABLED";

export interface TicketmasterCounts {
  readonly received: number;
  readonly validCandidates: number;
  readonly excluded: number;
  readonly skippedAfterLimit: number;
}

export interface TicketmasterExcluded {
  readonly total: number;
  readonly coordinates: number;
  readonly coordinatesMissingOrInvalid: number;
  readonly outsideArea: number;
  readonly date: number;
  readonly category: number;
  readonly status: number;
  readonly other: number;
  readonly byReason: Readonly<Record<string, number>>;
}

export interface TicketmasterApiError {
  readonly code: string;
  readonly httpStatus: number | null;
  readonly message: string;
  readonly page: number | null;
  readonly attempts: number;
}

export interface TicketmasterApiStats {
  readonly calls: number;
  readonly pagesFetched: number;
  readonly retries: number;
  readonly rateLimited: boolean;
  readonly rateLimitedResponses: number;
  readonly retryAfterSeconds: number | null;
  readonly quotaLimit: number | null;
  readonly quotaAvailable: number | null;
  readonly totalAvailableAtSource: number;
  readonly truncated: boolean;
  readonly stoppedBy: string;
  readonly lastEventStartsAt: string | null;
}

export interface TicketmasterSample {
  readonly externalId: string;
  readonly title: string;
  readonly startsAt: string;
  readonly category: string;
  readonly segment: string | null;
  readonly genre: string | null;
  readonly venue: string | null;
  readonly city: string | null;
  readonly sourceStatus: string | null;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly hasDescription: boolean;
  readonly hasImage: boolean;
  readonly excludedReason: string | null;
  readonly potentialDuplicateOf: string | null;
}

export interface TicketmasterDryRunReport {
  readonly status: TicketmasterRunStatus;
  readonly dryRun: true;
  readonly message: string;
  readonly generatedAt: string;
  readonly counts: TicketmasterCounts;
  readonly excluded: TicketmasterExcluded;
  readonly categoryDistribution: Readonly<Record<EventCategory, number>>;
  readonly api: TicketmasterApiStats;
  readonly errors: readonly TicketmasterApiError[];
  readonly validSamples: readonly TicketmasterSample[];
  readonly duplicateSamples: readonly TicketmasterSample[];
  readonly excludedSamples: readonly TicketmasterSample[];
}

export interface TicketmasterImportResult {
  readonly created: number;
  readonly updated: number;
  readonly unchanged: number;
  readonly keptLocalEdits: number;
  readonly skippedDuplicates: number;
  readonly skippedIncomplete: number;
  readonly failed: number;
  readonly sourceStatusRecorded: number;
}

export interface TicketmasterImportReport {
  readonly status: TicketmasterImportStatus;
  readonly dryRun: false;
  readonly message: string;
  readonly generatedAt: string;
  readonly source: {
    readonly counts: TicketmasterCounts;
    readonly excluded: TicketmasterExcluded;
    readonly api: TicketmasterApiStats;
    readonly errors: readonly TicketmasterApiError[];
  };
  readonly result: TicketmasterImportResult;
}
