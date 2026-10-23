import { LinkrHands, LinkrWordmark } from "../brand/LinkrLogo";

interface LinkrSymbolProps {
  readonly className?: string;
}

/**
 * Simbolo LINKR: due mani che incorniciano uno spazio (vedi components/brand). Il colore è `currentColor`: nero di base, su sfondo scuro basta
 * `text-white` / `text-canvas`. La classe di altezza/larghezza la decide il chiamante: il simbolo è più alto che largo (circa 0,78:1).
 */
export function LinkrSymbol({ className = "h-8 w-auto" }: LinkrSymbolProps) {
  return <LinkrHands className={className} />;
}

interface BrandMarkProps {
  /** Solo simbolo, senza wordmark. */
  readonly compact?: boolean;
  readonly className?: string;
}

export function BrandMark({ compact = false, className = "" }: BrandMarkProps) {
  return (
    <span className={`inline-flex items-center gap-2.5 text-ink ${className}`} aria-label="LINKR">
      <LinkrSymbol className="h-8 w-auto shrink-0" />
      {!compact && <LinkrWordmark className="h-[1.15rem] w-auto shrink-0 sm:h-5" />}
    </span>
  );
}
