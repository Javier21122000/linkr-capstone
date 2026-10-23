import { Check, UserPlus } from "lucide-react";
import { Link } from "react-router";

import { useEventInvite } from "../../hooks/useEventInvite";
import { describeInviteLink } from "../../lib/inviteToken";
import { UserAvatar } from "../auth/UserAvatar";

interface InviteSharedByProps {
  readonly eventId: string;
  /** Token letto dal link (`?i=`), già controllato nella forma; senza, la scheda non esiste. */
  readonly token: string | null;
}

/**
 * «Evento condiviso da [nome]» sul dettaglio, per chi apre un link con invito da autenticato (anche appena registrato). NON dice che il mittente
 * abbia invitato questa persona: dice solo chi ha condiviso. Il Link è un'azione a parte, esplicita e distinta da «Parteciperò»: non parte mai
 * da sola e non blocca niente. Appare solo se il servizio conferma l'invito e la visibilità del mittente.
 */
export function InviteSharedBy({ eventId, token }: InviteSharedByProps) {
  const { invite, sending, error, connect } = useEventInvite(eventId, token);
  if (!invite) return null;

  const name = invite.sender.displayName;
  const view = describeInviteLink(invite.linkStatus);

  return (
    <aside className="mt-4 flex items-start gap-3 rounded-2xl bg-white/70 p-3 ring-1 ring-line" aria-label="Chi ha condiviso questo evento" data-invite-card>
      <UserAvatar displayName={name} avatarUrl={invite.sender.avatarUrl} className="size-10 text-sm" />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-extrabold leading-5 text-ink">Evento condiviso da {name}</p>

        {view === "offer" ? (
          <>
            <p className="mt-0.5 text-xs font-semibold leading-5 text-muted">Se vuoi, puoi inviargli una richiesta Link: potrà accettarla o ignorarla.</p>
            <button className="button-secondary mt-2 inline-flex items-center gap-2" type="button" disabled={sending} aria-busy={sending} onClick={() => void connect()}>
              <UserPlus size={17} aria-hidden="true" /> {sending ? "Invio…" : `Connettiti con ${name}`}
            </button>
          </>
        ) : null}
        {view === "sent" ? (
          <p className="mt-0.5 inline-flex items-center gap-1.5 text-xs font-bold leading-5 text-muted" role="status">
            <Check size={14} strokeWidth={2.6} aria-hidden="true" /> Richiesta Link inviata a {name}
          </p>
        ) : null}
        {view === "received" ? (
          <p className="mt-0.5 text-xs font-semibold leading-5 text-muted" role="status">
            {name} ti ha inviato una richiesta Link.{" "}
            <Link className="font-extrabold text-ink underline decoration-linkr/50 underline-offset-4" to="/profile/links">Vedi le richieste</Link>
          </p>
        ) : null}
        {view === "connected" ? (
          <p className="mt-0.5 text-xs font-semibold leading-5 text-muted" role="status">
            Tu e {name} siete già collegati.{" "}
            <Link className="font-extrabold text-ink underline decoration-linkr/50 underline-offset-4" to="/chats">Scrivi in chat</Link>
          </p>
        ) : null}

        {error ? <p className="mt-2 text-xs font-bold leading-5 text-danger" role="alert">{error}</p> : null}
      </div>
    </aside>
  );
}
