export type EventCategory =
  | "MUSIC"
  | "FOOD"
  | "CULTURE"
  | "OUTDOORS"
  | "NIGHTLIFE"
  | "SPORT"
  | "SOCIAL";

export interface EventSummary {
  readonly id: string;
  readonly title: string;
  readonly category: EventCategory;
  readonly startsAt: string;
  readonly endsAt?: string | null;
  readonly venueName: string;
  readonly neighbourhood: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly imageUrl?: string | null;
  readonly imageAlt?: string | null;
  readonly demo: boolean;
}

export interface EventDetail extends EventSummary {
  readonly description: string;
  readonly address: string;
  readonly imageCredit?: string | null;
}

export interface EventCategoryOption {
  readonly value: EventCategory | null;
  readonly label: string;
}
