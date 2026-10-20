import { LoaderCircle } from "lucide-react";
import { useEffect, useRef } from "react";
import type { KeyboardEvent } from "react";

interface CancelEventDialogProps {
  readonly open: boolean;
  readonly eventTitle: string;
  readonly submitting: boolean;
  readonly error: string | null;
  onClose(): void;
  onConfirm(): void;
}

export function CancelEventDialog({ open, eventTitle, submitting, error, onClose, onConfirm }: CancelEventDialogProps) {
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return undefined;
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButtonRef.current?.focus();
    return () => previouslyFocused?.focus();
  }, [open]);

  if (!open) return null;

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape" && !submitting) {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;
    const first = closeButtonRef.current;
    const last = confirmButtonRef.current;
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
      <div className="dialog-panel" role="dialog" aria-modal="true" aria-labelledby="cancel-event-title" aria-describedby="cancel-event-description" onKeyDown={handleKeyDown}>
        <h2 id="cancel-event-title" className="text-xl font-extrabold tracking-[-0.035em] text-ink">Annullare “{eventTitle}”?</h2>
        <p id="cancel-event-description" className="mt-3 text-sm leading-6 text-muted">L’evento non verrà eliminato fisicamente: resterà nel tuo storico con stato Annullato e non potrà più essere modificato.</p>
        {error ? <div className="form-message form-message--error mt-4" role="alert">{error}</div> : null}
        <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button ref={closeButtonRef} className="button-secondary flex items-center" type="button" disabled={submitting} onClick={onClose}>Mantieni evento</button>
          <button ref={confirmButtonRef} className="button-primary flex items-center gap-2" type="button" disabled={submitting} aria-busy={submitting} onClick={onConfirm}>
            {submitting ? <LoaderCircle className="animate-spin motion-reduce:animate-none" size={17} aria-hidden="true" /> : null}
            {submitting ? "Annullamento…" : "Annulla evento"}
          </button>
        </div>
      </div>
    </div>
  );
}
