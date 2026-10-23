/**
 * Condivisione di un evento: indirizzo pubblico, testo dell'invito e invio (Web Share API con ripiego sulla copia del link).
 * Nessuna dipendenza dal DOM o da React: tutto ciò che tocca il browser arriva da `ShareEnvironment`, quindi si prova con `node --test`.
 */

import { INVITE_PARAM, isInviteToken } from "./inviteToken.ts";

const EVENT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
/** Nomi che non raggiungono nessun altro dispositivo: un invito con quell'indirizzo non si aprirebbe a chi lo riceve. */
const NON_PUBLIC_HOST = /(^|\.)(localhost|local|internal|lan|home|test|invalid|example|localdomain)$/i;
const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/** `https://host[:porta]` pubblico: no `http`, no localhost, no indirizzi IP, no host a etichetta singola o con suffissi riservati. */
export function toPublicOrigin(value: string | null | undefined): string | null {
  if (!value) return null;
  let url: URL;
  try {
    url = new URL(value.trim());
  } catch {
    return null;
  }
  if (url.protocol !== "https:" || url.username || url.password) return null;
  const host = url.hostname;
  if (!host.includes(".") || host.startsWith("[") || IPV4.test(host) || NON_PUBLIC_HOST.test(host)) return null;
  return url.origin;
}

/**
 * Origine da cui costruire gli inviti: quella configurata per il sito (`VITE_PUBLIC_SITE_URL`) se è pubblica, altrimenti quella da cui
 * l'app è aperta se è pubblica. `null` = nessun indirizzo pubblico (sviluppo in locale): in quel caso non si offre la condivisione.
 */
export function resolveShareOrigin(configured: string | null | undefined, current: string | null | undefined): string | null {
  return toPublicOrigin(configured) ?? toPublicOrigin(current);
}

/**
 * Indirizzo canonico e stabile dell'evento: origine + `/events/<id>`. Senza invito, mai query né hash. Con `inviteToken` (emesso dal servizio
 * per chi è autenticato) si aggiunge solo `?i=<token>`, e solo se ha la forma di un token: nessun nome, id o testo scelto dal client.
 * Il link senza token funziona sempre.
 */
export function buildEventShareUrl(origin: string | null, eventId: string, inviteToken?: string | null): string | null {
  const publicOrigin = toPublicOrigin(origin);
  if (!publicOrigin || !EVENT_ID.test(eventId)) return null;
  const base = `${publicOrigin}/events/${eventId.toLowerCase()}`;
  return isInviteToken(inviteToken) ? `${base}?${INVITE_PARAM}=${inviteToken}` : base;
}

export interface ShareInviteInput {
  readonly title: string;
  /** Quando, già formattato per chi legge (es. «gio 5 nov · 20:00–22:30»). */
  readonly when: string;
  /** Dove, già formattato (es. «Casa Comune · Porta Venezia»). */
  readonly where: string;
  readonly url: string;
}

export interface SharePayload {
  readonly title: string;
  readonly text: string;
  readonly url: string;
}

/** Il messaggio dell'invito: titolo, quando e dove, e l'invito. Solo dati già pubblici nella pagina dell'evento. */
export function buildSharePayload({ title, when, where, url }: ShareInviteInput): SharePayload {
  const cleanTitle = title.trim();
  return {
    title: cleanTitle,
    text: `${cleanTitle}\n${when} · ${where}\nVieni anche tu? Guarda l'evento su LINKR.`,
    url,
  };
}

export type ShareOutcome = "shared" | "copied" | "cancelled" | "failed";

/** Quello che il browser offre. Ogni voce può mancare: Safari desktop e Firefox non hanno `share`, un contesto non sicuro non ha `clipboard`. */
export interface ShareEnvironment {
  readonly share?: (data: SharePayload) => Promise<void>;
  readonly canShare?: (data: SharePayload) => boolean;
  readonly writeClipboard?: (text: string) => Promise<void>;
  /** Ripiego sincrono (`execCommand("copy")`); restituisce se la copia è riuscita. */
  readonly legacyCopy?: (text: string) => boolean;
}

/** Chiudere il foglio di condivisione (o annullarlo) è una scelta dell'utente, non un errore. */
export function isShareCancellation(error: unknown): boolean {
  return typeof error === "object" && error !== null && "name" in error && (error as { name?: unknown }).name === "AbortError";
}

async function copyLink(url: string, env: ShareEnvironment): Promise<boolean> {
  if (env.writeClipboard) {
    try {
      await env.writeClipboard(url);
      return true;
    } catch {
      // Permesso negato o contesto non sicuro: si prova il ripiego.
    }
  }
  try {
    return env.legacyCopy?.(url) === true;
  } catch {
    return false;
  }
}

/**
 * Condivide l'invito: foglio di sistema se c'è, altrimenti (o se il sistema lo rifiuta) copia il link.
 * `share` va chiamato per primo e senza attese prima: Safari lo accetta solo dentro il gesto dell'utente.
 */
export async function shareEvent(payload: SharePayload, env: ShareEnvironment): Promise<ShareOutcome> {
  if (env.share && (!env.canShare || env.canShare(payload))) {
    try {
      await env.share(payload);
      return "shared";
    } catch (error) {
      if (isShareCancellation(error)) return "cancelled";
      // Il sistema non può condividere adesso (permesso, contesto): resta la copia del link.
    }
  }
  return (await copyLink(payload.url, env)) ? "copied" : "failed";
}

/** L'ambiente reale del browser. Si passano `navigator` e `document` per poterlo provare con oggetti finti. */
export function browserShareEnvironment(nav: Navigator | undefined, doc: Document | undefined): ShareEnvironment {
  const share = nav && typeof nav.share === "function" ? (data: SharePayload) => nav.share(data) : undefined;
  const canShare = nav && typeof nav.canShare === "function" ? (data: SharePayload) => nav.canShare(data) : undefined;
  const writeClipboard = nav?.clipboard && typeof nav.clipboard.writeText === "function" ? (text: string) => nav.clipboard.writeText(text) : undefined;
  const legacyCopy = doc && typeof doc.execCommand === "function" ? (text: string) => legacyCopyText(doc, text) : undefined;
  return { share, canShare, writeClipboard, legacyCopy };
}

function legacyCopyText(doc: Document, text: string): boolean {
  const field = doc.createElement("textarea");
  field.value = text;
  field.setAttribute("readonly", "");
  field.setAttribute("aria-hidden", "true");
  // Fuori dal flusso e a 16 px: niente scorrimento, niente zoom di Safari iOS sui campi piccoli.
  field.style.cssText = "position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;font-size:16px;pointer-events:none;";
  const previous = doc.activeElement instanceof HTMLElement ? doc.activeElement : null;
  doc.body.appendChild(field);
  try {
    field.focus({ preventScroll: true });
    field.select();
    field.setSelectionRange(0, text.length);
    return doc.execCommand("copy");
  } finally {
    field.remove();
    previous?.focus({ preventScroll: true });
  }
}
