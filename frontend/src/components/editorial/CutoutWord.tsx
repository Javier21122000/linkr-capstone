import type { CSSProperties } from "react";

/**
 * Parola in "lettering ritagliato" (stile lettera di riscatto): ogni lettera è un pezzetto di carta con carattere, colore e angolo suoi.
 * Si usa in pochissimi momenti speciali (onboarding). Accessibilità: il testo leggibile resta nel DOM (`ed-cutout__plain`) e le lettere
 * decorative sono `aria-hidden`. Nel tema base si vede solo il testo normale.
 */
interface CutoutWordProps {
  readonly word: string;
  /** Sposta la sequenza di stili: parole vicine non cominciano con la stessa lettera dello stesso colore. */
  readonly seed?: number;
  readonly className?: string;
}

const TILE_STYLES = [
  "paper-serif",
  "ink-sans",
  "yellow-italic",
  "coral-serif",
  "pink-serif",
  "ink-serif",
  "paper-italic",
  "yellow-sans",
] as const;

const ROTATIONS = [-4, 3, -2, 5, -3, 2, -5, 4, -1, 3] as const;
const LIFTS = [0, -0.06, 0.05, -0.03, 0.06, 0, -0.05, 0.03] as const;
const EDGES = [0, 1, 2, 3] as const;

export function CutoutWord({ word, seed = 0, className = "" }: CutoutWordProps) {
  const letters = [...word];
  return (
    <span className={`ed-cutout ${className}`.trim()} style={{ "--letters": letters.length } as CSSProperties}>
      <span className="ed-cutout__plain">{word}</span>
      <span className="ed-cutout__tiles ed-deco" aria-hidden="true">
        {letters.map((letter, index) => {
          const position = index + seed;
          return (
            <span
              className="ed-cutout__tile"
              data-tile={TILE_STYLES[(position * 3 + 1) % TILE_STYLES.length]}
              data-edge={EDGES[(position * 2 + 1) % EDGES.length]}
              style={{ "--tile-rotate": `${ROTATIONS[position % ROTATIONS.length]}deg`, "--tile-lift": `${LIFTS[position % LIFTS.length]}em` } as CSSProperties}
              key={`${letter}-${index}`}
            >
              {letter}
            </span>
          );
        })}
      </span>
    </span>
  );
}
