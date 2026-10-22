import axios from "axios";
import { Check, Clock3, Link2, RotateCcw, UserPlus, UsersRound, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { WriteMessageLink } from "../components/chat/WriteMessageLink";
import { EmptyState } from "../components/ui/EmptyState";
import { useAuth } from "../context/AuthContext";
import { useLinkRequestCount } from "../context/LinkRequestCountContext";
import { getAuthRequestErrorMessage, updateDiscovery } from "../lib/auth";
import {
  acceptLinkRequest,
  declineLinkRequest,
  getLinkRequestErrorMessage,
  sendLinkRequest,
} from "../lib/linkRequests";
import {
  getDiscoverPeople,
  getDiscoverPeopleErrorMessage,
  isDiscoveryRequired,
} from "../lib/people";
import { staggerStyle } from "../lib/motion";
import type { LinkRequestResponse } from "../types/linkRequest";
import type { DiscoverPersonResponse, LinkStatus, PageResponse } from "../types/people";

type PeopleState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly page: PageResponse<DiscoverPersonResponse> }
  | { readonly status: "discovery-required" }
  | { readonly status: "error"; readonly message: string };

type LinkAction = "send" | "accept" | "decline";

function appendUniquePeople(
  current: readonly DiscoverPersonResponse[],
  incoming: readonly DiscoverPersonResponse[],
): readonly DiscoverPersonResponse[] {
  const existingIds = new Set(current.map((person) => person.userId));
  return [...current, ...incoming.filter((person) => !existingIds.has(person.userId))];
}

function sharedInterestsLabel(count: number): string {
  if (count === 0) return "Nessun interesse in comune";
  if (count === 1) return "1 interesse in comune";
  return `${count} interessi in comune`;
}

function linkStatusFromResponse(response: LinkRequestResponse): LinkStatus {
  if (response.status === "ACCEPTED") return "ACCEPTED";
  if (response.direction === "OUTGOING") return "OUTGOING_PENDING";
  if (response.status === "DECLINED") return "INCOMING_DECLINED";
  return "INCOMING_PENDING";
}

