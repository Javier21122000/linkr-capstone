interface FilterChipProps {
  readonly label: string;
  readonly selected: boolean;
  readonly onSelect: () => void;
}

export function FilterChip({ label, selected, onSelect }: FilterChipProps) {
  return (
    <button
      type="button"
      className="filter-chip"
      data-selected={selected}
      aria-pressed={selected}
      onClick={onSelect}
    >
      {label}
    </button>
  );
}
