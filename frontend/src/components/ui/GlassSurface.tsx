import { createElement } from "react";
import type { HTMLAttributes } from "react";

interface GlassSurfaceProps extends HTMLAttributes<HTMLElement> {
  readonly as?: "div" | "section" | "article" | "aside" | "nav" | "header";
  /** `regular` per controlli sopra la mappa, `strong` per contenuti informativi, `panel` per pannelli grandi con molto testo, `dark` per sfondi scuri. */
  readonly tone?: "regular" | "strong" | "panel" | "dark";
}

const toneClass = {
  regular: "glass",
  strong: "glass glass--strong",
  panel: "glass glass--panel",
  dark: "glass glass--dark",
} as const;

/** Superficie Liquid Glass: materiali in `styles/tokens.css`, fallback e preferenze di accessibilità in `styles/glass.css`. */
export function GlassSurface({ as = "div", tone = "regular", className = "", ...props }: GlassSurfaceProps) {
  return createElement(as, { ...props, className: `${toneClass[tone]} ${className}`.trim() });
}
