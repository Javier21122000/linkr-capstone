import axios from "axios";
import { ArrowLeft, CalendarDays, Check, Link2, RotateCcw, Send, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { WriteMessageLink } from "../components/chat/WriteMessageLink";
import { useAuth } from "../context/AuthContext";
import { useLinkRequestCount } from "../context/LinkRequestCountContext";
import {
  acceptLinkRequest,
  declineLinkRequest,
  getLinkRequestErrorMessage,
  getLinkRequests,
} from "../lib/linkRequests";
import type { LinkRequestDirectionFilter, LinkRequestResponse, LinkRequestStatus } from "../types/linkRequest";
import type { PageResponse } from "../types/people";

type LinksView = "incoming" | "outgoing" | "accepted";

type LinksState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly page: PageResponse<LinkRequestResponse> }
  | { readonly status: "error"; readonly message: string };

interface ViewQuery {
  readonly direction: LinkRequestDirectionFilter;
  readonly status?: LinkRequestStatus;
}

const VIEW_OPTIONS: readonly { readonly id: LinksView; readonly label: string }[] = [
  { id: "incoming", label: "Ricevute" },
  { id: "outgoing", label: "Inviate" },
  { id: "accepted", label: "Collegamenti" },
];

function queryForView(view: LinksView): ViewQuery {
  if (view === "incoming") return { direction: "incoming", status: "PENDING" };
  if (view === "outgoing") return { direction: "outgoing" };
  return { direction: "all", status: "ACCEPTED" };
}

function appendUniqueRequests(
  current: readonly LinkRequestResponse[],
  incoming: readonly LinkRequestResponse[],
): readonly LinkRequestResponse[] {
  const requestIds = new Set(current.map((request) => request.requestId));
  return [...current, ...incoming.filter((request) => !requestIds.has(request.requestId))];
}

function formatRequestDate(createdAt: string): string {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "Data non disponibile";
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "short", year: "numeric" }).format(date);
}

function requestStatusLabel(status: LinkRequestStatus): string {
  if (status === "ACCEPTED") return "Collegati";
  if (status === "DECLINED") return "Archiviata";
  return "In attesa";
}

function emptyStateCopy(view: LinksView): { readonly title: string; readonly description: string } {
  if (view === "incoming") {
    return { title: "Nessuna richiesta ricevuta.", description: "Le nuove richieste Link compariranno qui." };
  }
  if (view === "outgoing") {
    return { title: "Nessuna richiesta inviata.", description: "Puoi inviare un Link dalla discovery di un evento." };
  }
  return { title: "Nessun collegamento.", description: "Le richieste accettate diventeranno collegamenti." };
}

