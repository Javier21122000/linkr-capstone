import { ChevronDown, Clock3 } from "lucide-react";
import { useId, useMemo, useState } from "react";

import { deviceZoneDiffersFromRome } from "../../lib/romeTime";
import { formatRomeClock } from "../../lib/quickCreateSchedule";
import type { AmbiguousChoice } from "../../lib/quickCreateSchedule";
import { WheelPicker } from "../ui/WheelPicker";

interface TimeWheelFieldProps {
  readonly id: string;
  readonly label: string;
  /** `HH:mm` (ora di Milano) oppure vuoto. */
  readonly value: string;
  readonly onChange: (time: string) => void;
  /** Scorciatoie già filtrate per il giorno scelto (mai un orario passato). */
  readonly suggestions: readonly string[];
  /** Orario da mostrare sulla ruota quando si apre senza un valore: non viene impostato finché l'utente non lo conferma o scorre. */
  readonly defaultTime: string;
  readonly error?: string;
  readonly disabled?: boolean;
  /** Per l'ora che a Milano capita due volte (cambio all'ora solare): i due istanti e quale è scelto. */
  readonly ambiguous?: { readonly first: number; readonly second: number } | null;
  readonly ambiguousChoice?: AmbiguousChoice;
  readonly onAmbiguousChoice?: (choice: AmbiguousChoice) => void;
  /** Istante di riferimento per l'avviso sul fuso del dispositivo. */
  readonly referenceInstant: number;
}

const HOURS = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, "0"));
const MINUTES = Array.from({ length: 60 }, (_, minute) => String(minute).padStart(2, "0"));

function splitTime(time: string): { readonly hour: number; readonly minute: number } {
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  return match ? { hour: Number(match[1]), minute: Number(match[2]) } : { hour: 19, minute: 0 };
}

function joinTime(hour: number, minute: number): string {
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

/**
 * Orario di inizio: scorciatoie contestuali (15:00, 19:00, 21:00) e, a richiesta, una ruota ore/minuti a 24 ore in stile iOS.
 * Qualsiasi orario valido si sceglie con la ruota; le scorciatoie la muovono, non la sostituiscono. L'ora è sempre quella di Milano.
 */
export function TimeWheelField({
  id,
  label,
  value,
  onChange,
  suggestions,
  defaultTime,
  error,
  disabled = false,
  ambiguous = null,
  ambiguousChoice = "first",
  onAmbiguousChoice,
  referenceInstant,
}: TimeWheelFieldProps) {
  const panelId = useId();
  const errorId = `${id}-error`;
  const noteId = `${id}-note`;
  const [open, setOpen] = useState(false);
  const shown = splitTime(value || defaultTime);
  const differentZone = useMemo(() => deviceZoneDiffersFromRome(referenceInstant), [referenceInstant]);

  return (
    <div className="qc-time" role="group" aria-labelledby={`${id}-label`}>
      <span id={`${id}-label`} className="form-label">{label}</span>

      {suggestions.length > 0 ? (
        <div className="mt-2 flex flex-wrap gap-2" role="group" aria-label="Orari suggeriti">
          {suggestions.map((time) => (
            <button
              key={time}
              type="button"
              className="filter-chip"
              data-selected={value === time}
              aria-pressed={value === time}
              disabled={disabled}
              onClick={() => onChange(time)}
            >
              {time}
            </button>
          ))}
        </div>
      ) : null}

      <button
        id={id}
        type="button"
        className="qc-time__row"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-describedby={[error ? errorId : undefined, noteId].filter(Boolean).join(" ")}
        data-invalid={error ? "true" : undefined}
        data-empty={value ? undefined : "true"}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
      >
        <Clock3 size={18} className="shrink-0 text-linkr-strong" aria-hidden="true" />
        <span className="min-w-0 flex-1 text-left">
          {value
            ? <><span className="sr-only">Ora scelta: </span><span className="qc-time__value">{value}</span></>
            : "Scegli un altro orario"}
        </span>
        <ChevronDown className="qc-time__chevron shrink-0 text-muted" size={18} aria-hidden="true" />
      </button>

      {open ? (
        <div id={panelId} className="qc-time__panel" role="group" aria-label="Scegli l’orario con le ruote">
          <div className="qc-time__wheels">
            <WheelPicker
              label="Ore"
              items={HOURS}
              index={shown.hour}
              pageStep={6}
              disabled={disabled}
              valueText={(hour) => `${hour} ${hour === 1 ? "ora" : "ore"}`}
              onChange={(hour) => onChange(joinTime(hour, shown.minute))}
            />
            <span className="qc-time__colon" aria-hidden="true">:</span>
            <WheelPicker
              label="Minuti"
              items={MINUTES}
              index={shown.minute}
              pageStep={10}
              disabled={disabled}
              valueText={(minute) => `${minute} ${minute === 1 ? "minuto" : "minuti"}`}
              onChange={(minute) => onChange(joinTime(shown.hour, minute))}
            />
          </div>
          <button
            type="button"
            className="button-secondary inline-flex items-center"
            onClick={() => {
              // Aperta senza un valore, la ruota mostra solo una proposta: "Imposta" la rende l'orario scelto.
              if (!value) onChange(joinTime(shown.hour, shown.minute));
              setOpen(false);
              window.requestAnimationFrame(() => document.getElementById(id)?.focus());
            }}
          >
            {value ? "Fatto" : `Imposta ${joinTime(shown.hour, shown.minute)}`}
          </button>
        </div>
      ) : null}

      {/* Dove la ruota non è utilizzabile (nessuno scroll-snap) la regola in CSS mostra solo questo campo nativo. */}
      <input
        className="form-input qc-time__native mt-2"
        type="time"
        aria-label={`${label} (campo nativo)`}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />

      {ambiguous ? (
        <div className="qc-time__ambiguous" role="group" aria-label="Quest’ora capita due volte">
          <p className="text-xs font-bold leading-5 text-ink">Il cambio all’ora solare fa capitare questa ora due volte. Quale intendi?</p>
          <div className="mt-2 flex flex-wrap gap-2">
            {([["first", ambiguous.first, "la prima"], ["second", ambiguous.second, "la seconda"]] as const).map(([choice, instant, name]) => (
              <button
                key={choice}
                type="button"
                className="filter-chip"
                data-selected={ambiguousChoice === choice}
                aria-pressed={ambiguousChoice === choice}
                disabled={disabled}
                onClick={() => onAmbiguousChoice?.(choice)}
              >
                {`${name[0].toUpperCase()}${name.slice(1)} · ${formatRomeClock(instant)}`}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {error ? <p id={errorId} className="field-error mt-2">{error}</p> : null}
      <p id={noteId} className="mt-2 text-xs leading-5 text-muted">
        {differentZone
          ? "Ora di Milano. Il tuo dispositivo è in un altro fuso orario: l’evento resta alle ore di Milano."
          : "Ora di Milano."}
      </p>
    </div>
  );
}
