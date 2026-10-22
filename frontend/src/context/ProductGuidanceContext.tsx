import { ArrowLeft, ArrowRight, BookOpen, Building2, Flag, HelpCircle, LockKeyhole, Play, X } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { CSSProperties, KeyboardEvent, ReactNode } from "react";
import { createPortal } from "react-dom";
import { useLocation } from "react-router";

import { CutoutWord } from "../components/editorial/CutoutWord";
import { OnboardingPhoto } from "../components/editorial/scenes";
import { useBootReady } from "../hooks/useBootReady";
import { LinkrSymbol } from "../components/ui/BrandMark";
import { isOnboardingComplete, markOnboardingComplete, placeTourCard, tourStartDelay, WELCOME_DELAY_MS } from "../lib/guidance";
import type { Rect, TourPlacement } from "../lib/guidance";
import { useAuth } from "./AuthContext";

type Layer = "welcome" | "onboarding" | "tour" | "help" | "about" | "report" | "privacy" | null;

interface ProductGuidanceValue {
  readonly openHelp: () => void;
  readonly openOnboarding: () => void;
  /** Avvia il tour sui soli elementi presenti adesso; senza nessun elemento non fa nulla. */
  readonly openTour: () => void;
  /** Discover lo chiama quando è davvero pronto (visibile, eventi arrivati, mappa finita di caricare): è il momento in cui può partire il tour. */
  readonly notifyDiscoverReady: (ready: boolean) => void;
}

const ProductGuidanceContext = createContext<ProductGuidanceValue | null>(null);
const FOCUSABLE = "a[href],button:not(:disabled),input:not(:disabled),select:not(:disabled),textarea:not(:disabled),[tabindex]:not([tabindex='-1'])";

/** Tre schermate: la parola in lettering ritagliato (`word`) è il titolo accessibile, il resto lo spiega. */
const onboardingSteps = [
  { word: "SCOPRI", seed: 0, headline: "Eventi reali, luoghi interessanti.", body: "Esplora la mappa di Milano e scopri cosa succede intorno a te, ora e nei prossimi giorni." },
  { word: "CONNETTITI", seed: 2, headline: "Persone con i tuoi stessi interessi.", body: "Trova chi condivide la tua passione, invia un Link e inizia a chattare." },
  { word: "VIVI", seed: 5, headline: "Una città più tua.", body: "Esci, partecipa agli eventi o organizzane uno tuo, e crea nuovi ricordi." },
] as const;

interface TourStepDefinition {
  readonly id: string;
  readonly title: string;
  readonly body: string;
  /** L'elemento da evidenziare adesso, oppure `null` se qui non c'è: un passo senza elemento non fa parte del tour. */
  readonly find: () => HTMLElement | null;
}

function isShown(element: HTMLElement): boolean {
  const bounds = element.getBoundingClientRect();
  if (bounds.width === 0 || bounds.height === 0) return false;
  if (bounds.bottom <= 0 || bounds.right <= 0 || bounds.top >= window.innerHeight || bounds.left >= window.innerWidth) return false;
  const style = getComputedStyle(element);
  return style.visibility !== "hidden" && style.display !== "none" && style.opacity !== "0";
}

function findShown(selector: string): HTMLElement | null {
  return [...document.querySelectorAll<HTMLElement>(selector)].find(isShown) ?? null;
}

/** Un marker vero, in vista e non coperto da filtri, pannello, controlli o navigazione (che dichiarano il proprio ingombro con `data-map-inset`). */
function findFreeMarker(): HTMLElement | null {
  const overlays = [...document.querySelectorAll<HTMLElement>("[data-map-inset]")].filter(isShown).map((element) => element.getBoundingClientRect());
  const centerY = window.innerHeight / 2;
  const candidates = [...document.querySelectorAll<HTMLElement>(".event-marker")].filter((marker) => {
    if (!isShown(marker)) return false;
    const box = marker.getBoundingClientRect();
    if (box.left < 8 || box.top < 8 || box.right > window.innerWidth - 8 || box.bottom > window.innerHeight - 8) return false;
    return !overlays.some((overlay) => box.left < overlay.right && box.right > overlay.left && box.top < overlay.bottom && box.bottom > overlay.top);
  });
  // Si preferisce il marker più lontano dal centro verticale: il callout trova spazio dalla parte opposta senza coprirlo (decisivo sugli schermi bassi).
  const offCenter = (element: HTMLElement) => {
    const box = element.getBoundingClientRect();
    return Math.abs(box.top + box.height / 2 - centerY);
  };
  return candidates.sort((left, right) => offCenter(right) - offCenter(left))[0] ?? null;
}

