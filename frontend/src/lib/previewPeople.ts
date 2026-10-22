import type { PageResponse, PersonResponse } from "../types/people";

/** Pagina più grande che l'API concede: una sola richiesta per l'evento selezionato, abbastanza per contare davvero chi viene e chi è interessato. */
export const PREVIEW_PEOPLE_FETCH_SIZE = 50;
export const PREVIEW_AVATARS = 4;

export interface PreviewPeopleSummary {
  /** Persone con partecipazione pubblica, mai l'utente stesso (lo garantisce l'API). */
  readonly total: number;
  /** Conteggi per stato. Esatti solo se `exact`: altrimenti sono quelli della pagina letta (un minimo). */
  readonly going: number;
  readonly interested: number;
  readonly exact: boolean;
  /** Fino a PREVIEW_AVATARS persone: prima chi viene, poi gli interessati. */
  readonly avatars: readonly PersonResponse[];
}

/** Riassume la prima pagina di `GET /api/events/{id}/people`. Non inventa nulla: con `total` oltre la pagina i conteggi per stato sono un minimo. */
export function summarizePreviewPeople(page: PageResponse<PersonResponse>): PreviewPeopleSummary {
  const going = page.items.filter((person) => person.status === "GOING");
  const interested = page.items.filter((person) => person.status === "INTERESTED");
  return {
    total: page.totalElements,
    going: going.length,
    interested: interested.length,
    exact: page.totalElements <= page.items.length,
    avatars: [...going, ...interested].slice(0, PREVIEW_AVATARS),
  };
}

/** Testo breve e vero: «3 vengono · 2 interessati», con «almeno» se il conteggio è parziale. Vuoto = nessuno da mostrare. */
export function previewPeopleLabel(summary: PreviewPeopleSummary): string {
  const parts: string[] = [];
  const prefix = summary.exact ? "" : "almeno ";
  if (summary.going > 0) parts.push(`${prefix}${summary.going} ${summary.going === 1 ? "viene" : "vengono"}`);
  if (summary.interested > 0) parts.push(`${prefix}${summary.interested} ${summary.interested === 1 ? "interessato" : "interessati"}`);
  return parts.join(" · ");
}
