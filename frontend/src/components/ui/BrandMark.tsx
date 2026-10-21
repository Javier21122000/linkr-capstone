interface LinkrSymbolProps {
  readonly className?: string;
}

/**
 * Simbolo LINKR — "Meeting Point": due percorsi che si sfiorano.
 * Il tratto scuro usa `currentColor`: su sfondo scuro basta `text-white` / `text-canvas`.
 */
export function LinkrSymbol({ className = "size-8" }: LinkrSymbolProps) {
  return (
    <svg className={className} viewBox="0 0 48 48" fill="none" aria-hidden="true" focusable="false">
      <path d="M9 37C9 22 17 16 28 17" stroke="currentColor" strokeWidth="7" strokeLinecap="round" />
      <path d="M39 11C39 26 31 32 20 31" stroke="var(--color-linkr)" strokeWidth="7" strokeLinecap="round" />
      <circle cx="9" cy="37" r="5.5" fill="currentColor" />
      <circle cx="39" cy="11" r="5.5" fill="var(--color-linkr)" />
    </svg>
  );
}

interface BrandMarkProps {
  /** Solo simbolo, senza wordmark. */
  readonly compact?: boolean;
  readonly className?: string;
}

export function BrandMark({ compact = false, className = "" }: BrandMarkProps) {
  return (
    <span className={`inline-flex items-center gap-2 text-ink ${className}`} aria-label="LINKR">
      <LinkrSymbol className="size-8 shrink-0" />
      {!compact && (
        <span className="text-base font-extrabold tracking-[0.14em] sm:text-lg" aria-hidden="true">
          LINKR
        </span>
      )}
    </span>
  );
}
