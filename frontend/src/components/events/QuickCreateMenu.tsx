import { ChevronRight, ListPlus, LogIn, MapPinned, X, Zap } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "react-router";

import { IconButton } from "../ui/IconButton";

interface ActionProps {
  readonly icon: ReactNode;
  readonly title: string;
  readonly description: string;
  readonly accent?: boolean;
  readonly onSelect: () => void;
}

function QuickCreateAction({ icon, title, description, accent = false, onSelect }: ActionProps) {
  return (
    <button className="qc-action" type="button" data-accent={accent || undefined} onClick={onSelect}>
      <span className="qc-action__icon" aria-hidden="true">{icon}</span>
      <span className="min-w-0 flex-1 text-left">
        <span className="block text-base font-extrabold tracking-[-0.02em] text-ink">{title}</span>
        <span className="mt-0.5 block text-[0.8125rem] font-semibold leading-5 text-muted">{description}</span>
      </span>
      <ChevronRight className="shrink-0 text-muted" size={18} aria-hidden="true" />
    </button>
  );
}

interface QuickCreateMenuProps {
  readonly authenticated: boolean;
  readonly mapAvailable: boolean;
  readonly onClose: () => void;
  readonly onStartQuick: () => void;
  readonly onStartPick: () => void;
  readonly onStartFull: () => void;
  /** Da anonimo: i link verso accesso e registrazione lasciano Discover, quindi il dialogo non deve bloccarli. */
  readonly onLeave: () => void;
}

/** Punto d'ingresso: tre azioni reali (uscita rapida, punto sulla mappa, form completo). Da anonimo propone di accedere. */
export function QuickCreateMenu({ authenticated, mapAvailable, onClose, onStartQuick, onStartPick, onStartFull, onLeave }: QuickCreateMenuProps) {
  return (
    <div className="qc-view">
      <header className="qc-dialog__header">
        <div className="min-w-0">
          <p className="section-kicker">Crea su LINKR</p>
          <h2 id="qc-menu-title" className="mt-1 text-2xl font-extrabold tracking-[-0.045em] text-ink">
            {authenticated ? "Cosa vuoi organizzare?" : "Accedi per organizzare"}
          </h2>
        </div>
        <IconButton className="shrink-0" label="Chiudi" onClick={onClose}>
          <X size={19} aria-hidden="true" />
        </IconButton>
      </header>

      {authenticated ? (
        <div className="qc-dialog__body qc-actions">
          <QuickCreateAction
            accent
            icon={<Zap size={20} strokeWidth={2.3} />}
            title="Crea un’uscita"
            description="Titolo, luogo e orario in tre passaggi."
            onSelect={onStartQuick}
          />
          {mapAvailable ? (
            <QuickCreateAction
              icon={<MapPinned size={20} strokeWidth={2.2} />}
              title="Scegli un punto sulla mappa"
              description="Tocca la mappa, poi racconta cosa organizzi."
              onSelect={onStartPick}
            />
          ) : null}
          <QuickCreateAction
            icon={<ListPlus size={20} strokeWidth={2.2} />}
            title="Crea evento completo"
            description="Tutti i campi: fine, crediti della foto, URL immagine."
            onSelect={onStartFull}
          />
        </div>
      ) : (
        <div className="qc-dialog__body">
          <p className="text-sm leading-6 text-muted">
            Per proporre un’uscita serve un account LINKR: così le persone sanno chi organizza e possono scriverti.
          </p>
          <div className="mt-5 flex flex-col gap-3 sm:flex-row">
            <Link className="button-primary inline-flex items-center gap-2" to="/login" state={{ from: { pathname: "/" } }} onClick={onLeave}>
              <LogIn size={17} aria-hidden="true" /> Accedi
            </Link>
            <Link className="button-secondary inline-flex items-center" to="/register" onClick={onLeave}>Registrati</Link>
          </div>
        </div>
      )}
    </div>
  );
}