export function DiscoverPeoplePage() {
  const { authorizedRequest } = useAuth();
  const { refreshIncomingCount } = useLinkRequestCount();
  const [pageNumber, setPageNumber] = useState(0);
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<PeopleState>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [activating, setActivating] = useState(false);
  const [activationError, setActivationError] = useState<string | null>(null);
  const requestIdRef = useRef(0);
  const requestPendingRef = useRef(false);
  const linkActionLocksRef = useRef(new Set<string>());
  const [pendingLinkActions, setPendingLinkActions] = useState<Readonly<Record<string, LinkAction>>>({});
  const [linkActionErrors, setLinkActionErrors] = useState<Readonly<Record<string, string>>>({});

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const controller = new AbortController();
    requestPendingRef.current = true;
    setLoadMoreError(null);
    if (pageNumber === 0) setState({ status: "loading" });
    else setLoadingMore(true);

    void authorizedRequest((accessToken) => getDiscoverPeople(accessToken, pageNumber, 20, controller.signal))
      .then((response) => {
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
      })
      .catch((error: unknown) => {
        if (axios.isCancel(error) || requestId !== requestIdRef.current) return;
        if (isDiscoveryRequired(error)) setState({ status: "discovery-required" });
        else if (pageNumber > 0) setLoadMoreError(getDiscoverPeopleErrorMessage(error));
        else setState({ status: "error", message: getDiscoverPeopleErrorMessage(error) });
      })
      .finally(() => {
        if (requestId === requestIdRef.current) {
          requestPendingRef.current = false;
          setLoadingMore(false);
        }
      });

    return () => controller.abort();
  }, [authorizedRequest, pageNumber, requestKey]);

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
    if (loadMoreError) setRequestKey((current) => current + 1);
    else setPageNumber(state.page.page + 1);
  }

  async function activateDiscovery() {
    if (activating) return;
    setActivating(true);
    setActivationError(null);
    try {
      await authorizedRequest((accessToken) => updateDiscovery(accessToken, true));
      retry();
    } catch (error) {
      setActivationError(getAuthRequestErrorMessage(error));
    } finally {
      setActivating(false);
    }
  }

  async function handleLinkAction(person: DiscoverPersonResponse, action: LinkAction) {
    if (linkActionLocksRef.current.has(person.userId)) return;
    if (action !== "send" && !person.linkRequestId) return;

    linkActionLocksRef.current.add(person.userId);
    setPendingLinkActions((current) => ({ ...current, [person.userId]: action }));
    setLinkActionErrors((current) => {
      const next = { ...current };
      delete next[person.userId];
      return next;
    });

    try {
      const response = await authorizedRequest((accessToken) => {
        if (action === "send") return sendLinkRequest(accessToken, { recipientId: person.userId });
        if (action === "accept") return acceptLinkRequest(accessToken, person.linkRequestId!);
        return declineLinkRequest(accessToken, person.linkRequestId!);
      });
      if (action !== "send") void refreshIncomingCount(true);
      setState((current) => current.status === "success" ? {
        status: "success",
        page: {
          ...current.page,
          items: current.page.items.map((item) => item.userId === person.userId ? {
            ...item,
            linkStatus: linkStatusFromResponse(response),
            linkRequestId: response.requestId,
          } : item),
        },
      } : current);
    } catch (error) {
      if (isDiscoveryRequired(error)) setState({ status: "discovery-required" });
      else setLinkActionErrors((current) => ({ ...current, [person.userId]: getLinkRequestErrorMessage(error) }));
    } finally {
      linkActionLocksRef.current.delete(person.userId);
      setPendingLinkActions((current) => {
        const next = { ...current };
        delete next[person.userId];
        return next;
      });
    }
  }

  const loadedPage = state.status === "success" ? state.page : null;
  const hasMore = loadedPage !== null && loadedPage.page + 1 < loadedPage.totalPages;

  return (
    <div className="page-shell page-with-mobile-nav py-8 sm:py-12 lg:py-16">
      <section className="mx-auto max-w-5xl" aria-labelledby="discover-people-title">
        <p className="section-kicker">La community di LINKR</p>
        <h1 id="discover-people-title" className="mt-2 text-4xl font-extrabold tracking-[-0.05em] text-ink sm:text-5xl">Scopri persone</h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-muted">Incontra persone che hanno scelto di comparire. Per prime trovi chi condivide più interessi con te.</p>
        <p className="mt-2 text-xs font-bold text-muted">L’ordine è calcolato dal servizio in base agli interessi condivisi.</p>

        {state.status === "loading" ? (
          <div className="mt-8 grid gap-4 md:grid-cols-2" aria-busy="true" aria-label="Caricamento persone">
            {Array.from({ length: 4 }, (_, index) => <div className="h-56 animate-pulse rounded-[1.5rem] bg-white/70 ring-1 ring-black/5 motion-reduce:animate-none" key={index} />)}
          </div>
        ) : null}

        {state.status === "discovery-required" ? (
          <div className="surface-card content-reveal mt-8 px-6 py-10" role="status">
            <EmptyState
              className="mx-auto max-w-md"
              illustration={<span className="grid size-14 place-items-center rounded-full bg-linkr-soft text-linkr-strong"><UsersRound size={28} aria-hidden="true" /></span>}
              title="Scegli tu se comparire"
              description="Per tutelare la privacy, puoi vedere le altre persone solo se scegli esplicitamente di comparire anche tu in Scopri persone."
              action={(
                <button className="button-primary inline-flex items-center" type="button" disabled={activating} onClick={() => void activateDiscovery()}>
                  {activating ? "Attivazione…" : "Attiva Scopri persone"}
                </button>
              )}
            />
            {activationError ? <p className="mt-3 text-center text-sm font-bold text-danger" role="alert">{activationError}</p> : null}
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="form-message form-message--error mt-8" role="alert">
            <p>{state.message}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={retry}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length === 0 ? (
          <div className="surface-card content-reveal mt-8 px-6 py-10" role="status">
            <EmptyState
              className="mx-auto max-w-md"
              illustration={<span className="grid size-14 place-items-center rounded-full bg-linkr-soft text-linkr-strong"><UsersRound size={28} aria-hidden="true" /></span>}
              title="Nessuna persona da mostrarti"
              description="Quando ci saranno altre persone che hanno scelto di comparire, le troverai qui."
            />
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length > 0 ? (
          <>
            <p className="mt-8 text-sm font-extrabold text-ink" aria-live="polite">
              {loadedPage.totalElements === 1 ? "1 persona disponibile" : `${loadedPage.totalElements} persone disponibili`}
            </p>
            <ul className="mt-3 grid gap-4 md:grid-cols-2">
              {loadedPage.items.map((person, index) => {
                const visibleInterests = person.sharedInterests.slice(0, 4);
                const remainingInterests = Math.max(0, person.sharedInterestsCount - visibleInterests.length);
                const pendingAction = pendingLinkActions[person.userId];
                const pending = pendingAction !== undefined;
                const actionError = linkActionErrors[person.userId];
                return (
                  <li className="surface-card stagger-in flex flex-col p-5" style={staggerStyle(index)} key={person.userId} aria-busy={pending}>
                    <Link className="flex items-center gap-3.5 rounded-2xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-linkr-strong" to={`/people/${encodeURIComponent(person.userId)}`} aria-label={`Apri il profilo di ${person.displayName}`}>
                      <UserAvatar displayName={person.displayName} avatarUrl={person.avatarUrl} className="size-14 text-base" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-lg font-extrabold tracking-[-0.02em] text-ink transition-colors hover:text-linkr-strong">{person.displayName}</p>
                        <p className="truncate text-sm font-semibold text-muted">@{person.username}</p>
                      </div>
                    </Link>
                    <p className={`mt-5 text-xs font-extrabold uppercase tracking-[0.08em] ${person.sharedInterestsCount > 0 ? "text-linkr-strong" : "text-muted"}`}>
                      {sharedInterestsLabel(person.sharedInterestsCount)}
                    </p>
                    {visibleInterests.length > 0 ? (
                      <div className="mt-2.5 flex flex-wrap gap-1.5" role="group" aria-label={`Interessi condivisi con ${person.displayName}`}>
                        {visibleInterests.map((interest) => (
                          <span className="shared-chip" key={interest.id}>{interest.name}</span>
                        ))}
                        {remainingInterests > 0 ? <span className="shared-chip shared-chip--more">+{remainingInterests}</span> : null}
                      </div>
                    ) : null}

                    <div className="mt-auto pt-5">
                      {person.linkStatus === "NONE" && person.sharedInterestsCount === 0 ? (
                        <p className="text-xs font-semibold leading-5 text-muted">Puoi inviare un Link quando avete almeno un interesse in comune.</p>
                      ) : null}
                      {person.linkStatus === "NONE" && person.sharedInterestsCount > 0 ? (
                        <button className="button-primary inline-flex items-center gap-2" type="button" disabled={pending} onClick={() => void handleLinkAction(person, "send")}>
                          <UserPlus size={17} aria-hidden="true" /> {pendingAction === "send" ? "Invio…" : "Invia Link"}
                        </button>
                      ) : null}
                      {person.linkStatus === "OUTGOING_PENDING" ? (
                        <span className="status-pill"><Clock3 size={16} aria-hidden="true" /> Richiesta inviata</span>
                      ) : null}
                      {person.linkStatus === "INCOMING_PENDING" && person.linkRequestId ? (
                        <div className="flex flex-wrap gap-2" aria-label={`Rispondi alla richiesta Link di ${person.displayName}`}>
                          <button className="button-primary inline-flex items-center gap-2" type="button" disabled={pending} onClick={() => void handleLinkAction(person, "accept")}>
                            <Check size={17} aria-hidden="true" /> {pendingAction === "accept" ? "Accetto…" : "Accetta"}
                          </button>
                          <button className="button-secondary inline-flex items-center gap-2" type="button" disabled={pending} onClick={() => void handleLinkAction(person, "decline")}>
                            <X size={17} aria-hidden="true" /> {pendingAction === "decline" ? "Rifiuto…" : "Rifiuta"}
                          </button>
                        </div>
                      ) : null}
                      {person.linkStatus === "INCOMING_PENDING" && !person.linkRequestId ? (
                        <span className="status-pill">Richiesta ricevuta</span>
                      ) : null}
                      {person.linkStatus === "INCOMING_DECLINED" ? (
                        <span className="status-pill">Richiesta archiviata</span>
                      ) : null}
                      {person.linkStatus === "ACCEPTED" ? (
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="status-pill status-pill--success"><Link2 size={16} aria-hidden="true" /> Collegati</span>
                          {person.linkRequestId ? <WriteMessageLink conversationId={person.linkRequestId} /> : null}
                        </div>
                      ) : null}
                      {actionError ? <p className="mt-2 text-sm font-bold leading-5 text-danger" role="alert">{actionError}</p> : null}
                    </div>
                  </li>
                );
              })}
            </ul>

            <div className="mt-7 text-center">
              {loadMoreError ? <p className="form-message form-message--error mb-3" role="alert">{loadMoreError}</p> : null}
              {hasMore ? (
                <button className="button-secondary inline-flex min-w-52 items-center" type="button" disabled={loadingMore} onClick={loadMore}>
                  {loadingMore ? "Caricamento…" : loadMoreError ? "Riprova" : "Mostra altre persone"}
                </button>
              ) : <p className="text-sm font-semibold text-muted">Hai visto tutte le persone disponibili.</p>}
              <p className="mt-2 text-xs font-bold text-muted">Mostrate {loadedPage.items.length} di {loadedPage.totalElements}</p>
            </div>
          </>
        ) : null}
      </section>
    </div>
  );
}
