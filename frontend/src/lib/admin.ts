import axios from "axios";

import type { TicketmasterDryRunReport, TicketmasterImportReport } from "../types/admin";
import type { EventSummary } from "../types/event";
import { apiClient } from "./api";

function authHeaders(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function getAdminEvents(accessToken: string, signal?: AbortSignal): Promise<EventSummary[]> {
  const { data } = await apiClient.get<EventSummary[]>("/api/events", {
    headers: authHeaders(accessToken),
    signal,
  });
  return data;
}

export async function runTicketmasterDryRun(accessToken: string, days: number, maxCandidates: number): Promise<TicketmasterDryRunReport> {
  const { data } = await apiClient.post<TicketmasterDryRunReport>("/api/admin/import/ticketmaster/dry-run", null, {
    headers: authHeaders(accessToken),
    params: { days, maxCandidates },
    timeout: 60_000,
  });
  return data;
}

export async function runTicketmasterImport(accessToken: string, days: number, maxCandidates: number): Promise<TicketmasterImportReport> {
  const { data } = await apiClient.post<TicketmasterImportReport>("/api/admin/import/ticketmaster", null, {
    headers: authHeaders(accessToken),
    params: { days, maxCandidates },
    timeout: 60_000,
  });
  return data;
}

export function getAdminRequestErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) return "Si è verificato un errore imprevisto. Riprova.";
  if (!error.response) return "Il backend non è raggiungibile o ha impiegato troppo tempo a rispondere.";
  if (error.response.status === 401) return "La sessione è scaduta. Accedi di nuovo per continuare.";
  if (error.response.status === 403) return "Non hai i permessi di amministratore necessari.";
  if (error.response.status === 409) return "Un’altra operazione Ticketmaster è già in corso. Attendi e riprova.";
  if (error.response.status === 429) return "Il limite di richieste è stato raggiunto. Attendi prima di riprovare.";
  if (error.response.status >= 500) return "Il backend non ha completato l’operazione. Nessun nuovo tentativo è stato avviato automaticamente.";
  if (error.response.status === 400) return "I parametri richiesti non sono accettati dalla configurazione corrente.";
  return `La richiesta non è riuscita (HTTP ${error.response.status}).`;
}
