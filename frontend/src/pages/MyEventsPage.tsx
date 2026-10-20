import { ArrowLeft, CalendarDays, MapPin, Plus, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { EventImage } from "../components/events/EventImage";
import { useAuth } from "../context/AuthContext";
import { getMyEvents } from "../lib/eventCreation";
import { categoryLabels, formatEventDate, formatEventTimeRange } from "../lib/eventPresentation";
import { getEventsErrorMessage, isRequestCancelled } from "../lib/events";
import type { EventSummary } from "../types/event";

type EventsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly events: readonly EventSummary[] }
  | { readonly status: "error"; readonly message: string };

function EventCard({ event }: { readonly event: EventSummary }) {
  const cancelled = event.status === "CANCELLED";
  return (
    <Link
      className="group grid overflow-hidden rounded-[1.5rem] border border-line bg-surface transition-transform active:scale-[0.99] motion-reduce:transition-none sm:grid-cols-[11rem_minmax(0,1fr)]"
      to={`/events/${encodeURIComponent(event.id)}`}
    >
      <div className="relative min-h-44 bg-line sm:min-h-full">
        <EventImage imageUrl={event.imageUrl} imageAlt={event.imageAlt} category={event.category} className={`absolute inset-0 size-full object-cover ${cancelled ? "grayscale opacity-60" : ""}`} />
      </div>
      <div className="p-5 sm:p-6">
        <div className="flex flex-wrap items-center gap-2">
          <span className="section-kicker">{categoryLabels[event.category]}</span>
          <span className={`rounded-full px-2.5 py-1 text-[0.65rem] font-extrabold uppercase tracking-[0.12em] ${cancelled ? "bg-line text-muted" : "bg-linkr-soft text-linkr-strong"}`}>
            {cancelled ? "Annullato" : "Pubblicato"}
          </span>
          <span className="text-[0.65rem] font-extrabold uppercase tracking-[0.12em] text-muted">{event.type === "COMMUNITY" ? "Community" : "Official"}</span>
        </div>
        <h3 className="mt-3 text-2xl font-extrabold tracking-[-0.04em] text-ink group-hover:text-linkr-strong">{event.title}</h3>
        <p className="mt-4 inline-flex items-center gap-2 text-sm font-bold text-muted">
          <CalendarDays size={17} aria-hidden="true" /> {formatEventDate(event.startsAt)} · {formatEventTimeRange(event.startsAt, event.endsAt)}
        </p>
        <p className="mt-2 inline-flex items-start gap-2 text-sm font-semibold text-muted">
          <MapPin className="mt-0.5 shrink-0" size={17} aria-hidden="true" /> {event.venueName} · {event.neighbourhood}
        </p>
      </div>
    </Link>
  );
}

export function MyEventsPage() {
  const { authorizedRequest } = useAuth();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<EventsState>({ status: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: "loading" });
    void authorizedRequest((accessToken) => getMyEvents(accessToken, controller.signal))
      .then((events) => setState({ status: "success", events }))
      .catch((error: unknown) => {
        if (!isRequestCancelled(error)) setState({ status: "error", message: getEventsErrorMessage(error) });
      });
    return () => controller.abort();
  }, [authorizedRequest, requestKey]);

  const published = state.status === "success" ? state.events.filter((event) => event.status === "PUBLISHED") : [];
  const cancelled = state.status === "success" ? state.events.filter((event) => event.status === "CANCELLED") : [];

  return (
    <div className="page-shell page-with-mobile-nav pb-16 pt-8 sm:pt-12 lg:pt-16">
      <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to="/profile">
        <ArrowLeft size={19} aria-hidden="true" /> Torna al profilo
      </Link>
      <header className="mt-5 flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="section-kicker">Organizza</p>
          <h1 className="mt-3 text-[clamp(2.6rem,8vw,5rem)] font-extrabold leading-[0.94] tracking-[-0.06em] text-ink">I miei eventi</h1>
          <p className="mt-4 max-w-2xl text-base leading-7 text-muted">Ritrova gli eventi pubblicati e quelli annullati dal tuo account.</p>
        </div>
        <Link className="button-primary inline-flex shrink-0 items-center gap-2" to="/events/new"><Plus size={18} aria-hidden="true" /> Crea evento</Link>
      </header>

      {state.status === "loading" ? (
        <div className="mt-10 grid gap-4" aria-busy="true" aria-label="Caricamento eventi">
          {[0, 1].map((item) => <div key={item} className="h-52 animate-pulse rounded-[1.5rem] bg-line/70 motion-reduce:animate-none" />)}
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="mt-10 rounded-[1.5rem] border border-line bg-surface px-6 py-12 text-center" role="alert">
          <h2 className="text-2xl font-extrabold tracking-[-0.04em] text-ink">Non riusciamo a caricare i tuoi eventi</h2>
          <p className="mt-3 text-sm leading-6 text-muted">{state.message}</p>
          <button className="button-primary mt-6 inline-flex items-center gap-2" type="button" onClick={() => setRequestKey((current) => current + 1)}><RotateCcw size={17} aria-hidden="true" /> Riprova</button>
        </div>
      ) : null}

      {state.status === "success" && state.events.length === 0 ? (
        <div className="mt-10 rounded-[1.5rem] border border-line bg-surface px-6 py-14 text-center">
          <h2 className="text-2xl font-extrabold tracking-[-0.04em] text-ink">Il prossimo evento parte da te</h2>
          <p className="mx-auto mt-3 max-w-md text-sm leading-6 text-muted">Non hai ancora creato eventi. Pubblica un’uscita community e scegli il punto sulla mappa.</p>
          <Link className="button-primary mt-6 inline-flex items-center gap-2" to="/events/new"><Plus size={17} aria-hidden="true" /> Crea il primo evento</Link>
        </div>
      ) : null}

      {state.status === "success" && published.length > 0 ? (
        <section className="mt-10" aria-labelledby="published-events-title">
          <h2 id="published-events-title" className="text-xl font-extrabold tracking-[-0.035em] text-ink">Eventi pubblicati <span className="text-muted">({published.length})</span></h2>
          <div className="mt-4 grid gap-4">{published.map((event) => <EventCard key={event.id} event={event} />)}</div>
        </section>
      ) : null}

      {state.status === "success" && cancelled.length > 0 ? (
        <section className="mt-12" aria-labelledby="cancelled-events-title">
          <h2 id="cancelled-events-title" className="text-xl font-extrabold tracking-[-0.035em] text-ink">Eventi annullati <span className="text-muted">({cancelled.length})</span></h2>
          <p className="mt-2 text-sm leading-6 text-muted">Restano visibili qui come storico e non possono essere modificati.</p>
          <div className="mt-4 grid gap-4">{cancelled.map((event) => <EventCard key={event.id} event={event} />)}</div>
        </section>
      ) : null}
    </div>
  );
}
