import type { DemoEvent, DiscoverFilter } from "../types/event";

export const discoverFilters: readonly DiscoverFilter[] = [
  "All",
  "Tonight",
  "Music",
  "Food",
  "Culture",
  "Outdoors",
];

export const demoEvents: readonly DemoEvent[] = [
  {
    id: "courtyard-sessions",
    isDemo: true,
    title: "Courtyard Sessions",
    eyebrow: "Featured tonight",
    description:
      "An open-air set, local selectors and a late aperitivo in one of Isola's hidden courtyards.",
    category: "Music",
    timing: "tonight",
    dateLabel: "Tonight",
    timeLabel: "19:30",
    venue: "Cortile 68",
    neighbourhood: "Isola",
    distanceLabel: "1.4 km",
    attendeeCount: 28,
    attendeeInitials: ["A", "M", "L"],
    imageUrl:
      "https://images.unsplash.com/photo-1506157786151-b8491531f063?auto=format&fit=crop&w=1600&q=86",
    imageAlt: "Crowd enjoying a live music performance under warm stage lights",
    imageCredit: "Unsplash demo image",
  },
  {
    id: "table-for-new-friends",
    isDemo: true,
    title: "Table for new friends",
    eyebrow: "Easy introductions",
    description:
      "A relaxed shared-table dinner designed for good conversation, seasonal plates and no awkward networking.",
    category: "Food",
    timing: "tonight",
    dateLabel: "Tonight",
    timeLabel: "20:15",
    venue: "Casa Comune",
    neighbourhood: "Porta Venezia",
    distanceLabel: "2.2 km",
    attendeeCount: 16,
    attendeeInitials: ["G", "R", "S"],
    imageUrl:
      "https://images.unsplash.com/photo-1528605248644-14dd04022da1?auto=format&fit=crop&w=1000&q=84",
    imageAlt: "Friends sharing a meal around a table",
    imageCredit: "Unsplash demo image",
  },
  {
    id: "after-hours-design",
    isDemo: true,
    title: "After-hours design walk",
    eyebrow: "Small group",
    description:
      "A guided evening through independent studios, installations and overlooked details around the district.",
    category: "Culture",
    timing: "upcoming",
    dateLabel: "Thu, 15 Oct",
    timeLabel: "18:45",
    venue: "ADI Design Museum",
    neighbourhood: "Monumentale",
    distanceLabel: "3.1 km",
    attendeeCount: 12,
    attendeeInitials: ["E", "N", "T"],
    imageUrl:
      "https://images.unsplash.com/photo-1561214115-f2f134cc4912?auto=format&fit=crop&w=1000&q=84",
    imageAlt: "Visitors looking at contemporary artworks in a gallery",
    imageCredit: "Unsplash demo image",
  },
  {
    id: "sunrise-city-loop",
    isDemo: true,
    title: "Sunrise city loop",
    eyebrow: "Move together",
    description:
      "An easy-pace run along the canals, followed by coffee. First-timers are explicitly welcome.",
    category: "Outdoors",
    timing: "weekend",
    dateLabel: "Sat, 17 Oct",
    timeLabel: "07:30",
    venue: "Darsena",
    neighbourhood: "Navigli",
    distanceLabel: "2.8 km",
    attendeeCount: 34,
    attendeeInitials: ["F", "C", "D"],
    imageUrl:
      "https://images.unsplash.com/photo-1552674605-db6ffd4facb5?auto=format&fit=crop&w=1000&q=84",
    imageAlt: "A small running group training outdoors in the morning",
    imageCredit: "Unsplash demo image",
  },
];