const tourSteps: readonly TourStepDefinition[] = [
  { id: "categories", title: "Scegli cosa ti interessa", body: "Le categorie filtrano la mappa e la lista nello stesso momento.", find: () => findShown("[data-tour='categories']") },
  { id: "marker", title: "Gli eventi sono sulla mappa", body: "Ogni marker è un evento. Toccalo per l’anteprima (quando, dove, di cosa si tratta) e da lì apri il dettaglio.", find: findFreeMarker },
  { id: "list", title: "Gli eventi, in lista", body: "Qui trovi tutto ciò che succede, in ordine di inizio. Un tocco sulla riga porta l’evento in mappa.", find: () => findShown("[data-tour='list']") },
  { id: "people", title: "Scopri persone", body: "Incontra chi ha i tuoi stessi interessi: per prime vedi le persone con più cose in comune.", find: () => findShown("[data-tour='nav-people']") },
  { id: "links", title: "Link e chat", body: "Invia un Link a chi ti incuriosisce: quando lo accetta potete scrivervi in chat e organizzarvi.", find: () => findShown("[data-tour='nav-links']") },
  { id: "quick-create", title: "Organizza qualcosa", body: "Il pulsante + apre Quick Create: un’uscita in tre passaggi, senza lasciare la mappa.", find: () => findShown("[data-tour='quick-create']") },
  { id: "help", title: "L’aiuto è sempre qui", body: "Il punto interrogativo riapre questo tour e il Centro assistenza.", find: () => findShown("[data-tour='help']") },
];

/** Il tour usa solo i passi il cui elemento esiste adesso (su mobile e desktop, da anonimi o autenticati, l'elenco cambia di conseguenza). */
function resolveTourSteps(): readonly TourStepDefinition[] {
  return tourSteps.filter((step) => step.find() !== null);
}

function getStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

function Modal({ labelledBy, describedBy, onClose, children, className = "", backdropClassName = "" }: { readonly labelledBy: string; readonly describedBy?: string; readonly onClose: () => void; readonly children: ReactNode; readonly className?: string; readonly backdropClassName?: string }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const previousFocusRef = useRef<HTMLElement | null>(null);

  useLayoutEffect(() => {
    previousFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const panel = panelRef.current;
    // Il pulsante principale (data-autofocus) riceve il focus: Invio basta per proseguire. Altrimenti il primo elemento attivabile.
    const first = panel?.querySelector<HTMLElement>("[data-autofocus]") ?? panel?.querySelector<HTMLElement>(FOCUSABLE);
    (first ?? panel)?.focus({ preventScroll: true });
    return () => previousFocusRef.current?.focus({ preventScroll: true });
  }, []);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const panel = panelRef.current;
    if (!panel) return;
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;
    const items = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((item) => item.getClientRects().length > 0);
    const first = items[0];
    const last = items.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && (document.activeElement === first || document.activeElement === panel)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return createPortal(
    <div className={`guidance-backdrop ${backdropClassName}`.trim()} role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div ref={panelRef} className={`guidance-dialog glass glass--sheet ${className}`} role="dialog" aria-modal="true" aria-labelledby={labelledBy} aria-describedby={describedBy} tabIndex={-1} onKeyDown={handleKeyDown}>
        {children}
      </div>
    </div>,
    document.body,
  );
}

