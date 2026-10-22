import type { CSSProperties, ReactNode } from "react";

/**
 * Sticker/ritagli piccoli, disegnati in SVG o CSS: fumetto, puntina, scarica gialla e striscia di nastro adesivo.
 * Niente stelle né scintille: solo oggetti che c'entrano con l'app (chat, luoghi, eventi). Solo decorazione (`aria-hidden`).
 */
export type StickerKind = "bubble" | "pin" | "burst" | "tape";

interface EditorialStickerProps {
  readonly kind: StickerKind;
  /** Rotazione in gradi: ogni pezzo è appoggiato a mano. */
  readonly rotate?: number;
  readonly className?: string;
  readonly style?: CSSProperties;
}

const SHAPES: Record<Exclude<StickerKind, "tape">, { readonly viewBox: string; readonly body: ReactNode }> = {
  bubble: {
    viewBox: "0 0 120 100",
    body: (
      <>
        <path d="M14 10 Q10 8 14 6 L98 6 Q112 6 112 22 V58 Q112 74 98 74 H52 L26 94 L30 74 H22 Q8 74 8 58 V24 Z" fill="#fff" stroke="#fff" strokeWidth="9" strokeLinejoin="round" />
        <path d="M14 10 Q10 8 14 6 L98 6 Q112 6 112 22 V58 Q112 74 98 74 H52 L26 94 L30 74 H22 Q8 74 8 58 V24 Z" fill="#f6f1e4" stroke="#181816" strokeWidth="2.4" strokeLinejoin="round" />
        <g fill="#181816"><circle cx="38" cy="40" r="6.5" /><circle cx="60" cy="40" r="6.5" /><circle cx="82" cy="40" r="6.5" /></g>
      </>
    ),
  },
  pin: {
    viewBox: "0 0 60 96",
    body: (
      <>
        <path d="M30 4 C14 4 6 16 6 28 C6 48 30 74 30 92 C30 74 54 48 54 28 C54 16 46 4 30 4 Z" fill="#fff" stroke="#fff" strokeWidth="9" strokeLinejoin="round" />
        <path d="M30 4 C14 4 6 16 6 28 C6 48 30 74 30 92 C30 74 54 48 54 28 C54 16 46 4 30 4 Z" fill="#e84d2f" stroke="#181816" strokeWidth="2.4" strokeLinejoin="round" />
        <circle cx="30" cy="28" r="10" fill="#f6f1e4" stroke="#181816" strokeWidth="2.4" />
      </>
    ),
  },
  burst: {
    viewBox: "0 0 100 120",
    body: <path d="M62 4 L24 56 H48 L30 116 L82 46 H56 Z" fill="#f6cf4d" stroke="#181816" strokeWidth="2.4" strokeLinejoin="round" />,
  },
};

export function EditorialSticker({ kind, rotate = 0, className = "", style }: EditorialStickerProps) {
  const merged = { rotate: `${rotate}deg`, ...style } as CSSProperties;
  if (kind === "tape") return <span className={`ed-deco ed-sticker ed-sticker--tape ${className}`.trim()} style={merged} aria-hidden="true" />;
  const { viewBox, body } = SHAPES[kind];
  return (
    <svg className={`ed-deco ed-sticker ed-sticker--${kind} ${className}`.trim()} style={merged} viewBox={viewBox} aria-hidden="true" focusable="false">
      {body}
    </svg>
  );
}
