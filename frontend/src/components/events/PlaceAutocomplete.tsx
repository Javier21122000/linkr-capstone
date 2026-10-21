import { LoaderCircle, MapPin, RotateCcw, Search } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import type { ChangeEvent, FocusEvent, KeyboardEvent } from "react";

import {
  getPlaceSearchErrorMessage,
  searchMilanPlaces,
  type PlaceSuggestion,
} from "../../lib/geocoding";

const MIN_QUERY_LENGTH = 3;
const DEBOUNCE_MS = 300;

type SearchStatus = "idle" | "loading" | "success" | "error";

interface PlaceAutocompleteProps {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly error?: string;
  readonly disabled?: boolean;
  /** Per chi scrive un nome libero (es. punto scelto sulla mappa): "Nessun luogo trovato" non è un errore e non va mostrato. */
  readonly hideEmptyNotice?: boolean;
  readonly onChange: (value: string) => void;
  readonly onSelect: (suggestion: PlaceSuggestion) => void;
}

export function PlaceAutocomplete({
  id,
  label,
  value,
  error,
  disabled = false,
  hideEmptyNotice = false,
  onChange,
  onSelect,
}: PlaceAutocompleteProps) {
  const listboxId = useId();
  const statusId = useId();
  const wrapperRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const requestIdRef = useRef(0);
  const hasUserInputRef = useRef(false);
  const [retryKey, setRetryKey] = useState(0);
  const [status, setStatus] = useState<SearchStatus>("idle");
  const [results, setResults] = useState<readonly PlaceSuggestion[]>([]);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);

  useEffect(() => {
    const query = value.trim();
    const requestId = ++requestIdRef.current;
    if (!hasUserInputRef.current || disabled || query.length < MIN_QUERY_LENGTH) {
      setStatus("idle");
      setResults([]);
      setSearchError(null);
      setOpen(false);
      setActiveIndex(-1);
      return undefined;
    }

    const controller = new AbortController();
    setStatus("idle");
    setResults([]);
    setSearchError(null);
    setActiveIndex(-1);
    const timeoutId = window.setTimeout(() => {
      if (requestId !== requestIdRef.current) return;
      setStatus("loading");
      setOpen(true);
      void searchMilanPlaces(query, controller.signal)
        .then((suggestions) => {
          if (requestId !== requestIdRef.current) return;
          setResults(suggestions);
          setStatus("success");
          setOpen(true);
          setActiveIndex(suggestions.length > 0 ? 0 : -1);
        })
        .catch((reason: unknown) => {
          if (controller.signal.aborted || requestId !== requestIdRef.current) return;
          setResults([]);
          setStatus("error");
          setSearchError(getPlaceSearchErrorMessage(reason));
          setOpen(true);
          setActiveIndex(-1);
        });
    }, DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timeoutId);
      controller.abort();
    };
  }, [disabled, retryKey, value]);

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    hasUserInputRef.current = true;
    onChange(event.target.value);
  }

  function selectSuggestion(suggestion: PlaceSuggestion) {
    hasUserInputRef.current = false;
    requestIdRef.current += 1;
    setOpen(false);
    setActiveIndex(-1);
    setStatus("idle");
    setResults([]);
    setSearchError(null);
    onSelect(suggestion);
    window.requestAnimationFrame(() => inputRef.current?.focus());
  }

  function handleKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      if (open) event.preventDefault();
      setOpen(false);
      setActiveIndex(-1);
      return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      if (results.length === 0) return;
      event.preventDefault();
      setOpen(true);
      setActiveIndex((current) => {
        if (event.key === "ArrowDown") return current < results.length - 1 ? current + 1 : 0;
        return current > 0 ? current - 1 : results.length - 1;
      });
      return;
    }
    if (event.key === "Enter" && open && activeIndex >= 0) {
      const suggestion = results[activeIndex];
      if (!suggestion) return;
      event.preventDefault();
      selectSuggestion(suggestion);
    }
  }

  function handleBlur(event: FocusEvent<HTMLDivElement>) {
    const nextFocus = event.relatedTarget;
    if (nextFocus instanceof Node && wrapperRef.current?.contains(nextFocus)) return;
    setOpen(false);
    setActiveIndex(-1);
  }

  const activeOptionId = open && activeIndex >= 0 ? `${listboxId}-option-${activeIndex}` : undefined;
  const describedBy = [error ? `${id}-error` : undefined, statusId].filter(Boolean).join(" ");
  const showPanel = open && results.length > 0;

  return (
    <div ref={wrapperRef} className="relative" onBlur={handleBlur}>
      <label className="form-label" htmlFor={id}>{label}</label>
      <div className="relative mt-2">
        <Search className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" size={17} aria-hidden="true" />
        <input
          ref={inputRef}
          id={id}
          name={id}
          className="form-input pl-10 pr-10"
          type="text"
          value={value}
          onChange={handleChange}
          onFocus={() => {
            if (results.length > 0 || status === "loading" || status === "error") setOpen(true);
          }}
          onKeyDown={handleKeyDown}
          role="combobox"
          aria-autocomplete="list"
          aria-expanded={showPanel}
          aria-controls={showPanel ? listboxId : undefined}
          aria-activedescendant={activeOptionId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          aria-busy={status === "loading"}
          autoComplete="off"
          maxLength={120}
          placeholder="Cerca un luogo o un indirizzo"
          disabled={disabled}
        />
        {status === "loading" ? (
          <LoaderCircle className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 animate-spin text-muted motion-reduce:animate-none" size={17} aria-hidden="true" />
        ) : null}
      </div>

      {error ? <p id={`${id}-error`} className="field-error mt-2">{error}</p> : null}
      <div id={statusId} className="mt-2 min-h-5 text-xs font-semibold leading-5 text-muted" role="status" aria-live="polite">
        {status === "loading" ? "Ricerca luoghi…" : null}
        {status === "success" && results.length === 0 && !hideEmptyNotice ? "Nessun luogo trovato nell’area di Milano." : null}
        {status === "error" ? (
          <span>
            {searchError}
            <button
              className="ml-2 inline-flex items-center gap-1 font-extrabold text-ink"
              type="button"
              onClick={() => setRetryKey((current) => current + 1)}
            >
              <RotateCcw size={13} aria-hidden="true" /> Riprova
            </button>
          </span>
        ) : null}
        {status === "idle" && value.trim().length > 0 && value.trim().length < MIN_QUERY_LENGTH
          ? `Digita almeno ${MIN_QUERY_LENGTH} caratteri per cercare.`
          : null}
      </div>

      {showPanel && results.length > 0 ? (
        <div data-place-results="" className="absolute inset-x-0 top-full z-30 mt-1 overflow-hidden rounded-xl border border-line bg-surface shadow-xl">
          <ul id={listboxId} role="listbox" aria-label="Suggerimenti luoghi" className="max-h-72 overflow-y-auto py-1">
            {results.map((suggestion, index) => (
              <li
                id={`${listboxId}-option-${index}`}
                key={suggestion.id}
                role="option"
                aria-selected={index === activeIndex}
                className={`flex cursor-pointer items-start gap-3 px-3 py-3 text-left ${index === activeIndex ? "bg-linkr-soft" : "hover:bg-canvas"}`}
                onMouseDown={(event) => event.preventDefault()}
                onMouseMove={() => setActiveIndex(index)}
                onClick={() => selectSuggestion(suggestion)}
              >
                <MapPin className="mt-0.5 shrink-0 text-linkr-strong" size={17} aria-hidden="true" />
                <span className="min-w-0">
                  <span className="block font-extrabold text-ink">{suggestion.venueName}</span>
                  {suggestion.label !== suggestion.venueName ? (
                    <span className="mt-0.5 block text-xs font-semibold leading-5 text-muted">{suggestion.label}</span>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
