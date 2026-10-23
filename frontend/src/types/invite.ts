import type { InviteLinkStatus } from "../lib/inviteToken";

/** Chi ha condiviso l'evento, per chi apre il link da autenticato: solo nome visualizzato e avatar, e solo se il mittente è visibile. */
export interface InviteResponse {
  readonly eventId: string;
  readonly sender: { readonly displayName: string; readonly avatarUrl: string | null };
  readonly linkStatus: InviteLinkStatus;
  readonly linkRequestId: string | null;
}

export interface IssuedInviteResponse {
  readonly token: string;
  readonly expiresAt: string;
}
