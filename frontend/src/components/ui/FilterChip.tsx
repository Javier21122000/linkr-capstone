import { CategoryDisc } from "../events/CategoryIcon";
import type { EventCategory } from "../../types/event";

interface FilterChipProps {
  readonly label: string;
  /** Se presente mostra il disco con l'icona della categoria. */
  readonly category?: EventCategory;
  readonly selected: boolean;
  readonly onSelect: () => void;
}

export function FilterChip({ label, category, selected, onSelect }: FilterChipProps) {
  return (
    <button
      type="button"
      className="filter-chip"
      data-selected={selected}
      aria-pressed={selected}
      onClick={onSelect}
    >
      {category && <CategoryDisc category={category} size={14} />}
      {label}
    </button>
  );
}
