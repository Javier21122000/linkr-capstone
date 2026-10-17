import axios from "axios";
import { RotateCcw, UsersRound } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { isEventEnded } from "../../lib/eventPresentation";
import {
  getEventPeople,
  getPeopleErrorMessage,
  isPeopleEventEnded,
  isPeopleParticipationRequired,
} from "../../lib/people";
import type { PageResponse, PeopleSort, PersonResponse } from "../../types/people";
import type { ParticipationResponse } from "../../types/participation";
import { UserAvatar } from "../auth/UserAvatar";

type PeopleState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly page: PageResponse<PersonResponse> }
  | { readonly status: "participation-required" }
  | { readonly status: "error"; readonly message: string };

interface EventPeopleSectionProps {
  readonly eventId: string;
  readonly startsAt: string;
  readonly endsAt?: string | null;
  readonly participation: ParticipationResponse | null | undefined;
  readonly participationRevision: number;
}

function appendUniquePeople(current: readonly PersonResponse[], incoming: readonly PersonResponse[]): readonly PersonResponse[] {
  const existingIds = new Set(current.map((person) => person.userId));
  return [...current, ...incoming.filter((person) => !existingIds.has(person.userId))];
}

function sharedInterestsLabel(count: number): string {
  if (count === 0) return "Nessun interesse in comune";
  if (count === 1) return "1 interesse in comune";
  return `${count} interessi in comune`;
}

