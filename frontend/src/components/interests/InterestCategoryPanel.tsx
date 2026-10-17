import { ChevronDown } from "lucide-react";
import { useState } from "react";

import type { InterestCategory } from "../../types/interest";

interface InterestCategoryPanelProps {
  readonly category: InterestCategory;
  readonly selectedIds: ReadonlySet<string>;
  readonly atLimit: boolean;
  readonly disabled: boolean;
  readonly initiallyOpen: boolean;
  onToggle(interestId: string): void;
}

export function InterestCategoryPanel({ category, selectedIds, atLimit, disabled, initiallyOpen, onToggle }: InterestCategoryPanelProps) {
  const [open, setOpen] = useState(initiallyOpen);
  const selectedCount = category.interests.filter((interest) => selectedIds.has(interest.id)).length;
  const contentId = `interest-category-${category.id}`;

  return (
    <section className="interest-category" data-open={open}>
      <button
        className="interest-category__trigger"
        type="button"
        aria-expanded={open}
        aria-controls={contentId}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="text-left font-extrabold text-ink">{category.name}</span>
        <span className="flex items-center gap-3">
          {selectedCount > 0 ? <span className="interest-category__count">{selectedCount} selezionati</span> : null}
          <ChevronDown className="interest-category__chevron" size={20} aria-hidden="true" />
        </span>
      </button>

      <div id={contentId} className="interest-category__content">
        <div className="overflow-hidden">
          <div className="flex flex-wrap gap-2 px-1 pb-5 pt-1">
            {category.interests.map((interest) => {
              const selected = selectedIds.has(interest.id);
              return (
                <button
                  key={interest.id}
                  type="button"
                  className="interest-chip"
                  aria-pressed={selected}
                  disabled={disabled || (atLimit && !selected)}
                  onClick={() => onToggle(interest.id)}
                >
                  {interest.name}
                </button>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
}