function Onboarding({ step, onStep, onSkip, onFinish }: { readonly step: number; readonly onStep: (step: number) => void; readonly onSkip: () => void; readonly onFinish: (startTour: boolean) => void }) {
  const current = onboardingSteps[step];
  const isLast = step === onboardingSteps.length - 1;
  return (
    <Modal labelledBy="onboarding-title" describedBy="onboarding-copy" onClose={onSkip} className="onboarding-dialog" backdropClassName="guidance-backdrop--onboarding">
      <div className="ob-top">
        <span className="ob-brand" aria-hidden="true"><LinkrSymbol className="size-7" /><span>LINKR</span></span>
        <button className="guidance-skip" type="button" onClick={onSkip}>Salta<span className="sr-only"> onboarding</span></button>
      </div>
      <div className="ob-body">
        <div className="ob-stage" key={step}>
          <h2 id="onboarding-title" className="ob-word"><CutoutWord word={current.word} seed={current.seed} /></h2>
          <OnboardingPhoto step={step as 0 | 1 | 2} />
        </div>
        <div id="onboarding-copy" className="ob-copy" key={`copy-${step}`}>
          <p className="ob-headline">{current.headline}</p>
          <p className="ob-text">{current.body}</p>
        </div>
      </div>
      <footer className="ob-bar" data-last={isLast || undefined}>
        <div className="guidance-progress" role="img" aria-label={`Passaggio ${step + 1} di ${onboardingSteps.length}`}>
          {onboardingSteps.map((item, index) => <span key={item.word} data-current={index === step || undefined} />)}
        </div>
        <div className="ob-actions">
          {step > 0 ? <button className="ob-back" type="button" onClick={() => onStep(step - 1)}><ArrowLeft size={17} aria-hidden="true" /> <span className="ob-back__label">Indietro</span></button> : null}
          <button className="ob-next" type="button" data-autofocus="" data-last={isLast || undefined} aria-label={isLast ? undefined : "Avanti"} onClick={isLast ? () => onFinish(true) : () => onStep(step + 1)}>
            {isLast ? <span>Scopri come funziona</span> : null}
            <ArrowRight size={20} strokeWidth={2.6} aria-hidden="true" />
          </button>
          {isLast ? <button className="ob-secondary" type="button" onClick={() => onFinish(false)}>Esplora Milano</button> : null}
        </div>
      </footer>
    </Modal>
  );
}

interface TourLayout extends TourPlacement {
  readonly width: number;
}

const TOUR_CARD_MAX_WIDTH = 368;
const TOUR_VIEWPORT_MARGIN = 12;
const HIGHLIGHT_PADDING = 6;

/** Margini di sicurezza (notch, barra di navigazione di iOS) letti dal CSS: `env()` non si legge da JavaScript, quindi si misura una sonda. */
function readSafeInsets(): { readonly top: number; readonly bottom: number } {
  const probe = document.createElement("div");
  probe.style.cssText = "position:fixed;top:0;left:0;visibility:hidden;pointer-events:none;padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)";
  document.body.append(probe);
  const style = getComputedStyle(probe);
  const insets = { top: parseFloat(style.paddingTop) || 0, bottom: parseFloat(style.paddingBottom) || 0 };
  probe.remove();
  return insets;
}

