export type EventCategory = "Music" | "Food" | "Culture" | "Outdoors";

export type EventTiming = "tonight" | "weekend" | "upcoming";

export interface DemoEvent {
  readonly id: string;
  readonly isDemo: true;
  readonly title: string;
  readonly eyebrow: string;
  readonly description: string;
  readonly category: EventCategory;
  readonly timing: EventTiming;
  readonly dateLabel: string;
  readonly timeLabel: string;
  readonly venue: string;
  readonly neighbourhood: string;
  readonly distanceLabel: string;
  readonly attendeeCount: number;
  readonly attendeeInitials: readonly string[];
  readonly imageUrl: string;
  readonly imageAlt: string;
  readonly imageCredit: string;
}

export type DiscoverFilter = "All" | "Tonight" | EventCategory;
