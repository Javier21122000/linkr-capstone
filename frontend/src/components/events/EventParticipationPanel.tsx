import axios from "axios";
import { Bookmark, CheckCircle2, RotateCcw } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { isEventEnded } from "../../lib/eventPresentation";
import {
  deleteEventParticipation,
  getEventParticipation,
  getParticipationErrorMessage,
  isEventEndedConflict,
  isParticipationNotFound,
  updateEventParticipation,
} from "../../lib/participations";
import type { ParticipationResponse, ParticipationStatus } from "../../types/participation";

type ParticipationState =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly participation: ParticipationResponse | null }
  | { readonly status: "error"; readonly message: string };

interface EventParticipationPanelProps {
  readonly eventId: string;
  readonly startsAt: string;
  readonly endsAt?: string | null;
  onParticipationChange(participation: ParticipationResponse | null | undefined): void;
  onParticipationSaved(participation: ParticipationResponse): void;
}

export function EventParticipationPanel({ eventId, startsAt, endsAt, onParticipationChange, onParticipationSaved }: EventParticipationPanelProps) {
  const { status, authorizedRequest } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ParticipationState>(
    status === "anonymous" ? { status: "ready", participation: null } : { status: "loading" },
  );
  const [saving, setSaving] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [endedFromServer, setEndedFromServer] = useState(false);
  const mutationRef = useRef(false);
  const ended = endedFromServer || isEventEnded(startsAt, endsAt);

  useEffect(() => {
    setActionError(null);
    setEndedFromServer(false);
    onParticipationChange(undefined);

    if (status === "anonymous") {
      setState({ status: "ready", participation: null });
      onParticipationChange(null);
      return undefined;
    }

    const controller = new AbortController();
    setState({ status: "loading" });

    void authorizedRequest((accessToken) => getEventParticipation(accessToken, eventId, controller.signal))
      .then((participation) => {
        setState({ status: "ready", participation });
        onParticipationChange(participation);
      })
      .catch((error: unknown) => {
        if (axios.isCancel(error)) return;
        if (isParticipationNotFound(error)) {
          setState({ status: "ready", participation: null });
          onParticipationChange(null);
        } else {
          setState({ status: "error", message: getParticipationErrorMessage(error) });
          onParticipationChange(undefined);
        }
      });

    return () => controller.abort();
  }, [authorizedRequest, eventId, onParticipationChange, requestKey, status]);

  function goToLogin() {
    navigate("/login", { state: { from: location } });
  }

  async function saveParticipation(nextStatus: ParticipationStatus, visibleToOthers: boolean) {
    if (status === "anonymous") {
      goToLogin();
      return;
    }
    if (ended || mutationRef.current) return;

    mutationRef.current = true;
    setSaving(true);
    setActionError(null);

    try {
      const participation = await authorizedRequest((accessToken) => updateEventParticipation(
        accessToken,
        eventId,
        { status: nextStatus, visibleToOthers },
      ));
      setState({ status: "ready", participation });
      onParticipationSaved(participation);
    } catch (error) {
      if (isEventEndedConflict(error)) setEndedFromServer(true);
      setActionError(getParticipationErrorMessage(error));
    } finally {
      mutationRef.current = false;
      setSaving(false);
    }
  }

  async function removeParticipation() {
    if (status !== "authenticated" || mutationRef.current) return;

    mutationRef.current = true;
    setRemoving(true);
    setActionError(null);

    try {
      await authorizedRequest((accessToken) => deleteEventParticipation(accessToken, eventId));
      setState({ status: "ready", participation: null });
      onParticipationChange(null);
    } catch (error) {
      setActionError(getParticipationErrorMessage(error));
    } finally {
      mutationRef.current = false;
      setRemoving(false);
    }
  }

  const participation = state.status === "ready" ? state.participation : null;
  const busy = saving || removing;

  return (
    <section
      className="surface-card content-reveal mt-8 p-4 sm:p-6"
      aria-labelledby="participation-title"
      aria-busy={busy || state.status === "loading"}
    >
      <p className="section-kicker">Il tuo evento</p>
      <h2 id="participation-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">Parteciperai?</h2>

      {state.status === "loading" ? (
        <div className="mt-5 space-y-3" aria-label="Caricamento partecipazione">
          <div className="h-12 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
          <div className="h-12 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="form-message form-message--error mt-5" role="alert">
          <p>{state.message}</p>
          <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
            <RotateCcw size={17} aria-hidden="true" /> Riprova
          </button>
        </div>
      ) : null}

      {state.status === "ready" ? (
        <>
          {ended ? (
            <p className="form-message mt-5" role="status">
              {participation
                ? "L’evento è terminato. Puoi consultare o rimuovere la tua partecipazione."
                : "L’evento è terminato e non accetta più partecipazioni."}
            </p>
          ) : null}

          {status === "anonymous" && !ended ? (
            <p className="mt-4 text-sm leading-6 text-muted">Accedi per indicare se questo evento ti interessa o se parteciperai.</p>
          ) : null}

          <div className="mt-5 grid gap-3 sm:grid-cols-2">
            {(["INTERESTED", "GOING"] as const).map((participationStatus) => {
              const selected = participation?.status === participationStatus;
              return (
                <button
                  key={participationStatus}
                  className="participation-choice"
                  type="button"
                  aria-pressed={selected}
                  data-selected={selected}
                  data-kind={participationStatus}
                  disabled={busy || (ended && !selected)}
                  onClick={() => {
                    if (selected) void removeParticipation();
                    else void saveParticipation(participationStatus, participation?.visibleToOthers ?? false);
                  }}
                >
                  {participationStatus === "INTERESTED"
                    ? <Bookmark className="participation-choice__icon" size={18} fill={selected ? "currentColor" : "none"} aria-hidden="true" />
                    : <CheckCircle2 className="participation-choice__icon" size={18} aria-hidden="true" />}
                  <span>{participationStatus === "INTERESTED" ? "Mi interessa" : "Parteciperò"}</span>
                </button>
              );
            })}
          </div>

          {participation ? <p className="mt-3 text-center text-sm font-semibold leading-6 text-muted">Tocca di nuovo per annullare</p> : null}

          {participation ? (
            <div className="mt-6 border-t border-line pt-5">
              <label className="privacy-control flex min-h-14 items-start gap-3 rounded-xl bg-canvas p-3 text-ink" data-disabled={busy || ended}>
                <input
                  className="mt-0.5 size-6 shrink-0 accent-linkr-strong"
                  type="checkbox"
                  checked={participation.visibleToOthers}
                  disabled={busy || ended}
                  onChange={(event) => void saveParticipation(participation.status, event.currentTarget.checked)}
                />
                <span>
                  <span className="block text-sm font-extrabold leading-5">Mostra il mio profilo agli altri partecipanti</span>
                  <span className="mt-1 block text-sm font-semibold leading-5 text-muted">
                    Se attivi questa opzione, le persone interessate allo stesso evento potranno trovarti nella sezione 'Chi viene con te?'.
                  </span>
                </span>
              </label>
            </div>
          ) : null}

          <div className="min-h-6 pt-3 text-sm font-bold text-muted" aria-live="polite">
            {saving ? "Salvataggio della partecipazione…" : removing ? "Rimozione della partecipazione…" : null}
          </div>
        </>
      ) : null}

      {actionError ? <div className="form-message form-message--error mt-3" role="alert">{actionError}</div> : null}
    </section>
  );
}