function Tour({ steps, step, onStep, onClose }: { readonly steps: readonly TourStepDefinition[]; readonly step: number; readonly onStep: (step: number) => void; readonly onClose: () => void }) {
  const [target, setTarget] = useState<Rect | null>(null);
  const [layout, setLayout] = useState<TourLayout | null>(null);
  const cardRef = useRef<HTMLElement>(null);
  const current = steps[step];
  const isLast = step === steps.length - 1;

  // Posizione dell'elemento evidenziato e del callout. Si rimisura a ogni passo, al resize, allo scroll e se il callout cambia altezza.
  useLayoutEffect(() => {
    const card = cardRef.current;
    if (!card) return undefined;

    const update = () => {
      const element = current.find();
      const bounds = element?.getBoundingClientRect() ?? null;
      const rect: Rect | null = bounds ? { left: bounds.left, top: bounds.top, width: bounds.width, height: bounds.height } : null;
      const width = Math.min(TOUR_CARD_MAX_WIDTH, window.innerWidth - 2 * TOUR_VIEWPORT_MARGIN);
      card.style.width = `${width}px`;
      const height = card.getBoundingClientRect().height;
      const insets = readSafeInsets();
      const framed = rect ? { left: rect.left - HIGHLIGHT_PADDING, top: rect.top - HIGHLIGHT_PADDING, width: rect.width + 2 * HIGHLIGHT_PADDING, height: rect.height + 2 * HIGHLIGHT_PADDING } : null;
      setTarget(framed);
      setLayout({
        width,
        ...placeTourCard(framed, { width, height }, { width: window.innerWidth, height: window.innerHeight, insetTop: insets.top, insetBottom: insets.bottom }, 14, TOUR_VIEWPORT_MARGIN),
      });
    };
    update();
    window.addEventListener("resize", update);
    window.addEventListener("scroll", update, true);
    const observer = new ResizeObserver(update);
    observer.observe(card);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("scroll", update, true);
      observer.disconnect();
    };
  }, [current]);

  // Alla chiusura il focus torna dove era prima del tour (se quell'elemento esiste ancora). Va dichiarato prima del focus sul callout.
  useLayoutEffect(() => {
    const previous = document.activeElement instanceof HTMLElement && document.activeElement !== document.body ? document.activeElement : null;
    return () => { if (previous?.isConnected) previous.focus({ preventScroll: true }); };
  }, []);

  useLayoutEffect(() => {
    cardRef.current?.focus({ preventScroll: true });
  }, [step]);

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === "ArrowRight") {
      event.preventDefault();
      if (isLast) onClose();
      else onStep(step + 1);
      return;
    }
    if (event.key === "ArrowLeft" && step > 0) {
      event.preventDefault();
      onStep(step - 1);
      return;
    }
    if (event.key !== "Tab") return;
    const card = cardRef.current;
    if (!card) return;
    const items = [...card.querySelectorAll<HTMLElement>(FOCUSABLE)].filter((item) => item.getClientRects().length > 0);
    const first = items[0];
    const last = items.at(-1);
    if (!first || !last) return;
    if (event.shiftKey && (document.activeElement === first || document.activeElement === card)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  const highlightStyle = target ? ({ transform: `translate(${target.left}px, ${target.top}px)`, width: target.width, height: target.height } satisfies CSSProperties) : undefined;
  const cardStyle = layout ? ({ left: layout.left, top: layout.top, width: layout.width, "--arrow": layout.arrow === null ? undefined : `${layout.arrow}px` } as CSSProperties) : undefined;
  return createPortal(
    <div className="tour-layer" role="dialog" aria-modal="true" aria-labelledby="tour-title" aria-describedby="tour-body" onKeyDown={handleKeyDown}>
      <div className="tour-scrim" data-dim={target ? undefined : "true"} />
      {highlightStyle ? <div className="tour-highlight" style={highlightStyle} aria-hidden="true" /> : null}
      <section ref={cardRef} className="tour-card glass glass--sheet" style={cardStyle} data-side={layout?.side} data-ready={layout ? "true" : undefined} tabIndex={-1}>
        {layout && layout.arrow !== null ? <span className="tour-card__arrow" aria-hidden="true" /> : null}
        <div className="tour-card__scroll">
          <p className="section-kicker">Tour · {step + 1} di {steps.length}</p>
          <h2 id="tour-title" className="mt-1 text-xl font-extrabold tracking-[-0.04em] text-ink">{current.title}</h2>
          <p id="tour-body" className="mt-2 text-sm font-semibold leading-6 text-muted">{current.body}</p>
          <div className="tour-card__dots" aria-hidden="true">
            {steps.map((item, index) => <span key={item.id} data-current={index === step || undefined} />)}
          </div>
          <div className="mt-4 flex items-center justify-between gap-3">
            <button className="guidance-skip" type="button" onClick={onClose}>Salta</button>
            <div className="flex gap-2">
              {step > 0 ? <button className="button-secondary inline-flex items-center gap-1.5" type="button" onClick={() => onStep(step - 1)}><ArrowLeft size={17} aria-hidden="true" /> Indietro</button> : null}
              <button className="button-primary inline-flex items-center gap-2" type="button" onClick={isLast ? onClose : () => onStep(step + 1)}>{isLast ? "Fine" : "Avanti"}{!isLast ? <ArrowRight size={17} aria-hidden="true" /> : null}</button>
            </div>
          </div>
        </div>
      </section>
    </div>,
    document.body,
  );
}

