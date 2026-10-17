import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";
import type { Blocker } from "react-router";

export function UnsavedChangesDialog({ blocker }: { readonly blocker: Blocker }) {
  const leaveButtonRef = useRef<HTMLButtonElement>(null);
  const stayButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (blocker.state === "blocked") stayButtonRef.current?.focus();
  }, [blocker.state]);

  if (blocker.state !== "blocked") return null;

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      blocker.reset?.();
      return;
    }
    if (event.key !== "Tab") return;

    const first = leaveButtonRef.current;
    const last = stayButtonRef.current;
    if (!first || !last) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog-panel" role="dialog" aria-modal="true" aria-labelledby="unsaved-title" aria-describedby="unsaved-description" onKeyDown={handleKeyDown}>
        <h2 id="unsaved-title" className="text-xl font-extrabold tracking-[-0.035em] text-ink">Modifiche non salvate</h2>
        <p id="unsaved-description" className="mt-3 text-sm leading-6 text-muted">Se esci ora, la selezione corrente andrà persa.</p>
        <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button ref={leaveButtonRef} className="button-secondary flex items-center" type="button" onClick={() => blocker.proceed()}>
            Esci senza salvare
          </button>
          <button ref={stayButtonRef} className="button-primary flex items-center" type="button" onClick={() => blocker.reset()}>
            Continua a modificare
          </button>
        </div>
      </div>
    </div>
  );
}
