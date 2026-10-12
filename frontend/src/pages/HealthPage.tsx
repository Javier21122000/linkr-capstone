import axios from "axios";
import { ArrowLeft, CheckCircle2, RefreshCw, Server, TriangleAlert } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { BrandMark } from "../components/ui/BrandMark";
import { getHealth } from "../lib/health";
import type { HealthResponse } from "../types/health";

type HealthState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly data: HealthResponse }
  | { readonly status: "error"; readonly message: string };

function getErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return "Il browser non ha ricevuto una risposta leggibile. Verifica che il backend sia avviato e che consenta richieste dall’origine del frontend.";
    }

    return `Il backend ha risposto con codice HTTP ${error.response.status}.`;
  }

  if (error instanceof Error) return error.message;
  return "Si è verificato un errore imprevisto durante la verifica.";
}

export function HealthPage() {
  const [attempt, setAttempt] = useState(0);
  const [healthState, setHealthState] = useState<HealthState>({ status: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    async function checkHealth() {
      setHealthState({ status: "loading" });

      try {
        const data = await getHealth(controller.signal);
        setHealthState({ status: "success", data });
      } catch (error) {
        if (axios.isCancel(error)) return;
        setHealthState({ status: "error", message: getErrorMessage(error) });
      }
    }

    void checkHealth();
    return () => controller.abort();
  }, [attempt]);

  return (
    <main className="grid min-h-dvh place-items-center bg-canvas px-5 py-12 text-ink">
      <section className="w-full max-w-2xl rounded-[1.75rem] border border-line bg-surface p-6 sm:p-10" aria-labelledby="health-title">
        <div className="flex items-center justify-between gap-4">
          <BrandMark />
          <span className="rounded-full bg-linkr-soft px-3 py-2 text-xs font-extrabold uppercase tracking-[0.12em] text-linkr-strong">
            Pagina tecnica
          </span>
        </div>

        <div className="mt-12">
          <p className="section-kicker">React ↔ Spring Boot</p>
          <h1 id="health-title" className="mt-3 text-4xl font-extrabold leading-[1] tracking-[-0.055em] sm:text-6xl">
            Stato del collegamento
          </h1>
          <p className="mt-4 max-w-xl text-base leading-7 text-muted">
            Verifica tecnica della comunicazione con il servizio locale di LINKR.
          </p>
        </div>

        <div className="mt-8 rounded-2xl border border-line bg-canvas p-5" aria-live="polite">
          {healthState.status === "loading" && (
            <div className="flex items-start gap-4">
              <RefreshCw className="mt-0.5 shrink-0 animate-spin text-linkr-strong motion-reduce:animate-none" size={22} aria-hidden="true" />
              <div>
                <h2 className="font-extrabold">Connessione al backend in corso…</h2>
                <p className="mt-1 text-sm leading-6 text-muted">Richiesta GET a `/api/health`.</p>
              </div>
            </div>
          )}

          {healthState.status === "success" && (
            <div>
              <div className="flex items-start gap-4">
                <CheckCircle2 className="mt-0.5 shrink-0 text-success" size={24} aria-hidden="true" />
                <div>
                  <h2 className="text-lg font-extrabold text-success">Collegamento riuscito</h2>
                  <p className="mt-1 text-sm leading-6 text-muted">Il frontend ha ricevuto una risposta valida dal backend.</p>
                </div>
              </div>
              <dl className="mt-6 grid gap-3 sm:grid-cols-3">
                <div className="rounded-xl bg-surface p-4">
                  <dt className="text-xs font-extrabold uppercase tracking-[0.12em] text-muted">Stato</dt>
                  <dd className="mt-2 font-extrabold text-ink">{healthState.data.status}</dd>
                </div>
                <div className="rounded-xl bg-surface p-4">
                  <dt className="text-xs font-extrabold uppercase tracking-[0.12em] text-muted">Applicazione</dt>
                  <dd className="mt-2 font-extrabold text-ink">{healthState.data.application}</dd>
                </div>
                <div className="rounded-xl bg-surface p-4">
                  <dt className="text-xs font-extrabold uppercase tracking-[0.12em] text-muted">Servizio</dt>
                  <dd className="mt-2 font-extrabold text-ink">{healthState.data.service}</dd>
                </div>
              </dl>
            </div>
          )}

          {healthState.status === "error" && (
            <div className="flex items-start gap-4">
              <TriangleAlert className="mt-0.5 shrink-0 text-linkr-strong" size={24} aria-hidden="true" />
              <div>
                <h2 className="text-lg font-extrabold text-linkr-strong">Collegamento non riuscito</h2>
                <p className="mt-2 text-sm leading-6 text-muted">{healthState.message}</p>
                <button className="button-primary mt-5 inline-flex items-center gap-2" type="button" onClick={() => setAttempt((current) => current + 1)}>
                  <RefreshCw size={17} aria-hidden="true" /> Riprova
                </button>
              </div>
            </div>
          )}
        </div>

        <div className="mt-6 flex flex-wrap items-center justify-between gap-4 text-sm text-muted">
          <span className="inline-flex items-center gap-2">
            <Server size={17} aria-hidden="true" /> Backend previsto su localhost:8080
          </span>
          <Link to="/" className="inline-flex min-h-11 items-center gap-2 rounded-full px-3 font-extrabold text-ink">
            <ArrowLeft size={17} aria-hidden="true" /> Torna a Discover
          </Link>
        </div>
      </section>
    </main>
  );
}