export function ProductGuidanceProvider({ children }: { readonly children: ReactNode }) {
  const { user } = useAuth();
  const userId = user?.id ?? null;
  const bootReady = useBootReady();
  const onDiscover = useLocation().pathname === "/";
  const [layer, setLayer] = useState<Layer>(null);
  const [onboardingStep, setOnboardingStep] = useState(0);
  const [tourStepList, setTourSteps] = useState<readonly TourStepDefinition[]>([]);
  const [tourStep, setTourStep] = useState(0);
  const [discoverReady, setDiscoverReady] = useState(false);
  /** "Scopri come funziona": l'onboarding è chiuso e il tour parte appena Discover è pronto. */
  const [tourPending, setTourPending] = useState(false);

  // Benvenuto alla prima visita (anonima o autenticata, è lo stesso): subito dopo lo splash di avvio, su Discover (su Login o Registrazione mai).
  // Non aspetta la mappa: mentre si legge, la mappa si carica dietro. Se nel frattempo si apre un altro dialogo o si cambia pagina, si rinuncia.
  useEffect(() => {
    if (!bootReady || !onDiscover || isOnboardingComplete(getStorage(), userId)) return undefined;
    const timeoutId = window.setTimeout(() => {
      if (isOnboardingComplete(getStorage(), userId)) return;
      if (document.querySelector("[role='dialog'][aria-modal='true']")) return;
      setOnboardingStep(0);
      setLayer((current) => (current === null ? "welcome" : current));
    }, WELCOME_DELAY_MS);
    return () => window.clearTimeout(timeoutId);
  }, [bootReady, onDiscover, userId]);

  const startTour = useCallback(() => {
    const available = resolveTourSteps();
    if (available.length === 0) {
      setLayer(null);
      return;
    }
    setTourSteps(available);
    setTourStep(0);
    setLayer("tour");
  }, []);

  const value = useMemo<ProductGuidanceValue>(() => ({
    openHelp: () => setLayer("help"),
    openOnboarding: () => { setOnboardingStep(0); setLayer("onboarding"); },
    openTour: startTour,
    notifyDiscoverReady: setDiscoverReady,
  }), [startTour]);

  /** Salta, Escape e "Esplora Milano" chiudono e basta; "Scopri come funziona" chiude e poi, a modale sparito, avvia il tour. */
  const finishOnboarding = useCallback((thenTour: boolean) => {
    markOnboardingComplete(getStorage(), userId);
    setLayer(null);
    setTourPending(thenTour);
  }, [userId]);

  // Il tour parte solo se nel frattempo non si è aperto altro (aiuto, un altro dialogo): niente overlay sovrapposti.
  useEffect(() => {
    if (!tourPending) return undefined;
    if (layer !== null || document.querySelector("[role='dialog'][aria-modal='true']")) {
      setTourPending(false);
      return undefined;
    }
    const delay = tourStartDelay(document.querySelector(".event-map") !== null, discoverReady);
    const timeoutId = window.setTimeout(() => {
      setTourPending(false);
      startTour();
    }, delay);
    return () => window.clearTimeout(timeoutId);
  }, [tourPending, layer, discoverReady, startTour]);

  return (
    <ProductGuidanceContext.Provider value={value}>
      {children}
      {layer === "welcome" || layer === "onboarding" ? <Onboarding step={onboardingStep} onStep={setOnboardingStep} onSkip={() => finishOnboarding(false)} onFinish={finishOnboarding} /> : null}
      {layer === "tour" ? <Tour steps={tourStepList} step={tourStep} onStep={setTourStep} onClose={() => setLayer(null)} /> : null}
      {layer === "help" ? (
        <Modal labelledBy="help-title" onClose={() => setLayer(null)} className="help-dialog">
          <header className="guidance-dialog__header"><div><p className="section-kicker">LINKR</p><h2 id="help-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Centro assistenza</h2></div><button className="icon-button" type="button" onClick={() => setLayer(null)} aria-label="Chiudi Centro assistenza"><X size={18} aria-hidden="true" /></button></header>
          <div className="guidance-dialog__body help-menu">
            <button type="button" onClick={() => { setOnboardingStep(0); setLayer("onboarding"); }}><BookOpen aria-hidden="true" /><span><strong>Come funziona LINKR</strong><small>Rivedi l’onboarding</small></span><ArrowRight aria-hidden="true" /></button>
            <button type="button" onClick={() => setLayer("about")}><Building2 aria-hidden="true" /><span><strong>Chi siamo</strong><small>Il progetto e la sua direzione</small></span><ArrowRight aria-hidden="true" /></button>
            <button type="button" onClick={() => setLayer("report")}><Flag aria-hidden="true" /><span><strong>Segnala un problema</strong><small>Canali di assistenza disponibili</small></span><ArrowRight aria-hidden="true" /></button>
            <button type="button" onClick={() => setLayer("privacy")}><LockKeyhole aria-hidden="true" /><span><strong>Privacy e sicurezza</strong><small>Come trattiamo queste informazioni</small></span><ArrowRight aria-hidden="true" /></button>
            <button className="help-tour-button" type="button" onClick={startTour}><Play aria-hidden="true" /><span><strong>Tour dell’interfaccia</strong><small>Rivedi i punti principali dell’interfaccia</small></span><ArrowRight aria-hidden="true" /></button>
          </div>
        </Modal>
      ) : null}
      {layer === "about" || layer === "report" || layer === "privacy" ? (
        <Modal labelledBy="help-detail-title" onClose={() => setLayer("help")} className="guidance-dialog--compact">
          <header className="guidance-dialog__header"><button className="icon-button" type="button" onClick={() => setLayer("help")} aria-label="Torna al Centro assistenza"><ArrowLeft size={18} aria-hidden="true" /></button><button className="icon-button" type="button" onClick={() => setLayer(null)} aria-label="Chiudi"><X size={18} aria-hidden="true" /></button></header>
          <div className="guidance-dialog__body">
            <h2 id="help-detail-title" className="text-2xl font-extrabold tracking-[-0.04em] text-ink">{layer === "about" ? "Chi siamo" : layer === "report" ? "Segnala un problema" : "Privacy e sicurezza"}</h2>
            <p className="mt-3 text-sm font-semibold leading-6 text-muted">{layer === "about" ? "LINKR nasce a Milano per rendere più semplice scoprire eventi, incontrare persone con interessi affini e organizzarsi insieme, con un’esperienza urbana accessibile e rispettosa." : layer === "report" ? "Al momento non è configurato un contatto pubblico per l’assistenza. Non vogliamo inventare un indirizzo: quando sarà disponibile, comparirà qui." : "LINKR non pubblica ancora pagine legali dedicate all’interno dell’app. Evita di condividere dati sensibili nelle descrizioni e nelle chat; le informazioni su privacy e sicurezza verranno collegate qui quando saranno effettivamente pubblicate."}</p>
          </div>
          <footer className="guidance-dialog__footer"><button className="button-primary inline-flex items-center" type="button" onClick={() => setLayer("help")}>Torna al Centro assistenza</button></footer>
        </Modal>
      ) : null}
    </ProductGuidanceContext.Provider>
  );
}

export function useProductGuidance(): ProductGuidanceValue {
  const context = useContext(ProductGuidanceContext);
  if (!context) throw new Error("useProductGuidance must be used within ProductGuidanceProvider");
  return context;
}

export function HelpButton({ hidden = false }: { readonly hidden?: boolean }) {
  const { openHelp } = useProductGuidance();
  return <button className="icon-button help-fab glass glass--strong" type="button" onClick={openHelp} hidden={hidden} aria-label="Apri il Centro assistenza" data-tour="help"><HelpCircle size={20} strokeWidth={2.4} aria-hidden="true" /></button>;
}
