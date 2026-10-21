import axios from "axios";
import { MessageCircle, RotateCcw } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { useAuth } from "../context/AuthContext";
import { useChatRealtime } from "../context/ChatRealtimeContext";
import { getChatErrorMessage, getConversations } from "../lib/chat";
import type { ConversationResponse } from "../types/chat";
import type { PageResponse } from "../types/people";

type ConversationsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly page: PageResponse<ConversationResponse> }
  | { readonly status: "error"; readonly message: string };

function appendUniqueConversations(
  current: readonly ConversationResponse[],
  incoming: readonly ConversationResponse[],
): readonly ConversationResponse[] {
  const ids = new Set(current.map((conversation) => conversation.conversationId));
  return [...current, ...incoming.filter((conversation) => !ids.has(conversation.conversationId))];
}

function formatConversationTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const sameDay = date.toDateString() === now.toDateString();
  return new Intl.DateTimeFormat("it-IT", sameDay
    ? { hour: "2-digit", minute: "2-digit" }
    : { day: "2-digit", month: "short" }).format(date);
}

export function ChatsPage() {
  const { authorizedRequest } = useAuth();
  const { connectionStatus, conversationActivity, reconciliationRevision, subscribe } = useChatRealtime();
  const [pageNumber, setPageNumber] = useState(0);
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ConversationsState>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState<string | null>(null);
  const requestIdRef = useRef(0);
  const requestPendingRef = useRef(false);
  const conversationIdsRef = useRef(new Set<string>());
  const seenMessageIdsRef = useRef(new Set<string>());
  const reconciliationRevisionRef = useRef(reconciliationRevision);

  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const controller = new AbortController();
    requestPendingRef.current = true;
    setLoadMoreError(null);
    if (pageNumber === 0) setState({ status: "loading" });
    else setLoadingMore(true);

    void authorizedRequest((accessToken) => getConversations(accessToken, pageNumber, 20, controller.signal))
      .then((response) => {
        if (requestId !== requestIdRef.current) return;
        response.items.forEach((conversation) => {
          conversationIdsRef.current.add(conversation.conversationId);
          if (conversation.lastMessage) seenMessageIdsRef.current.add(conversation.lastMessage.messageId);
        });
        setState((current) => ({
          status: "success",
          page: {
            ...response,
            items: pageNumber > 0 && current.status === "success"
              ? appendUniqueConversations(current.page.items, response.items)
              : response.items,
          },
        }));
      })
      .catch((error: unknown) => {
        if (axios.isCancel(error) || requestId !== requestIdRef.current) return;
        if (pageNumber > 0) setLoadMoreError(getChatErrorMessage(error));
        else setState({ status: "error", message: getChatErrorMessage(error) });
      })
      .finally(() => {
        if (requestId === requestIdRef.current) {
          requestPendingRef.current = false;
          setLoadingMore(false);
        }
      });

    return () => controller.abort();
  }, [authorizedRequest, pageNumber, requestKey]);

  useEffect(() => subscribe((message) => {
    if (seenMessageIdsRef.current.has(message.messageId)) return;
    seenMessageIdsRef.current.add(message.messageId);
    if (!conversationIdsRef.current.has(message.conversationId)) {
      setPageNumber(0);
      setRequestKey((current) => current + 1);
      return;
    }
    setState((current) => {
      if (current.status !== "success") return current;
      const existing = current.page.items.find((item) => item.conversationId === message.conversationId);
      if (!existing) return current;
      const updated: ConversationResponse = {
        ...existing,
        lastMessage: message,
        unreadCount: message.mine ? existing.unreadCount : existing.unreadCount + 1,
      };
      return {
        status: "success",
        page: {
          ...current.page,
          items: [updated, ...current.page.items.filter((item) => item.conversationId !== message.conversationId)],
        },
      };
    });
  }), [subscribe]);

  useEffect(() => {
    if (!conversationActivity) return;
    setState((current) => {
      if (current.status !== "success") return current;
      const items = current.page.items.map((conversation) => {
        const activity = conversationActivity[conversation.conversationId];
        if (!activity) return conversation;
        return {
          ...conversation,
          lastMessage: activity.lastMessage ?? conversation.lastMessage,
          unreadCount: activity.unreadCount,
        };
      }).sort((left, right) => {
        const leftTime = Date.parse(left.lastMessage?.createdAt ?? left.linkedAt);
        const rightTime = Date.parse(right.lastMessage?.createdAt ?? right.linkedAt);
        return rightTime - leftTime;
      });
      return { status: "success", page: { ...current.page, items } };
    });
  }, [conversationActivity]);

  useEffect(() => {
    if (reconciliationRevisionRef.current === reconciliationRevision) return;
    reconciliationRevisionRef.current = reconciliationRevision;
    requestIdRef.current += 1;
    requestPendingRef.current = false;
    conversationIdsRef.current.clear();
    setPageNumber(0);
    setRequestKey((current) => current + 1);
  }, [reconciliationRevision]);

  function retry() {
    requestIdRef.current += 1;
    requestPendingRef.current = false;
    conversationIdsRef.current.clear();
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

  const loadedPage = state.status === "success" ? state.page : null;
  const hasMore = loadedPage !== null && loadedPage.page + 1 < loadedPage.totalPages;

  return (
    <div className="page-shell page-with-mobile-nav py-8 sm:py-12 lg:py-16">
      <section className="mx-auto max-w-2xl" aria-labelledby="chats-title">
        <p className="section-kicker">Messaggi privati</p>
        <h1 id="chats-title" className="mt-2 text-4xl font-extrabold tracking-[-0.05em] text-ink sm:text-5xl">Chat</h1>
        <p className="mt-3 text-sm leading-6 text-muted">Le conversazioni sono disponibili solo con i tuoi Link accettati.</p>

        {connectionStatus === "disconnected" ? (
          <p className="form-message mt-5" role="status">Gli aggiornamenti in tempo reale non sono disponibili. Puoi continuare a usare la chat.</p>
        ) : null}

        {state.status === "loading" ? (
          <div className="mt-7 space-y-3" aria-busy="true" aria-label="Caricamento conversazioni">
            {Array.from({ length: 4 }, (_, index) => <div className="h-24 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" key={index} />)}
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="form-message form-message--error mt-7" role="alert">
            <p>{state.message}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={retry}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length === 0 ? (
          <div className="mt-7 rounded-2xl border border-line bg-surface p-6 text-center" role="status">
            <MessageCircle className="mx-auto text-linkr-strong" size={30} aria-hidden="true" />
            <h2 className="mt-3 font-extrabold text-ink">Nessuna conversazione</h2>
            <p className="mt-2 text-sm leading-6 text-muted">Quando accetterai un Link, potrai scrivere da qui.</p>
          </div>
        ) : null}

        {loadedPage && loadedPage.items.length > 0 ? (
          <>
            <ul className="mt-7 divide-y divide-line border-y border-line">
              {loadedPage.items.map((conversation) => {
                const activityAt = conversation.lastMessage?.createdAt ?? conversation.linkedAt;
                return (
                  <li key={conversation.conversationId}>
                    <Link className="flex min-h-24 items-center gap-3 py-4" to={`/chats/${conversation.conversationId}`}>
                      <UserAvatar displayName={conversation.otherUser.displayName} avatarUrl={conversation.otherUser.avatarUrl} className="size-13 text-sm" />
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center justify-between gap-3">
                          <span className="truncate font-extrabold text-ink">{conversation.otherUser.displayName}</span>
                          <span className="shrink-0 text-xs font-bold text-muted">{formatConversationTime(activityAt)}</span>
                        </span>
                        <span className="mt-1 flex items-center gap-3">
                          <span className={`min-w-0 flex-1 truncate text-sm ${conversation.unreadCount > 0 ? "font-extrabold text-ink" : "font-semibold text-muted"}`}>
                            {conversation.lastMessage
                              ? `${conversation.lastMessage.mine ? "Tu: " : ""}${conversation.lastMessage.body}`
                              : "Inizia la conversazione"}
                          </span>
                          {conversation.unreadCount > 0 ? (
                            <span className="grid min-h-6 min-w-6 shrink-0 place-items-center rounded-full bg-linkr-action px-1.5 text-xs font-extrabold text-white" aria-label={`${conversation.unreadCount} messaggi non letti`}>
                              {conversation.unreadCount > 99 ? "99+" : conversation.unreadCount}
                            </span>
                          ) : null}
                        </span>
                      </span>
                    </Link>
                  </li>
                );
              })}
            </ul>

            <div className="mt-6 text-center">
              {loadMoreError ? <p className="form-message form-message--error mb-3" role="alert">{loadMoreError}</p> : null}
              {hasMore ? (
                <button className="button-secondary inline-flex min-w-52 items-center" type="button" disabled={loadingMore} onClick={loadMore}>
                  {loadingMore ? "Caricamento…" : loadMoreError ? "Riprova" : "Mostra altre chat"}
                </button>
              ) : <p className="text-sm font-semibold text-muted">Hai visto tutte le conversazioni.</p>}
              <p className="mt-2 text-xs font-bold text-muted">Mostrate {loadedPage.items.length} di {loadedPage.totalElements}</p>
            </div>
          </>
        ) : null}
      </section>
    </div>
  );
}
