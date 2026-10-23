/**
 * Token d'invito nel link dell'evento (`?i=<token>`): forma, lettura dall'indirizzo e cosa mostrare in base allo stato del Link.
 * Puro e senza dipendenze (si prova con `node --test`). Il token è firmato dal servizio: qui si controlla solo la FORMA, per non
 * mandare al servizio testo arbitrario; chi può fidarsi del contenuto è solo il servizio (`GET /api/invites/{token}`).
 */

export const INVITE_PARAM = "i";

/** 41 byte di dati (55 caratteri base64url) + "." + firma HMAC-SHA256 di 32 byte (43 caratteri). */
const INVITE_TOKEN = /^[A-Za-z0-9_-]{55}\.[A-Za-z0-9_-]{43}$/;

export function isInviteToken(value: unknown): value is string {
  return typeof value === "string" && INVITE_TOKEN.test(value);
}

/** Il token d'invito presente nella query (`?i=…`), oppure `null`: assente, ripetuto in modo ambiguo o con una forma che non può essere valida. */
export function readInviteToken(search: string): string | null {
  const values = new URLSearchParams(search).getAll(INVITE_PARAM);
  return values.length === 1 && isInviteToken(values[0]) ? values[0] : null;
}

/** Stato del Link tra chi guarda e chi ha condiviso, come lo restituisce il servizio. */
export type InviteLinkStatus = "NONE" | "OUTGOING_PENDING" | "INCOMING_PENDING" | "INCOMING_DECLINED" | "ACCEPTED";

/** Cosa propone la scheda: l'azione esplicita, oppure solo lo stato già esistente. `none` = nessuna proposta (es. richiesta rifiutata o stato sconosciuto). */
export type InviteLinkView = "offer" | "sent" | "received" | "connected" | "none";

export function describeInviteLink(status: unknown): InviteLinkView {
  switch (status) {
    case "NONE": return "offer";
    case "OUTGOING_PENDING": return "sent";
    case "INCOMING_PENDING": return "received";
    case "ACCEPTED": return "connected";
    default: return "none";
  }
}
