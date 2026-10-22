import {
  AlertTriangle,
  ArrowRight,
  CalendarRange,
  CheckCircle2,
  DatabaseZap,
  ExternalLink,
  Gauge,
  LoaderCircle,
  RefreshCw,
  Search,
  ShieldCheck,
  Tag,
  Ticket,
  UsersRound,
  X,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useSearchParams } from "react-router";

import { useAuth } from "../context/AuthContext";
import { useSlidingIndicator } from "../hooks/useSlidingIndicator";
import {
  getAdminEvents,
  getAdminRequestErrorMessage,
  runTicketmasterDryRun,
  runTicketmasterImport,
} from "../lib/admin";
import { countAdminEvents, filterAdminEvents } from "../lib/adminPresentation";
import type { AdminEventSourceFilter } from "../lib/adminPresentation";
import { categoryLabels, formatEventDate, formatEventTime } from "../lib/eventPresentation";
import { createBackState } from "../lib/navigation";
import type { TicketmasterDryRunReport, TicketmasterImportReport } from "../types/admin";
import type { EventCategory, EventSummary } from "../types/event";

type AdminSection = "overview" | "ticketmaster" | "events";
type Operation = "dry-run" | "import" | null;

const sections: readonly { id: AdminSection; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "ticketmaster", label: "Ticketmaster" },
  { id: "events", label: "Eventi" },
];
const daysOptions = [7, 14, 30] as const;
const candidatesOptions = [10, 20, 50] as const;
const categories = Object.keys(categoryLabels) as EventCategory[];

function isAdminSection(value: string | null): value is AdminSection {
  return sections.some((section) => section.id === value);
}

function formatReportDate(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "Data non disponibile";
  return new Intl.DateTimeFormat("it-IT", { dateStyle: "medium", timeStyle: "short" }).format(date);
}

function reportTone(status: string): "success" | "warning" | "error" {
  if (status === "OK") return "success";
  if (status === "PARTIAL" || status === "DISABLED" || status === "NOT_CONFIGURED" || status === "IMPORT_DISABLED") return "warning";
  return "error";
}

function reportStatusText(status: string): string {
  const labels: Record<string, string> = {
    OK: "Operazione completata",
    PARTIAL: "Operazione completata solo in parte",
    FAILED: "Operazione non completata",
    DISABLED: "Integrazione Ticketmaster disattivata",
    NOT_CONFIGURED: "Integrazione Ticketmaster non configurata",
    IMPORT_DISABLED: "Importazione reale disattivata",
  };
  return labels[status] ?? status;
}

function StatusNotice({ status, generatedAt }: { readonly status: string; readonly generatedAt: string }) {
  return (
    <div className="admin-status" data-tone={reportTone(status)} role={status === "FAILED" ? "alert" : "status"}>
      {status === "OK" ? <CheckCircle2 size={20} aria-hidden="true" /> : <AlertTriangle size={20} aria-hidden="true" />}
      <div>
        <p className="font-extrabold">{reportStatusText(status)}</p>
        <p className="mt-0.5 text-sm font-semibold opacity-80">Report generato il {formatReportDate(generatedAt)}</p>
      </div>
    </div>
  );
}

function MetricCard({ label, value, hint, icon }: { readonly label: string; readonly value: number; readonly hint: string; readonly icon: React.ReactNode }) {
  return (
    <article className="admin-metric surface-card">
      <span className="admin-metric__icon">{icon}</span>
      <p className="text-sm font-extrabold text-muted">{label}</p>
      <p className="mt-2 text-4xl font-extrabold tracking-[-0.06em] text-ink">{value.toLocaleString("it-IT")}</p>
      <p className="mt-2 text-xs font-semibold leading-5 text-muted">{hint}</p>
    </article>
  );
}