export function EventPeopleSection({ eventId, startsAt, endsAt, participation, participationRevision }: EventPeopleSectionProps) {
  const { status, authorizedRequest } = useAuth();
  const location = useLocation();
  const [sort, setSort] = useState<PeopleSort>("shared");
  const [pageNumber, setPageNumber] = useState(0);
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<PeopleState>({ status: "idle" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [endedFromServer, setEndedFromServer] = useState(false);
  const requestIdRef = useRef(0);
  const requestPendingRef = useRef(false);
  const loadedParticipationRevisionRef = useRef(participationRevision);
  const ended = endedFromServer || isEventEnded(startsAt, endsAt);
  const hasParticipation = participation !== undefined && participation !== null;
  const canLoadPeople = status === "authenticated" && hasParticipation && !ended;

  useEffect(() => setEndedFromServer(false), [eventId]);

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const participationChanged = loadedParticipationRevisionRef.current !== participationRevision;
    loadedParticipationRevisionRef.current = participationRevision;

    if (!canLoadPeople) {
      requestPendingRef.current = false;
      setState({ status: "idle" });
      setLoadingMore(false);
      setLoadMoreError(null);
      if (pageNumber !== 0) setPageNumber(0);
      return undefined;
    }

    if (participationChanged && pageNumber !== 0) {
      requestPendingRef.current = false;
      setPageNumber(0);
      setLoadMoreError(null);
      return undefined;
    }

    const controller = new AbortController();
    requestPendingRef.current = true;
    setLoadMoreError(null);
    if (pageNumber === 0) setState({ status: "loading" });
    else setLoadingMore(true);

    void authorizedRequest((accessToken) => getEventPeople(accessToken, eventId, {
      sort,
      page: pageNumber,
      signal: controller.signal,
    })).then((response) => {
      if (requestId !== requestIdRef.current) return;
      setState((current) => ({
        status: "success",
        page: {
          ...response,
          items: pageNumber > 0 && current.status === "success"
            ? appendUniquePeople(current.page.items, response.items)
            : response.items,
        },
      }));
    }).catch((error: unknown) => {
      if (axios.isCancel(error) || requestId !== requestIdRef.current) return;
      if (isPeopleEventEnded(error)) {
        setEndedFromServer(true);
      } else if (isPeopleParticipationRequired(error)) {
        setState({ status: "participation-required" });
      } else if (pageNumber > 0) {
        setLoadMoreError(getPeopleErrorMessage(error));
      } else {
        setState({ status: "error", message: getPeopleErrorMessage(error) });
      }
    }).finally(() => {
      if (requestId === requestIdRef.current) {
        requestPendingRef.current = false;
        setLoadingMore(false);
      }
    });

    return () => controller.abort();
  }, [authorizedRequest, canLoadPeople, eventId, pageNumber, participationRevision, requestKey, sort]);

  function changeSort(nextSort: PeopleSort) {
    if (nextSort === sort) return;
    requestIdRef.current += 1;
    requestPendingRef.current = false;
    setSort(nextSort);
    setPageNumber(0);
    setLoadMoreError(null);
  }

  function retry() {
    requestIdRef.current += 1;
    requestPendingRef.current = false;
    setPageNumber(0);
    setRequestKey((current) => current + 1);
  }

  function loadMore() {
    if (requestPendingRef.current || state.status !== "success") return;
    if (state.page.page + 1 >= state.page.totalPages) return;
    requestPendingRef.current = true;
    if (loadMoreError) {
      setRequestKey((current) => current + 1);
      return;
    }
    setPageNumber(state.page.page + 1);
  }

  const loadedPage = state.status === "success" ? state.page : null;
  const hasMore = loadedPage !== null && loadedPage.page + 1 < loadedPage.totalPages;

  return (
    <section className="content-reveal mt-8 border-t border-line pt-8" aria-labelledby="event-people-title">
      <p className="section-kicker">Social discovery</p>
      <h2 id="event-people-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">Chi viene con te?</h2>

      {ended ? (
        <p className="form-message mt-5" role="status">La scoperta delle persone non è disponibile per gli eventi terminati.</p>
      ) : null}

      {!ended && status === "anonymous" ? (
        <div className="mt-5">
          <p className="text-sm leading-6 text-muted">Accedi per scoprire chi è interessato a questo evento.</p>
          <Link className="button-secondary mt-4 inline-flex items-center" to="/login" state={{ from: location }}>Accedi</Link>
        </div>
      ) : null}

      {!ended && status === "authenticated" && participation === undefined ? (
        <div className="mt-5 space-y-3" aria-busy="true" aria-label="Verifica della partecipazione">
          <div className="h-16 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
          <div className="h-16 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
        </div>
      ) : null}

      {!ended && status === "authenticated" && participation === null ? (
        <p className="form-message mt-5" role="status">Scegli “Mi interessa” o “Parteciperò” per vedere le persone che partecipano.</p>
      ) : null}

      {canLoadPeople ? (
        <>
          {participation.visibleToOthers === false ? (
            <p className="mt-4 text-sm leading-6 text-muted">Il tuo profilo resta privato. Puoi comunque esplorare le persone visibili per questo evento.</p>
          ) : null}

          <div className="mt-5 flex flex-wrap gap-2" role="group" aria-label="Ordina le persone">
            <button className="filter-chip" type="button" data-selected={sort === "shared"} aria-pressed={sort === "shared"} onClick={() => changeSort("shared")}>
              Più interessi in comune
            </button>
            <button className="filter-chip" type="button" data-selected={sort === "recent"} aria-pressed={sort === "recent"} onClick={() => changeSort("recent")}>
              Più recenti
            </button>
          </div>

          {state.status === "loading" ? (
            <div className="mt-5 space-y-3" aria-busy="true" aria-label="Caricamento persone">
              {Array.from({ length: 3 }, (_, index) => <div className="h-24 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" key={index} />)}
            </div>
          ) : null}

          {state.status === "error" ? (
            <div className="form-message form-message--error mt-5" role="alert">
              <p>{state.message}</p>
              <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={retry}>
                <RotateCcw size={17} aria-hidden="true" /> Riprova
              </button>
            </div>
          ) : null}

          {state.status === "participation-required" ? (
            <p className="form-message mt-5" role="status">La partecipazione non risulta più attiva. Seleziona nuovamente uno stato per continuare.</p>
          ) : null}

          {loadedPage && loadedPage.items.length === 0 ? (
            <div className="mt-5 rounded-2xl bg-surface p-5 text-center">
              <UsersRound className="mx-auto text-linkr-strong" size={28} aria-hidden="true" />
              <p className="mt-3 font-extrabold text-ink">Ancora nessuna persona visibile.</p>
              <p className="mt-2 text-sm leading-6 text-muted">Quando qualcuno sceglierà di mostrarsi per questo evento, lo troverai qui.</p>
            </div>
          ) : null}

          {loadedPage && loadedPage.items.length > 0 ? (
            <>
              <p className="mt-5 text-sm font-extrabold text-ink" aria-live="polite">
                {loadedPage.totalElements === 1 ? "1 persona visibile" : `${loadedPage.totalElements} persone visibili`}
              </p>
              <ul className="mt-2 divide-y divide-line border-y border-line">
                {loadedPage.items.map((person) => {
                  const visibleInterests = person.sharedInterests.slice(0, 3);
                  const remainingInterests = Math.max(0, person.sharedInterestsCount - visibleInterests.length);
                  return (
                    <li className="flex gap-3 py-5" key={person.userId}>
                      <UserAvatar displayName={person.displayName} avatarUrl={person.avatarUrl} className="size-12 text-sm" />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
                          <div className="min-w-0">
                            <p className="truncate font-extrabold text-ink">{person.displayName}</p>
                            <p className="truncate text-sm font-semibold text-muted">@{person.username}</p>
                          </div>
                          <span className="rounded-full bg-linkr-soft px-2.5 py-1 text-xs font-extrabold text-linkr-strong">
                            {person.status === "GOING" ? "Parteciperà" : "Gli interessa"}
                          </span>
                        </div>
                        <p className="mt-2 text-xs font-bold text-muted">{sharedInterestsLabel(person.sharedInterestsCount)}</p>
                        {visibleInterests.length > 0 ? (
                          <div className="mt-2 flex flex-wrap gap-1.5" aria-label="Interessi condivisi">
                            {visibleInterests.map((interest) => (
                              <span className="rounded-full border border-line bg-surface px-2.5 py-1 text-xs font-bold text-ink" key={interest.id}>{interest.name}</span>
                            ))}
                            {remainingInterests > 0 ? <span className="rounded-full bg-line/70 px-2.5 py-1 text-xs font-extrabold text-muted">+{remainingInterests}</span> : null}
                          </div>
                        ) : null}
                      </div>
                    </li>
                  );
                })}
              </ul>

              <div className="mt-5 text-center">
                {loadMoreError ? <p className="form-message form-message--error mb-3" role="alert">{loadMoreError}</p> : null}
                {hasMore ? (
                  <button className="button-secondary inline-flex min-w-52 items-center" type="button" disabled={loadingMore} onClick={loadMore}>
                    {loadingMore ? "Caricamento…" : loadMoreError ? "Riprova" : "Mostra altre persone"}
                  </button>
                ) : (
                  <p className="text-sm font-semibold text-muted">Hai visto tutte le persone disponibili.</p>
                )}
                <p className="mt-2 text-xs font-bold text-muted">Mostrate {loadedPage.items.length} di {loadedPage.totalElements}</p>
              </div>
            </>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
