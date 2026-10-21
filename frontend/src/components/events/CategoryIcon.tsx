import { Footprints, Landmark, Martini, Music, TreePine, UsersRound, Utensils } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { CSSProperties } from "react";

import { categoryColors, unknownCategoryColor } from "../../lib/eventPresentation";
import type { EventCategory } from "../../types/event";

export const categoryIcons: Record<EventCategory, LucideIcon> = {
  MUSIC: Music,
  FOOD: Utensils,
  CULTURE: Landmark,
  OUTDOORS: TreePine,
  NIGHTLIFE: Martini,
  SPORT: Footprints,
  SOCIAL: UsersRound,
};

interface CategoryIconProps {
  readonly category: EventCategory;
  readonly size?: number;
  readonly strokeWidth?: number;
}

export function CategoryIcon({ category, size = 16, strokeWidth = 2.2 }: CategoryIconProps) {
  const Icon = categoryIcons[category];
  return <Icon size={size} strokeWidth={strokeWidth} aria-hidden="true" />;
}

/** Disco colorato con l'icona categoria: stesso linguaggio dei marker in mappa. */
export function CategoryDisc({ category, size = 15, className = "" }: CategoryIconProps & { readonly className?: string }) {
  return (
    <span
      className={`category-disc ${className}`.trim()}
      style={{ "--category-color": categoryColors[category] ?? unknownCategoryColor } as CSSProperties}
      aria-hidden="true"
    >
      <CategoryIcon category={category} size={size} />
    </span>
  );
}
