import axios from "axios";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

import { getMyParticipations } from "../lib/participations";
import type { ParticipationStatus } from "../types/participation";
import { useAuth } from "./AuthContext";

type ParticipationMap = ReadonlyMap<string, ParticipationStatus>;

interface MyParticipationsValue {
  /**
   * Lo stato PERSONALE dell'utente autenticato per ogni evento a cui ha risposto (`GOING` o `INTERESTED`). Dati reali di
   * `GET /api/users/me/participations`: da anonimi (o finché non sono arrivati, o se la richiesta fallisce) la mappa è vuota e non si mostra nulla.
   */
  readonly byEventId: ParticipationMap;
  /** Rilegge l'elenco dal servizio (Discover lo fa quando torna visibile). Più richieste ravvicinate si fondono in una. */
  readonly refresh: () => Promise<void>;
  /** Applica subito una modifica appena confermata dal servizio (`null` = partecipazione rimossa), senza aspettare un nuovo caricamento. */
  readonly apply: (eventId: string, status: ParticipationStatus | null) => void;
}

const EMPTY: ParticipationMap = new Map();
const MyParticipationsContext = createContext<MyParticipationsValue | null>(null);

export function MyParticipationsProvider({ children }: { readonly children: ReactNode }) {
  const { status, user, authorizedRequest } = useAuth();
  const ownerId = status === "authenticated" && user ? user.id : null;
  const [state, setState] = useState<{ readonly ownerId: string; readonly byEventId: ParticipationMap } | null>(null);
  const requestRef = useRef<{ readonly ownerId: string; readonly promise: Promise<void> } | null>(null);
  const requestIdRef = useRef(0);

  const refresh = useCallback((): Promise<void> => {
    if (ownerId === null) return Promise.resolve();
    const active = requestRef.current;
    if (active?.ownerId === ownerId) return active.promise;

    const requestId = ++requestIdRef.current;
    const promise = authorizedRequest((accessToken) => getMyParticipations(accessToken))
      .then((response) => {
        if (requestId !== requestIdRef.current) return;
        const next = new Map<string, ParticipationStatus>();
        for (const participation of response.participations) next.set(participation.event.id, participation.status);
        setState({ ownerId, byEventId: next });
      })
      .catch((error: unknown) => {
        // Senza l'elenco non si mostra nulla (mai un indicatore inventato): resta ciò che si sapeva già.
        if (axios.isCancel(error)) return;
      })
      .finally(() => {
        if (requestId === requestIdRef.current) requestRef.current = null;
      });
    requestRef.current = { ownerId, promise };
    return promise;
  }, [authorizedRequest, ownerId]);

  // Accesso, cambio utente o uscita: si riparte dallo stato di quell'utente, mai da quello di un altro.
  useEffect(() => {
    requestIdRef.current += 1;
    requestRef.current = null;
    setState(null);
    if (ownerId !== null) void refresh();
  }, [ownerId, refresh]);

  const apply = useCallback((eventId: string, next: ParticipationStatus | null) => {
    if (ownerId === null) return;
    setState((current) => {
      const base = current?.ownerId === ownerId ? current.byEventId : EMPTY;
      if (next === null ? !base.has(eventId) : base.get(eventId) === next) return current ?? { ownerId, byEventId: base };
      const updated = new Map(base);
      if (next === null) updated.delete(eventId);
      else updated.set(eventId, next);
      return { ownerId, byEventId: updated };
    });
  }, [ownerId]);

  const value = useMemo<MyParticipationsValue>(() => ({
    byEventId: ownerId !== null && state?.ownerId === ownerId ? state.byEventId : EMPTY,
    refresh,
    apply,
  }), [apply, ownerId, refresh, state]);

  return <MyParticipationsContext.Provider value={value}>{children}</MyParticipationsContext.Provider>;
}

export function useMyParticipations(): MyParticipationsValue {
  const context = useContext(MyParticipationsContext);
  if (!context) throw new Error("useMyParticipations must be used within MyParticipationsProvider");
  return context;
}