function DryRunReport({ report }: { readonly report: TicketmasterDryRunReport }) {
  const distribution = Object.entries(report.categoryDistribution)
    .filter((entry): entry is [EventCategory, number] => entry[0] in categoryLabels);

  return (
    <section className="mt-6 space-y-5" aria-labelledby="dry-run-report-title">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="section-kicker">Anteprima senza scritture</p>
          <h3 id="dry-run-report-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em]">Report dry-run</h3>
        </div>
        <span className="admin-safe-badge"><ShieldCheck size={15} aria-hidden="true" /> Nessun dato modificato</span>
      </div>
      <StatusNotice status={report.status} generatedAt={report.generatedAt} />

      <div className="admin-report-grid">
        <div><span>Ricevuti</span><strong>{report.counts.received}</strong></div>
        <div><span>Validi</span><strong>{report.counts.validCandidates}</strong></div>
        <div><span>Esclusi</span><strong>{report.excluded.total}</strong></div>
        <div><span>Oltre il limite</span><strong>{report.counts.skippedAfterLimit}</strong></div>
      </div>

      {distribution.length ? (
        <div className="admin-report-block">
          <h4>Distribuzione per categoria</h4>
          <div className="admin-category-distribution">
            {distribution.map(([category, count]) => (
              <div key={category}><span>{categoryLabels[category]}</span><strong>{count}</strong></div>
            ))}
          </div>
        </div>
      ) : null}

      {report.validSamples.length ? (
        <div className="admin-report-block">
          <h4>Esempi di candidati validi</h4>
          <div className="admin-sample-list">
            {report.validSamples.map((sample) => (
              <article key={sample.externalId} className="admin-sample">
                <div>
                  <p className="font-extrabold text-ink">{sample.title}</p>
                  <p className="mt-1 text-sm font-semibold text-muted">
                    {formatEventDate(sample.startsAt)} · {formatEventTime(sample.startsAt)} · {sample.venue || "Sede non indicata"}
                  </p>
                </div>
                <span className="admin-tag">{categoryLabels[sample.category as EventCategory] ?? sample.category}</span>
              </article>
            ))}
          </div>
        </div>
      ) : null}

      {report.errors.length ? (
        <div className="form-message form-message--error" role="alert">
          <p className="font-extrabold">La fonte ha segnalato {report.errors.length} errori.</p>
          <p className="mt-1 text-sm">{report.api.rateLimited ? "Ticketmaster ha applicato un limite di richieste." : report.errors[0]?.message}</p>
        </div>
      ) : null}
    </section>
  );
}

function ImportReport({ report }: { readonly report: TicketmasterImportReport }) {
  const skipped = report.result.keptLocalEdits + report.result.skippedDuplicates + report.result.skippedIncomplete;
  return (
    <section className="mt-6 space-y-5" aria-labelledby="import-report-title">
      <div>
        <p className="section-kicker">Ultima esecuzione</p>
        <h3 id="import-report-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em]">Report importazione</h3>
      </div>
      <StatusNotice status={report.status} generatedAt={report.generatedAt} />
      <div className="admin-report-grid admin-report-grid--five">
        <div><span>Creati</span><strong>{report.result.created}</strong></div>
        <div><span>Aggiornati</span><strong>{report.result.updated}</strong></div>
        <div><span>Invariati</span><strong>{report.result.unchanged}</strong></div>
        <div><span>Saltati</span><strong>{skipped}</strong></div>
        <div><span>Falliti</span><strong>{report.result.failed}</strong></div>
      </div>
      {report.result.keptLocalEdits || report.result.skippedDuplicates || report.result.skippedIncomplete ? (
        <p className="text-sm font-semibold leading-6 text-muted">
          Dettaglio saltati: {report.result.keptLocalEdits} con modifiche locali, {report.result.skippedDuplicates} duplicati, {report.result.skippedIncomplete} incompleti.
        </p>
      ) : null}
    </section>
  );
}

function ImportConfirmation({ days, maxCandidates, busy, onCancel, onConfirm }: {
  readonly days: number;
  readonly maxCandidates: number;
  readonly busy: boolean;
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}) {
  return (
    <div className="admin-dialog-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget && !busy) onCancel();
    }}>
      <section className="admin-dialog surface-card" role="dialog" aria-modal="true" aria-labelledby="import-confirm-title" aria-describedby="import-confirm-description">
        <button className="icon-button absolute right-4 top-4" type="button" aria-label="Chiudi conferma" disabled={busy} onClick={onCancel}>
          <X size={18} aria-hidden="true" />
        </button>
        <span className="grid size-12 place-items-center rounded-full bg-linkr-soft text-linkr-strong"><DatabaseZap size={22} aria-hidden="true" /></span>
        <h2 id="import-confirm-title" className="mt-5 text-2xl font-extrabold tracking-[-0.04em]">Confermi l’importazione reale?</h2>
        <p id="import-confirm-description" className="mt-3 font-semibold leading-7 text-muted">
          Questa operazione può creare o aggiornare dati reali. Verranno analizzati al massimo {maxCandidates} candidati nei prossimi {days} giorni. Non parte mai automaticamente.
        </p>
        <div className="mt-6 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
          <button className="button-secondary" type="button" disabled={busy} onClick={onCancel}>Annulla</button>
          <button className="button-primary inline-flex items-center justify-center gap-2" type="button" disabled={busy} onClick={onConfirm}>
            {busy ? <LoaderCircle className="animate-spin" size={18} aria-hidden="true" /> : <DatabaseZap size={18} aria-hidden="true" />}
            {busy ? "Importazione in corso…" : "Conferma e importa"}
          </button>
        </div>
      </section>
    </div>
  );
}

