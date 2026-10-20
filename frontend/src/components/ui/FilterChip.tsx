interface FilterChipProps {
  readonly label: string;
  readonly indicatorColor?: string;
  readonly selected: boolean;
  readonly onSelect: () => void;
}

export function FilterChip({ label, indicatorColor, selected, onSelect }: FilterChipProps) {
  return (
    <button
      type="button"
      className="filter-chip"
      data-selected={selected}
      aria-pressed={selected}
      onClick={onSelect}
    >
      {label}
      {indicatorColor && (
        <span className="filter-chip__indicator" style={{ backgroundColor: indicatorColor }} aria-hidden="true" />
      )}
    </button>
  );
}
