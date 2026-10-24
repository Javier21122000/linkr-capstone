import { HAND_PATH, LOCKUP, WORDMARK_PATH, WORDMARK_SIZE } from "./brandPaths";

interface LogoProps {
  readonly className?: string;
}

const COMMON = { fill: "currentColor", "aria-hidden": true, focusable: "false" } as const;

/** Le due mani del simbolo statico (intestazione, onboarding, errori): nessuna animazione. */
function Hands() {
  return (
    <>
      <g transform={LOCKUP.bottomHand}><path d={HAND_PATH} /></g>
      <g transform={LOCKUP.topHand}><path d={HAND_PATH} /></g>
    </>
  );
}

/**
 * Simbolo LINKR: due mani che si cercano e incorniciano uno spazio. Solo tracciati, senza font; `currentColor` (nero inchiostro di base,
 * chiaro su sfondo scuro).
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

/**
 * Logo completo: le mani incorniciano il wordmark. È fatto di tre strati sovrapposti (mano in basso, mano in alto, wordmark), ciascuno un
 * contenitore HTML con il suo SVG, tutti con lo stesso viewBox: così l'animazione (splash.css) muove elementi HTML (transform/opacity,
 * gestiti dal compositore, quindi fluidi anche quando il thread principale è occupato dall'avvio dell'app) invece di gruppi SVG, che il
 * browser ridisegna a ogni fotogramma. Il disegno è identico a quello del simbolo statico (stesse trasformazioni di LOCKUP).
 */
export function LinkrLockup({ className = "" }: LogoProps) {
  return (
    <span className={`linkr-lockup ${className}`.trim()} aria-hidden="true">
      <span className="linkr-layer linkr-hand linkr-hand--bottom">
        <svg viewBox={LOCKUP.viewBox} {...COMMON}><g transform={LOCKUP.bottomHand}><path d={HAND_PATH} /></g></svg>
      </span>
      <span className="linkr-layer linkr-hand linkr-hand--top">
        <svg viewBox={LOCKUP.viewBox} {...COMMON}><g transform={LOCKUP.topHand}><path d={HAND_PATH} /></g></svg>
      </span>
      <span className="linkr-layer linkr-word">
        <svg viewBox={LOCKUP.viewBox} {...COMMON}><g transform={LOCKUP.wordmark}><path d={WORDMARK_PATH} /></g></svg>
      </span>
    </span>
  );
}
