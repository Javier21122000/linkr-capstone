import type { EventSource } from "../types/event";

const providerLabels: Readonly<Record<string, string>> = {
  TICKETMASTER: "Ticketmaster",
};

/** Nome da mostrare per l'origine di un evento importato, o null se l'evento è nato in LINKR (o l'origine non è nota). */
export function sourceLabel(source: EventSource | null | undefined): string | null {
  if (!source) return null;
  return providerLabels[source.provider] ?? null;
}

/**
 * Link alla pagina ufficiale dell'evento, solo se è sicuro aprirlo: un URL https, con un nome di dominio vero e senza credenziali
 * nell'indirizzo. Qualunque altra cosa (http, javascript:, data:, URL relativi o malformati) dà null e il pulsante non compare.
 * Il backend salva già solo link di questo tipo: questa è la seconda barriera, perché il valore arriva da una risposta di rete.
 */
export function officialDetailsUrl(source: EventSource | null | undefined): string | null {
  if (!source?.url) return null;
  try {
    const url = new URL(source.url);
    if (url.protocol !== "https:" || url.username !== "" || url.password !== "" || !url.hostname.includes(".")) return null;
    return url.href;
  } catch {
    return null;
  }
}
