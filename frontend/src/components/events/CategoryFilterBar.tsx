import { categoryOptions } from "../../lib/eventPresentation";
import type { EventCategory } from "../../types/event";
import { FilterChip } from "../ui/FilterChip";

interface CategoryFilterBarProps {
  readonly activeCategory: EventCategory | null;
  readonly onSelect: (category: EventCategory | null) => void;
}

export function CategoryFilterBar({ activeCategory, onSelect }: CategoryFilterBarProps) {
  return (
    <div className="discover-filter-bar glass scrollbar-none" role="group" aria-label="Filtra gli eventi per categoria">
      {categoryOptions.map((option) => (
        <FilterChip
          key={option.value ?? "all"}
          label={option.label}
          category={option.value ?? undefined}
          selected={activeCategory === option.value}
          onSelect={() => onSelect(option.value)}
        />
      ))}
    </div>
  );
}
