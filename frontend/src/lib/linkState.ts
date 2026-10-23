import type { LinkRequestResponse } from "../types/linkRequest";
import type { LinkStatus } from "../types/people";

/**
 * Stato Link di una persona: come cambia dopo una risposta del servizio e che cosa offre il suo profilo pubblico.
 * Modulo senza dipendenze (testabile con `node --test`): le chiamate al servizio stanno in `linkRequests.ts`.
 */

/** Spiega perché non c'è il pulsante "Invia Link": senza almeno un interesse in comune il servizio rifiuta l'invio (404 uniforme). */
export const LINK_NEEDS_SHARED_INTEREST_HINT = "Puoi inviare un Link quando avete almeno un interesse in comune.";

/** Stato Link di una persona dopo una risposta del servizio (invio, accetta, rifiuta): lo stesso calcolo per ogni schermata. */
export function linkStatusFromResponse(response: Pick<LinkRequestResponse, "status" | "direction">): LinkStatus {
  if (response.status === "ACCEPTED") return "ACCEPTED";
  if (response.direction === "OUTGOING") return "OUTGOING_PENDING";
  if (response.status === "DECLINED") return "INCOMING_DECLINED";
  return "INCOMING_PENDING";
}

/**
 * Titoli degli errori che dicono "lo stato che vedi non è più quello del servizio": la richiesta esiste già (magari inviata dall'altra
 * persona nel frattempo), è già stata gestita, è sparita, o la persona non è più raggiungibile. Ripetere il clic non servirebbe.
 */
const STALE_STATE_TITLES: ReadonlySet<string> = new Set([
  "Link request already exists",
  "Link request already answered",
  "Link request not found",
  "Person not available",
]);

export function isStaleLinkStateTitle(title: unknown): boolean {
  return typeof title === "string" && STALE_STATE_TITLES.has(title);
}

/** Che cosa mostra il profilo pubblico di una persona rispetto al Link. */
export type ProfileLinkAffordance =
  /** Il proprio profilo: nessuna azione Link. */
  | { readonly kind: "self" }
  /** Nessuna relazione: si può inviare. */
  | { readonly kind: "send" }
  /** Nessuna relazione, ma senza interessi in comune il servizio non accetterebbe l'invio. */
  | { readonly kind: "needs-shared-interest" }
  /** Inviata da te: in attesa di risposta (anche se l'altra persona l'ha rifiutata: il rifiuto non si rivela al mittente). */
  | { readonly kind: "awaiting-answer" }
  /** Ricevuta e gestibile: si può accettare. */
  | { readonly kind: "can-accept"; readonly requestId: string }
  /** Ricevuta, ma senza identificativo con cui rispondere. */
  | { readonly kind: "received" }
  /** Ricevuta e archiviata da te. */
  | { readonly kind: "archived" }
  /** Collegati: si può scrivere. La conversazione ha lo stesso identificativo della richiesta. */
  | { readonly kind: "can-message"; readonly conversationId: string }
  /** Collegati, ma senza identificativo della conversazione. */
  | { readonly kind: "connected" };

interface ProfileLinkInput {
  /** Chi guarda (`null` se non si sa). */
  readonly viewerId: string | null;
  readonly userId: string;
  readonly linkStatus: LinkStatus;
  readonly linkRequestId: string | null;
  readonly sharedInterestsCount: number;
}

export function getProfileLinkAffordance({ viewerId, userId, linkStatus, linkRequestId, sharedInterestsCount }: ProfileLinkInput): ProfileLinkAffordance {
  if (viewerId !== null && viewerId.toLowerCase() === userId.toLowerCase()) return { kind: "self" };

  switch (linkStatus) {
    case "NONE":
      return sharedInterestsCount > 0 ? { kind: "send" } : { kind: "needs-shared-interest" };
    case "OUTGOING_PENDING":
      return { kind: "awaiting-answer" };
    case "INCOMING_PENDING":
      return linkRequestId ? { kind: "can-accept", requestId: linkRequestId } : { kind: "received" };
    case "INCOMING_DECLINED":
      return { kind: "archived" };
    case "ACCEPTED":
      return linkRequestId ? { kind: "can-message", conversationId: linkRequestId } : { kind: "connected" };
  }
}
