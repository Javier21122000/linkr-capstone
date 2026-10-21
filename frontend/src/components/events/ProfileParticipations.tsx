import axios from "axios";
import { ArrowRight, CalendarDays, Eye, EyeOff, MapPin, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { formatEventDate, formatEventTimeRange, isEventEnded } from "../../lib/eventPresentation";
import { getMyParticipations, getParticipationsErrorMessage } from "../../lib/participations";
import type { UserParticipation } from "../../types/participation";
import { ViewOnMapLink } from "./ViewOnMapLink";

type ParticipationsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly participations: readonly UserParticipation[] }
  | { readonly status: "error"; readonly message: string };

export function ProfileParticipations() {
  const { authorizedRequest } = useAuth();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ParticipationsState>({ status: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: "loading" });

    void authorizedRequest((accessToken) => getMyParticipations(accessToken, controller.signal))
      .then((response) => setState({ status: "success", participations: response.participations }))
      .catch((error: unknown) => {
        if (!axios.isCancel(error)) setState({ status: "error", message: getParticipationsErrorMessage(error) });
      });

    return () => controller.abort();
  }, [authorizedRequest, requestKey]);

  return (
    <section className="content-reveal mt-10" aria-labelledby="profile-participations-title">
      <p className="section-kicker">Partecipazioni</p>
      <h2 id="profile-participations-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">La mia agenda</h2>
      <p className="mt-2 text-sm leading-6 text-muted">Gli eventi a cui parteciperai o che hai segnato con «Mi interessa».</p>

      {state.status === "loading" ? (
        <div className="mt-5 space-y-3" aria-busy="true" aria-label="Caricamento della tua agenda">
          {Array.from({ length: 2 }, (_, index) => (
            <div className="h-32 animate-pulse rounded-[1.5rem] bg-white/70 ring-1 ring-black/5 motion-reduce:animate-none" key={index} />
          ))}
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

      {state.status === "success" && state.participations.length === 0 ? (
        <div className="surface-card mt-5 p-5">
          <p className="font-extrabold text-ink">La tua agenda è ancora vuota.</p>
          <p className="mt-2 text-sm leading-6 text-muted">Quando scegli «Parteciperò» o «Mi interessa» su un evento, lo ritrovi qui.</p>
          <Link className="mt-4 inline-flex min-h-11 items-center gap-1 rounded-full font-extrabold text-ink" to="/">
            Vai a Discover <ArrowRight size={17} aria-hidden="true" />
          </Link>
        </div>
      ) : null}

      {state.status === "success" && state.participations.length > 0 ? (
        <div className="mt-5 space-y-3">
          {state.participations.map(({ event, status, visibleToOthers }) => {
            const location = [event.venueName, event.neighbourhood].filter(Boolean).join(" · ");
            return (
              <article className="surface-card p-5" key={event.id}>
                <Link className="group block rounded-xl" to={`/events/${event.id}`}>
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <h3 className="text-lg font-extrabold tracking-[-0.025em] text-ink group-hover:text-linkr-strong">{event.title}</h3>
                      <p className="mt-2 flex items-start gap-2 text-sm font-semibold text-muted">
                        <CalendarDays className="mt-0.5 shrink-0 text-linkr-strong" size={16} aria-hidden="true" />
                        <span>{formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}</span>
                      </p>
                      {location ? (
                        <p className="mt-1.5 flex items-start gap-2 text-sm font-semibold text-muted">
                          <MapPin className="mt-0.5 shrink-0 text-linkr-strong" size={16} aria-hidden="true" />
                          <span>{location}</span>
                        </p>
                      ) : null}
                    </div>
                    <ArrowRight className="mt-1 shrink-0 text-muted transition-transform motion-reduce:transition-none group-hover:translate-x-1" size={19} aria-hidden="true" />
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2 text-xs font-extrabold">
                    <span className="rounded-full bg-linkr-soft px-3 py-1.5 text-linkr-strong">
                      {status === "INTERESTED" ? "Mi interessa" : "Parteciperò"}
                    </span>
                    <span className="inline-flex items-center gap-1.5 rounded-full bg-black/5 px-3 py-1.5 text-muted">
                      {visibleToOthers ? <Eye size={14} aria-hidden="true" /> : <EyeOff size={14} aria-hidden="true" />}
                      {visibleToOthers ? "Visibilità scelta: attiva" : "Visibilità scelta: privata"}
                    </span>
                  </div>
                </Link>
                {event.status === "PUBLISHED" && !isEventEnded(event.startsAt, event.endsAt) ? (
                  <div className="mt-1">
                    <ViewOnMapLink eventId={event.id} variant="inline" />
                  </div>
                ) : null}
              </article>
            );
          })}
        </div>
      ) : null}
    </section>
  );
}
