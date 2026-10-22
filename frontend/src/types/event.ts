export type EventCategory =
  | "MUSIC"
  | "FOOD"
  | "CULTURE"
  | "OUTDOORS"
  | "NIGHTLIFE"
  | "SPORT"
  | "SOCIAL";

export type EventStatus = "PUBLISHED" | "CANCELLED";
export type EventType = "OFFICIAL" | "COMMUNITY";

export interface EventCreator {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly avatarUrl: string | null;
}

/** Da dove arriva un evento importato (oggi solo Ticketmaster) e il link alla sua pagina ufficiale. Assente per gli eventi creati in LINKR. */
export interface EventSource {
  readonly provider: string;
  readonly url: string | null;
}

export interface EventSummary {
  readonly id: string;
  readonly title: string;
  readonly category: EventCategory;
  readonly startsAt: string;
  readonly endsAt: string | null;
  readonly venueName: string;
  readonly neighbourhood: string;
  readonly latitude: number;
  readonly longitude: number;
  readonly imageUrl: string | null;
  readonly imageAlt: string | null;
  readonly demo: boolean;
  readonly status: EventStatus;
  readonly type: EventType;
  /** Origine esterna dell'evento; null/assente se è stato creato in LINKR (community, demo, ufficiale a mano). */
  readonly source?: EventSource | null;
}

export interface EventDetail extends EventSummary {
  readonly description: string;
  readonly address: string;
  readonly imageCredit: string | null;
  readonly creator: EventCreator | null;
  readonly canEdit: boolean;
}

export interface EventCategoryOption {
  readonly value: EventCategory | null;
  readonly label: string;
}
