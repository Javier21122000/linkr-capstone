import { createElement } from "react";
import type { HTMLAttributes } from "react";

interface GlassSurfaceProps extends HTMLAttributes<HTMLElement> {
  readonly as?: "div" | "section" | "article" | "aside" | "nav" | "header";
  /** `strong` per contenuti informativi, `dark` per superfici su sfondi scuri. */
  readonly tone?: "regular" | "strong" | "dark";
}

const toneClass = {
  regular: "glass",
  strong: "glass glass--strong",
  dark: "glass glass--dark",
} as const;

/** Superficie Liquid Glass: vedi `styles/glass.css` per fallback e preferenze di accessibilità. */
export function GlassSurface({ as = "div", tone = "regular", className = "", ...props }: GlassSurfaceProps) {
  return createElement(as, { ...props, className: `${toneClass[tone]} ${className}`.trim() });
}
