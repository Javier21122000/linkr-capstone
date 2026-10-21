import { useRef } from "react";

import { useSlidingIndicator } from "../../hooks/useSlidingIndicator";

interface SegmentedOption<Id extends string> {
  readonly id: Id;
  readonly label: string;
}

interface SegmentedControlProps<Id extends string> {
  /** Nome del gruppo, letto da chi usa uno screen reader. */
  readonly label: string;
  readonly options: readonly SegmentedOption<Id>[];
  readonly value: Id;
  readonly onChange: (value: Id) => void;
  readonly className?: string;
}

/**
 * Selettore a capsula in vetro con l'indicatore che scorre sotto la voce scelta (stesso linguaggio dei filtri di Discover).
 * Ogni voce è un pulsante `aria-pressed`: è un filtro sulla stessa pagina, non una navigazione.
 */
export function SegmentedControl<Id extends string>({ label, options, value, onChange, className = "" }: SegmentedControlProps<Id>) {
  const trackRef = useRef<HTMLDivElement>(null);
  useSlidingIndicator({ trackRef, activeSelector: '[aria-pressed="true"]', activeKey: value });

  return (
    <div ref={trackRef} className={`segmented glass ${className}`.trim()} role="group" aria-label={label}>
      <span className="segmented__thumb" aria-hidden="true" />
      {options.map((option) => (
        <button
          key={option.id}
          className="segmented__option"
          type="button"
          aria-pressed={value === option.id}
          onClick={() => onChange(option.id)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}