export function ProfileLinksPage() {
  const { authorizedRequest } = useAuth();
  const { refreshIncomingCount } = useLinkRequestCount();
  const [view, setView] = useState<LinksView>("incoming");
  const [pageNumber, setPageNumber] = useState(0);
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<LinksState>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const [pendingAnswers, setPendingAnswers] = useState<Readonly<Record<string, "accept" | "decline">>>({});
  const [answerErrors, setAnswerErrors] = useState<Readonly<Record<string, string>>>({});
  const [feedback, setFeedback] = useState<string | null>(null);
  const requestIdRef = useRef(0);
  const requestPendingRef = useRef(false);
  const answerLocksRef = useRef(new Set<string>());

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const controller = new AbortController();
    const query = queryForView(view);
    requestPendingRef.current = true;
    setLoadMoreError(null);
    if (pageNumber === 0) setState({ status: "loading" });
    else setLoadingMore(true);

    void authorizedRequest((accessToken) => getLinkRequests(accessToken, {
      ...query,
      page: pageNumber,
      signal: controller.signal,
    })).then((response) => {
      if (requestId !== requestIdRef.current) return;
      setState((current) => ({
        status: "success",
        page: {
          ...response,
          items: pageNumber > 0 && current.status === "success"
            ? appendUniqueRequests(current.page.items, response.items)
            : response.items,
        },
      }));
    }).catch((error: unknown) => {
      if (axios.isCancel(error) || requestId !== requestIdRef.current) return;
      if (pageNumber > 0) setLoadMoreError(getLinkRequestErrorMessage(error));
      else setState({ status: "error", message: getLinkRequestErrorMessage(error) });
    }).finally(() => {
      if (requestId === requestIdRef.current) {
        requestPendingRef.current = false;
        setLoadingMore(false);
      }
    });

    return () => controller.abort();
  }, [authorizedRequest, pageNumber, requestKey, view]);

  function changeView(nextView: LinksView) {
    if (nextView === view) return;
    requestIdRef.current += 1;
    requestPendingRef.current = false;
    setView(nextView);
    setPageNumber(0);
    setLoadMoreError(null);
    setAnswerErrors({});
    setFeedback(null);
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

  async function answerRequest(request: LinkRequestResponse, action: "accept" | "decline") {
    if (answerLocksRef.current.has(request.requestId)) return;
    answerLocksRef.current.add(request.requestId);
    setPendingAnswers((current) => ({ ...current, [request.requestId]: action }));
    setAnswerErrors((current) => {
      const next = { ...current };
      delete next[request.requestId];
      return next;
    });
    setFeedback(null);

    try {
      await authorizedRequest((accessToken) => action === "accept"
        ? acceptLinkRequest(accessToken, request.requestId)
        : declineLinkRequest(accessToken, request.requestId));
      void refreshIncomingCount(true);
      setState((current) => current.status === "success" ? {
        status: "success",
        page: {
          ...current.page,
          items: current.page.items.filter((item) => item.requestId !== request.requestId),
          totalElements: Math.max(0, current.page.totalElements - 1),
        },
      } : current);
      setFeedback(action === "accept" ? "Richiesta accettata." : "Richiesta archiviata.");
      setPageNumber(0);
      setRequestKey((current) => current + 1);
    } catch (error) {
      setAnswerErrors((current) => ({ ...current, [request.requestId]: getLinkRequestErrorMessage(error) }));
    } finally {
      answerLocksRef.current.delete(request.requestId);
      setPendingAnswers((current) => {
        const next = { ...current };
        delete next[request.requestId];
        return next;
      });
    }
  }

  const loadedPage = state.status === "success" ? state.page : null;
  const hasMore = loadedPage !== null && loadedPage.page + 1 < loadedPage.totalPages;
  const emptyCopy = emptyStateCopy(view);

  return (
    <div className="page-shell page-with-mobile-nav pt-8 sm:pt-12 lg:pt-16">
      <section className="mx-auto max-w-2xl" aria-labelledby="profile-links-title">
        <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to="/profile">
          <ArrowLeft size={19} aria-hidden="true" /> Torna al profilo
        </Link>

        <p className="section-kicker mt-8">La tua rete</p>
        <h1 id="profile-links-title" className="mt-2 text-4xl font-extrabold tracking-[-0.05em] text-ink sm:text-5xl">I miei Link</h1>
        <p className="mt-3 max-w-xl text-sm leading-6 text-muted">Gestisci le richieste nate dagli eventi e ritrova le persone con cui sei in contatto.</p>

        <div className="scrollbar-none mt-7 flex gap-2 overflow-x-auto pb-1" role="group" aria-label="Viste delle richieste Link">
          {VIEW_OPTIONS.map((option) => (
            <button
              className="filter-chip"
              type="button"
              aria-pressed={view === option.id}
              data-selected={view === option.id}
              key={option.id}
              onClick={() => changeView(option.id)}
            >
              {option.label}
            </button>
          ))}
        </div>

        {feedback ? <div className="form-message form-message--success mt-5" role="status">{feedback}</div> : null}

        {state.status === "loading" ? (
          <div className="mt-6 space-y-3" aria-busy="true" aria-label="Caricamento richieste Link">
            {Array.from({ length: 3 }, (_, index) => <div className="h-32 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" key={index} />)}
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="form-message form-message--error mt-6" role="alert">
            <p>{state.message}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={retry}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length === 0 ? (
          <div className="mt-6 rounded-2xl border border-line bg-surface p-6 text-center">
            {view === "outgoing" ? <Send className="mx-auto text-linkr-strong" size={28} aria-hidden="true" /> : <Link2 className="mx-auto text-linkr-strong" size={28} aria-hidden="true" />}
            <p className="mt-3 font-extrabold text-ink">{emptyCopy.title}</p>
            <p className="mt-2 text-sm leading-6 text-muted">{emptyCopy.description}</p>
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length > 0 ? (
          <>
            <ul className="mt-6 divide-y divide-line border-y border-line">
              {loadedPage.items.map((request) => {
                const pendingAction = pendingAnswers[request.requestId];
                const pending = pendingAction !== undefined;
                const actionError = answerErrors[request.requestId];
                return (
                  <li className="py-5" key={request.requestId} aria-busy={pending}>
                    <div className="flex gap-3">
                      <UserAvatar displayName={request.otherUser.displayName} avatarUrl={request.otherUser.avatarUrl} className="size-12 text-sm" />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate font-extrabold text-ink">{request.otherUser.displayName}</p>
                            <p className="truncate text-sm font-semibold text-muted">@{request.otherUser.username}</p>
                          </div>
                          <span className="rounded-full bg-linkr-soft px-2.5 py-1 text-xs font-extrabold text-linkr-strong">{requestStatusLabel(request.status)}</span>
                        </div>

                        {request.eventId && request.eventTitle ? (
                          <Link className="mt-3 inline-flex min-h-11 items-center gap-2 rounded-lg text-sm font-bold text-ink hover:text-linkr-strong" to={`/events/${request.eventId}`}>
                            <CalendarDays className="shrink-0 text-linkr-strong" size={16} aria-hidden="true" />
                            <span className="line-clamp-2">{request.eventTitle}</span>
                          </Link>
                        ) : null}
                        <p className="mt-1 text-xs font-bold text-muted">Richiesta del {formatRequestDate(request.createdAt)}</p>

                        {request.status === "ACCEPTED" ? (
                          <WriteMessageLink conversationId={request.requestId} className="button-primary mt-4" />
                        ) : null}

                        {view === "incoming" && request.direction === "INCOMING" && request.status === "PENDING" ? (
                          <div className="mt-4 flex flex-wrap gap-2" aria-label={`Rispondi alla richiesta Link di ${request.otherUser.displayName}`}>
                            <button className="button-primary inline-flex min-w-28 items-center gap-2" type="button" disabled={pending} onClick={() => void answerRequest(request, "accept")}>
                              <Check size={17} aria-hidden="true" /> {pendingAction === "accept" ? "Accetto…" : "Accetta"}
                            </button>
                            <button className="button-secondary inline-flex min-w-28 items-center gap-2" type="button" disabled={pending} onClick={() => void answerRequest(request, "decline")}>
                              <X size={17} aria-hidden="true" /> {pendingAction === "decline" ? "Rifiuto…" : "Rifiuta"}
                            </button>
                          </div>
                        ) : null}

                        {actionError ? <p className="mt-2 text-sm font-bold leading-5 text-danger" role="alert">{actionError}</p> : null}
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>

            <div className="mt-6 text-center">
              {loadMoreError ? <p className="form-message form-message--error mb-3" role="alert">{loadMoreError}</p> : null}
              {hasMore ? (
                <button className="button-secondary inline-flex min-w-52 items-center" type="button" disabled={loadingMore} onClick={loadMore}>
                  {loadingMore ? "Caricamento…" : loadMoreError ? "Riprova" : "Mostra altre richieste"}
                </button>
              ) : (
                <p className="text-sm font-semibold text-muted">Hai visto tutti gli elementi disponibili.</p>
              )}
              <p className="mt-2 text-xs font-bold text-muted">Mostrati {loadedPage.items.length} di {loadedPage.totalElements}</p>
            </div>
          </>
        ) : null}
      </section>
    </div>
  );
}