export function AdminPage() {
  const { authorizedRequest } = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedSection = searchParams.get("section");
  const section: AdminSection = isAdminSection(requestedSection) ? requestedSection : "overview";
  const navRef = useRef<HTMLElement>(null);
  useSlidingIndicator({ trackRef: navRef, activeSelector: '[aria-current="page"]', activeKey: section });
  const [events, setEvents] = useState<EventSummary[]>([]);
  const [eventsLoading, setEventsLoading] = useState(true);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [days, setDays] = useState<number>(7);
  const [maxCandidates, setMaxCandidates] = useState<number>(10);
  const [operation, setOperation] = useState<Operation>(null);
  const [operationError, setOperationError] = useState<string | null>(null);
  const [dryRunReport, setDryRunReport] = useState<TicketmasterDryRunReport | null>(null);
  const [importReport, setImportReport] = useState<TicketmasterImportReport | null>(null);
  const [confirmImport, setConfirmImport] = useState(false);
  const [search, setSearch] = useState("");
  const [sourceFilter, setSourceFilter] = useState<AdminEventSourceFilter>("ALL");
  const [categoryFilter, setCategoryFilter] = useState<EventCategory | "ALL">("ALL");

  const loadEvents = useCallback(async (signal?: AbortSignal) => {
    setEventsLoading(true);
    setEventsError(null);
    try {
      const response = await authorizedRequest((accessToken) => getAdminEvents(accessToken, signal));
      setEvents(response);
    } catch (error) {
      if (!signal?.aborted) setEventsError(getAdminRequestErrorMessage(error));
    } finally {
      if (!signal?.aborted) setEventsLoading(false);
    }
  }, [authorizedRequest]);

  useEffect(() => {
    const controller = new AbortController();
    void loadEvents(controller.signal);
    return () => controller.abort();
  }, [loadEvents]);

  const counts = useMemo(() => countAdminEvents(events), [events]);
  const filteredEvents = useMemo(
    () => filterAdminEvents(events, search, sourceFilter, categoryFilter),
    [categoryFilter, events, search, sourceFilter],
  );

  function changeSection(nextSection: AdminSection) {
    setSearchParams(nextSection === "overview" ? {} : { section: nextSection });
  }

  async function handleDryRun() {
    if (operation) return;
    setOperation("dry-run");
    setOperationError(null);
    try {
      const report = await authorizedRequest((accessToken) => runTicketmasterDryRun(accessToken, days, maxCandidates));
      setDryRunReport(report);
    } catch (error) {
      setOperationError(getAdminRequestErrorMessage(error));
    } finally {
      setOperation(null);
    }
  }

  async function handleImport() {
    if (operation) return;
    setOperation("import");
    setOperationError(null);
    try {
      const report = await authorizedRequest((accessToken) => runTicketmasterImport(accessToken, days, maxCandidates));
      setImportReport(report);
      setConfirmImport(false);
      if (report.status === "OK" || report.status === "PARTIAL") await loadEvents();
    } catch (error) {
      setOperationError(getAdminRequestErrorMessage(error));
      setConfirmImport(false);
    } finally {
      setOperation(null);
    }
  }

  return (
    <div className="admin-page page-shell page-with-mobile-nav">
      <header className="admin-hero">
        <div>
          <p className="section-kicker">Area riservata</p>
          <h1 className="mt-2 text-4xl font-extrabold tracking-[-0.06em] sm:text-5xl">Amministrazione</h1>
          <p className="mt-3 max-w-2xl font-semibold leading-7 text-muted">Controlla gli eventi pubblicati e avvia manualmente le operazioni Ticketmaster.</p>
        </div>
        <span className="admin-role-badge"><ShieldCheck size={17} aria-hidden="true" /> ADMIN</span>
      </header>

      <nav ref={navRef} className="admin-nav glass" aria-label="Sezioni amministrazione">
        <span className="admin-nav__thumb" aria-hidden="true" />
        {sections.map((item) => (
          <button key={item.id} type="button" aria-current={section === item.id ? "page" : undefined} onClick={() => changeSection(item.id)}>
            {item.label}
          </button>
        ))}
      </nav>

      {section === "overview" ? (
        <section className="admin-section" aria-labelledby="overview-title">
          <div className="admin-section-heading">
            <div><p className="section-kicker">Stato contenuti</p><h2 id="overview-title">Overview</h2></div>
            <button className="button-secondary inline-flex items-center gap-2" type="button" disabled={eventsLoading} onClick={() => void loadEvents()}>
              <RefreshCw className={eventsLoading ? "animate-spin" : ""} size={17} aria-hidden="true" /> Aggiorna
            </button>
          </div>
          {eventsError ? <div className="form-message form-message--error mt-5" role="alert">{eventsError}</div> : null}
          {!eventsError || events.length ? (
            <div className="admin-metrics">
              <MetricCard label="Eventi totali" value={counts.total} hint="Restituiti da GET /api/events" icon={<Gauge size={21} aria-hidden="true" />} />
              <MetricCard label="Ticketmaster" value={counts.ticketmaster} hint="Con fonte Ticketmaster identificata" icon={<Ticket size={21} aria-hidden="true" />} />
              <MetricCard label="Community" value={counts.community} hint="Creati come eventi community" icon={<UsersRound size={21} aria-hidden="true" />} />
              <MetricCard label="Demo" value={counts.demo} hint="Marcati esplicitamente come demo" icon={<Tag size={21} aria-hidden="true" />} />
            </div>
          ) : null}
          {eventsLoading && !events.length ? <p className="admin-loading" role="status"><LoaderCircle className="animate-spin" size={19} aria-hidden="true" /> Caricamento statistiche…</p> : null}
          <div className="admin-overview-actions">
            <button type="button" onClick={() => changeSection("ticketmaster")}><DatabaseZap size={20} aria-hidden="true" /><span><strong>Import Ticketmaster</strong><small>Analizza prima, importa solo con conferma</small></span><ArrowRight size={18} aria-hidden="true" /></button>
            <button type="button" onClick={() => changeSection("events")}><CalendarRange size={20} aria-hidden="true" /><span><strong>Esplora gli eventi</strong><small>Cerca e filtra il catalogo attuale</small></span><ArrowRight size={18} aria-hidden="true" /></button>
          </div>
        </section>
      ) : null}

      {section === "ticketmaster" ? (
        <section className="admin-section" aria-labelledby="ticketmaster-title">
          <div className="admin-section-heading">
            <div><p className="section-kicker">Importazione controllata</p><h2 id="ticketmaster-title">Ticketmaster</h2></div>
          </div>
          <div className="admin-import-card surface-card">
            <div className="admin-import-fields">
              <label><span>Finestra temporale</span><select value={days} disabled={operation !== null} onChange={(event) => setDays(Number(event.target.value))}>{daysOptions.map((value) => <option key={value} value={value}>{value} giorni</option>)}</select></label>
              <label><span>Numero massimo candidati</span><select value={maxCandidates} disabled={operation !== null} onChange={(event) => setMaxCandidates(Number(event.target.value))}>{candidatesOptions.map((value) => <option key={value} value={value}>{value} candidati</option>)}</select></label>
            </div>
            <p className="mt-4 text-sm font-semibold leading-6 text-muted">Il dry-run legge e analizza la fonte senza scrivere dati. L’importazione reale richiede sempre una conferma separata.</p>
            <div className="admin-import-actions">
              <button className="button-secondary inline-flex items-center justify-center gap-2" type="button" disabled={operation !== null} onClick={() => void handleDryRun()}>
                {operation === "dry-run" ? <LoaderCircle className="animate-spin" size={18} aria-hidden="true" /> : <Search size={18} aria-hidden="true" />}
                {operation === "dry-run" ? "Analisi in corso…" : "Esegui dry-run"}
              </button>
              <button className="button-primary inline-flex items-center justify-center gap-2" type="button" disabled={operation !== null} onClick={() => setConfirmImport(true)}>
                <DatabaseZap size={18} aria-hidden="true" /> Importa eventi
              </button>
            </div>
            {operationError ? <div className="form-message form-message--error mt-5" role="alert">{operationError}</div> : null}
          </div>
          {dryRunReport ? <DryRunReport report={dryRunReport} /> : null}
          {importReport ? <ImportReport report={importReport} /> : null}
        </section>
      ) : null}

      {section === "events" ? (
        <section className="admin-section" aria-labelledby="events-title">
          <div className="admin-section-heading">
            <div><p className="section-kicker">Catalogo attuale</p><h2 id="events-title">Eventi</h2></div>
            <button className="button-secondary inline-flex items-center gap-2" type="button" disabled={eventsLoading} onClick={() => void loadEvents()}><RefreshCw className={eventsLoading ? "animate-spin" : ""} size={17} aria-hidden="true" /> Aggiorna</button>
          </div>
          <div className="admin-event-filters surface-card">
            <label className="admin-search"><span className="sr-only">Cerca per titolo o sede</span><Search size={18} aria-hidden="true" /><input type="search" value={search} placeholder="Cerca titolo o sede" onChange={(event) => setSearch(event.target.value)} /></label>
            <label><span className="sr-only">Filtra per fonte</span><select value={sourceFilter} onChange={(event) => setSourceFilter(event.target.value as AdminEventSourceFilter)}><option value="ALL">Tutte le fonti</option><option value="TICKETMASTER">Ticketmaster</option><option value="LINKR">LINKR</option></select></label>
            <label><span className="sr-only">Filtra per categoria</span><select value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value as EventCategory | "ALL")}><option value="ALL">Tutte le categorie</option>{categories.map((category) => <option key={category} value={category}>{categoryLabels[category]}</option>)}</select></label>
          </div>
          {eventsError ? <div className="form-message form-message--error mt-5" role="alert">{eventsError}</div> : null}
          <p className="mt-5 text-sm font-bold text-muted" aria-live="polite">{eventsLoading ? "Aggiornamento eventi…" : `${filteredEvents.length} ${filteredEvents.length === 1 ? "evento" : "eventi"}`}</p>
          <div className="admin-event-list">
            {filteredEvents.map((event) => {
              const imported = event.source?.provider === "TICKETMASTER";
              return (
                <article key={event.id} className="admin-event-card surface-card">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap gap-2">
                      <span className="admin-tag">{categoryLabels[event.category]}</span>
                      <span className="admin-tag" data-tone={imported ? "ticketmaster" : "linkr"}>{imported ? "Ticketmaster" : "LINKR"}</span>
                      <span className="admin-tag" data-tone={event.status === "CANCELLED" ? "cancelled" : "published"}>{event.status === "CANCELLED" ? "Annullato" : "Pubblicato"}</span>
                      {event.demo ? <span className="admin-tag">Demo</span> : null}
                    </div>
                    <h3 className="mt-3 text-xl font-extrabold tracking-[-0.03em] text-ink">{event.title}</h3>
                    <p className="mt-2 text-sm font-semibold leading-6 text-muted">{formatEventDate(event.startsAt)} · {formatEventTime(event.startsAt)} · {event.venueName}</p>
                  </div>
                  <div className="admin-event-actions">
                    <Link to={`/events/${encodeURIComponent(event.id)}`} state={createBackState("admin-events")}>Dettagli <ExternalLink size={15} aria-hidden="true" /></Link>
                    <Link to={`/events/${encodeURIComponent(event.id)}/edit`} state={createBackState("admin-events")}>Modifica <ArrowRight size={15} aria-hidden="true" /></Link>
                  </div>
                </article>
              );
            })}
          </div>
          {!eventsLoading && !filteredEvents.length ? <div className="surface-card mt-4 p-8 text-center font-semibold text-muted">Nessun evento corrisponde ai filtri selezionati.</div> : null}
        </section>
      ) : null}

      {confirmImport ? <ImportConfirmation days={days} maxCandidates={maxCandidates} busy={operation === "import"} onCancel={() => setConfirmImport(false)} onConfirm={() => void handleImport()} /> : null}
    </div>
  );
}
