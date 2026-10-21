import { CircleCheck, Info, X } from "lucide-react";
import type { ReactNode } from "react";
import { useEffect } from "react";

interface MapNoticeProps {
  readonly tone?: "success" | "info";
  readonly title: string;
  readonly detail?: string;
  /** Azioni (link o pulsanti) mostrate a destra del testo. */
  readonly children?: ReactNode;
  readonly onDismiss: () => void;
  /** Chiusura automatica; senza, l'avviso resta finché non lo si chiude. */
  readonly durationMs?: number;
}

/** Avviso in vetro sopra la mappa (pubblicazione, filtro tolto per mostrare un evento…). La regione `aria-live` sta nel chiamante. */
export function MapNotice({ tone = "info", title, detail, children, onDismiss, durationMs }: MapNoticeProps) {
  useEffect(() => {
    if (!durationMs) return undefined;
    const timeoutId = window.setTimeout(onDismiss, durationMs);
    return () => window.clearTimeout(timeoutId);
  }, [durationMs, onDismiss]);

  return (
    <div className="qc-notice glass glass--strong content-reveal">
      {tone === "success"
        ? <CircleCheck className="shrink-0 text-success" size={20} aria-hidden="true" />
        : <Info className="shrink-0 text-linkr-strong" size={20} aria-hidden="true" />}
      <p className="min-w-0 flex-1 text-sm font-extrabold leading-5 text-ink">
        {title}
        {detail ? <span className="block text-xs font-semibold text-muted">{detail}</span> : null}
      </p>
      {children}
      <button className="qc-notice__close" type="button" aria-label="Chiudi avviso" onClick={onDismiss}>
        <X size={16} aria-hidden="true" />
      </button>
    </div>
  );
}
