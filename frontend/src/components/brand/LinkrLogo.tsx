import { HAND_PATH, LOCKUP, WORDMARK_PATH, WORDMARK_SIZE } from "./brandPaths";

interface LogoProps {
  readonly className?: string;
}

const COMMON = { fill: "currentColor", "aria-hidden": true, focusable: "false" } as const;

/** Le due mani, dentro i gruppi che l'animazione sposta (`.linkr-hand`); la posizione fissa sta nel gruppo interno. */
function Hands() {
  return (
    <>
      <g className="linkr-hand linkr-hand--bottom"><g transform={LOCKUP.bottomHand}><path d={HAND_PATH} /></g></g>
      <g className="linkr-hand linkr-hand--top"><g transform={LOCKUP.topHand}><path d={HAND_PATH} /></g></g>
    </>
  );
}

/**
 * Simbolo LINKR: due mani che si cercano e incorniciano uno spazio. Solo tracciati, senza font; `currentColor` (nero inchiostro di base,
 * chiaro su sfondo scuro). Le classi `.linkr-hand--top` / `--bottom` servono alle animazioni (splash e caricamento, vedi splash.css).
 */
export function LinkrHands({ className }: LogoProps) {
  return (
    <svg className={className} viewBox={LOCKUP.viewBox} {...COMMON}>
      <Hands />
    </svg>
  );
}

/** Wordmark «linkr», disegnato come tracciato (nessun font da caricare). */
export function LinkrWordmark({ className }: LogoProps) {
  return (
    <svg className={className} viewBox={`0 0 ${WORDMARK_SIZE.width} ${WORDMARK_SIZE.height}`} {...COMMON}>
      <path d={WORDMARK_PATH} />
    </svg>
  );
}

/** Logo completo: le mani incorniciano il wordmark. `.linkr-word` serve all'animazione d'ingresso. */
export function LinkrLockup({ className }: LogoProps) {
  return (
    <svg className={className} viewBox={LOCKUP.viewBox} {...COMMON}>
      <Hands />
      <g className="linkr-word"><g transform={LOCKUP.wordmark}><path d={WORDMARK_PATH} /></g></g>
    </svg>
  );
}
