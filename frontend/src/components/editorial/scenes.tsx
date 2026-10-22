import { useEffect } from "react";

import { EditorialIllustration } from "./EditorialIllustration";
import { HalftoneAccent } from "./HalftoneAccent";

/**
 * Composizioni collage: illustrazione + sticker + retino, posizionati dal CSS (styles/editorial.css). Sono decorazione pura:
 * nessuna è interattiva e nessuna si sovrappone a testo o pulsanti (occupano uno spazio loro).
 */

/**
 * Alto di "Scopri persone": collage fotografico originale (PNG con trasparenza, public/images/editorial/people-hero.png).
 * Decorativo (`alt=""`): il contenuto della pagina è già nel testo. Si scarica solo se la variante Urban Editorial Pop è attiva
 * e solo in questa pagina; `width`/`height` riservano lo spazio (nessun layout shift).
 */
const PEOPLE_HERO = { src: "/images/editorial/people-hero.png", webp: "/images/editorial/people-hero-768.webp", width: 1153, height: 1364 } as const;

export function PeopleHeroArt() {
  if (document.documentElement.dataset.variant !== "urban-editorial") return null;
  // WebP a 768 px (con alpha) per i browser che lo supportano, PNG originale come ripiego. La dimensione mostrata arriva dal CSS.
  return (
    <picture className="ed-deco ed-hero__art">
      <source srcSet={PEOPLE_HERO.webp} type="image/webp" />
      <img src={PEOPLE_HERO.src} width={PEOPLE_HERO.width} height={PEOPLE_HERO.height} alt="" decoding="async" draggable={false} />
    </picture>
  );
}

/**
 * Le tre schermate dell'onboarding (Scopri, Connettiti, Vivi) usano un collage fotografico ciascuna: PNG originale con trasparenza
 * come ripiego e WebP con alpha alla dimensione di rendering. `width`/`height` e `aspect-ratio` (CSS) riservano lo spazio.
 */
const ONBOARDING_PHOTOS = [
  { name: "discover", src: "/images/editorial/people-hero.png", webp: "/images/editorial/people-hero-768.webp", width: 1153, height: 1364 },
  { name: "connect", src: "/images/editorial/connect-hero.png", webp: "/images/editorial/connect-hero-960.webp", width: 1536, height: 1024 },
  { name: "live", src: "/images/editorial/live-hero.png", webp: "/images/editorial/live-hero-768.webp", width: 1153, height: 1364 },
] as const;

export function OnboardingPhoto({ step }: { readonly step: 0 | 1 | 2 }) {
  const photo = ONBOARDING_PHOTOS[step];
  const variantOn = document.documentElement.dataset.variant === "urban-editorial";

  // Scaricata a riposo la schermata successiva, l'unica che con ogni probabilità servirà: Salta non ne scarica altre.
  useEffect(() => {
    const next = ONBOARDING_PHOTOS[step + 1];
    if (!variantOn || !next) return undefined;
    const preload = new Image();
    preload.src = next.webp;
    return undefined;
  }, [step, variantOn]);

  if (!variantOn) return null;
  return (
    <picture className={`ed-deco ed-photo ed-photo--${photo.name}`}>
      <source srcSet={photo.webp} type="image/webp" />
      <img src={photo.src} width={photo.width} height={photo.height} alt="" decoding="async" draggable={false} />
    </picture>
  );
}

/** Angolo del profilo pubblico: il Duomo a retino e un velo di puntini, nell'angolo alto a destra, fuori dal testo. */
export function ProfileArt() {
  return (
    <div className="ed-deco ed-scene ed-scene--profile" aria-hidden="true">
      <HalftoneAccent shape="fade" tone="coral" className="ed-scene__dots" />
      <EditorialIllustration kind="duomo" className="ed-scene__main" />
    </div>
  );
}
