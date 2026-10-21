import axios from "axios";
import { ArrowDown, ArrowLeft, LoaderCircle, RotateCcw, Send } from "lucide-react";
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { FormEvent, KeyboardEvent } from "react";
import { Link, useParams } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { useAuth } from "../context/AuthContext";
import { useChatRealtime } from "../context/ChatRealtimeContext";
import {
  findConversation,
  getChatErrorMessage,
  getMessages,
  isConversationNotFound,
  markConversationRead,
  sendMessage,
} from "../lib/chat";
import { isNearChatBottom, shouldPinNewMessage } from "../lib/chatLayout";
import { prefersReducedMotion } from "../lib/motion";
import type { ConversationResponse, MessageResponse } from "../types/chat";

type ThreadState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly conversation: ConversationResponse; readonly hasMore: boolean }
  | { readonly status: "not-found" }
  | { readonly status: "error"; readonly message: string };

interface ScrollSnapshot {
  readonly height: number;
  readonly top: number;
}

const dayFormatter = new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "long" });

/** Etichetta del giorno per i separatori della conversazione: "Oggi", "Ieri" o la data. Giorno di calendario del dispositivo, come l'ora dei messaggi. */
function formatDayLabel(value: string, now: Date): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1);
  if (date.toDateString() === now.toDateString()) return "Oggi";
  if (date.toDateString() === yesterday.toDateString()) return "Ieri";
  return dayFormatter.format(date);
}

function formatClock(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : new Intl.DateTimeFormat("it-IT", { hour: "2-digit", minute: "2-digit" }).format(date);
}

function uniqueMessages(messages: readonly MessageResponse[]): readonly MessageResponse[] {
  const ids = new Set<string>();
  return messages.filter((message) => {
    if (ids.has(message.messageId)) return false;
    ids.add(message.messageId);
    return true;
  });
}

export function ChatThreadPage() {
  const { conversationId = "" } = useParams();
  const { authorizedRequest } = useAuth();
  const { clearConversationUnread, connectionStatus, reconciliationRevision, subscribe } = useChatRealtime();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ThreadState>({ status: "loading" });
  const [messages, setMessages] = useState<readonly MessageResponse[]>([]);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [sendUncertain, setSendUncertain] = useState(false);
  const [atBottom, setAtBottom] = useState(true);
  const pageRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const composerRef = useRef<HTMLTextAreaElement>(null);
  const sendingRef = useRef(false);
  const restoreComposerFocusRef = useRef(false);
  const seenMessageIdsRef = useRef(new Set<string>());
  const pendingScrollRestoreRef = useRef<ScrollSnapshot | null>(null);
  const shouldScrollBottomRef = useRef(false);
  const pendingSendBodyRef = useRef<string | null>(null);
  const reconciliationRevisionRef = useRef(reconciliationRevision);
  const atBottomRef = useRef(true);

  // Su mobile `100dvh` non segue allo stesso modo tutte le tastiere virtuali. Il bordo inferiore realmente visibile
  // viene dal Visual Viewport e alimenta soltanto il calcolo CSS della chat; durante il pinch zoom non si forza nulla.
  useEffect(() => {
    const page = pageRef.current;
    const viewport = window.visualViewport;
    if (!page || !viewport) return undefined;

    const update = () => {
      if (viewport.scale > 1.01) {
        page.style.removeProperty("--chat-viewport-h");
        return;
      }
      page.style.setProperty("--chat-viewport-h", `${Math.round(viewport.height + viewport.offsetTop)}px`);
    };
    update();
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, [state.status]);

  // La textarea cresce con il testo fino al limite CSS. Non serve trascinarla e il pulsante invio resta sempre allineato.
  useLayoutEffect(() => {
    const textarea = composerRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 144)}px`;
  }, [draft, state.status]);

  // Se il composer cambia altezza mentre l'utente è al fondo, conserva l'ultimo messaggio visibile sopra di esso.
  useEffect(() => {
    const composer = formRef.current;
    if (!composer) return undefined;
    let frame = 0;
    const observer = new ResizeObserver(() => {
      if (!atBottomRef.current) return;
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => {
        const scroll = scrollRef.current;
        if (scroll) scroll.scrollTop = scroll.scrollHeight;
      });
    });
    observer.observe(composer);
    return () => {
      observer.disconnect();
      window.cancelAnimationFrame(frame);
    };
  }, [state.status]);

  const markRead = useCallback(async () => {
    await authorizedRequest((accessToken) => markConversationRead(accessToken, conversationId));
    clearConversationUnread(conversationId);
  }, [authorizedRequest, clearConversationUnread, conversationId]);

  useEffect(() => {
    const controller = new AbortController();
    seenMessageIdsRef.current.clear();
    setState({ status: "loading" });
    setMessages([]);
    setOlderError(null);
    setSendError(null);
    setSendUncertain(false);

    void Promise.all([
      authorizedRequest((accessToken) => findConversation(accessToken, conversationId, controller.signal)),
      authorizedRequest((accessToken) => getMessages(accessToken, conversationId, undefined, 30, controller.signal)),
    ]).then(([conversation, page]) => {
      if (!conversation) {
        setState({ status: "not-found" });
        return;
      }
      const chronological = [...page.items].reverse();
      chronological.forEach((message) => seenMessageIdsRef.current.add(message.messageId));
      shouldScrollBottomRef.current = true;
      setMessages((current) => uniqueMessages([...chronological, ...current]));
      setState({ status: "success", conversation, hasMore: page.hasMore });
      if (chronological.some((message) => !message.mine)) void markRead().catch(() => undefined);
    }).catch((error: unknown) => {
      if (axios.isCancel(error)) return;
      if (isConversationNotFound(error)) setState({ status: "not-found" });
      else setState({ status: "error", message: getChatErrorMessage(error) });
    });

    return () => controller.abort();
  }, [authorizedRequest, conversationId, markRead, requestKey]);

  useLayoutEffect(() => {
    const element = scrollRef.current;
    if (!element) return;
    const snapshot = pendingScrollRestoreRef.current;
    if (snapshot) {
      element.scrollTop = snapshot.top + (element.scrollHeight - snapshot.height);
      pendingScrollRestoreRef.current = null;
      return;
    }
    if (shouldScrollBottomRef.current) {
      element.scrollTop = element.scrollHeight;
      shouldScrollBottomRef.current = false;
    }
  }, [messages]);

  useEffect(() => subscribe((message) => {
    if (message.conversationId !== conversationId || seenMessageIdsRef.current.has(message.messageId)) return;
    const scroll = scrollRef.current;
    const nearBottom = !scroll || isNearChatBottom(scroll);
    seenMessageIdsRef.current.add(message.messageId);
    shouldScrollBottomRef.current = shouldPinNewMessage(message.mine, nearBottom);
    setMessages((current) => [...current, message]);

    if (message.mine && pendingSendBodyRef.current === message.body) {
      pendingSendBodyRef.current = null;
      setSendError(null);
      setDraft((current) => current === message.body ? "" : current);
    }
    if (!message.mine && document.visibilityState === "visible") void markRead().catch(() => undefined);
  }), [conversationId, markRead, subscribe]);

  useEffect(() => {
    if (reconciliationRevisionRef.current === reconciliationRevision) return;
    reconciliationRevisionRef.current = reconciliationRevision;
    setRequestKey((current) => current + 1);
  }, [reconciliationRevision]);

  useEffect(() => {
    function readWhenVisible() {
      if (document.visibilityState === "visible" && state.status === "success") void markRead().catch(() => undefined);
    }
    document.addEventListener("visibilitychange", readWhenVisible);
    window.addEventListener("focus", readWhenVisible);
    return () => {
      document.removeEventListener("visibilitychange", readWhenVisible);
      window.removeEventListener("focus", readWhenVisible);
    };
  }, [markRead, state.status]);

  async function loadOlderMessages() {
    if (loadingOlder || state.status !== "success" || !state.hasMore || messages.length === 0) return;
    const oldest = messages[0];
    const scroll = scrollRef.current;
    if (!oldest || !scroll) return;
    setLoadingOlder(true);
    setOlderError(null);
    try {
      const page = await authorizedRequest((accessToken) => getMessages(accessToken, conversationId, oldest.messageId, 30));
      const older = [...page.items].reverse().filter((message) => !seenMessageIdsRef.current.has(message.messageId));
      older.forEach((message) => seenMessageIdsRef.current.add(message.messageId));
      pendingScrollRestoreRef.current = { height: scroll.scrollHeight, top: scroll.scrollTop };
      setMessages((current) => uniqueMessages([...older, ...current]));
      setState((current) => current.status === "success" ? { ...current, hasMore: page.hasMore } : current);
    } catch (error) {
      if (isConversationNotFound(error)) setState({ status: "not-found" });
      else setOlderError(getChatErrorMessage(error));
    } finally {
      setLoadingOlder(false);
    }
  }

  async function submitMessage(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    const body = draft.trim();
    if (sendingRef.current || body.length === 0 || body.length > 2_000 || state.status !== "success") return;
    restoreComposerFocusRef.current = document.activeElement === composerRef.current || !window.matchMedia("(pointer: coarse)").matches;
    sendingRef.current = true;
    setSending(true);
    setSendError(null);
    setSendUncertain(false);
    pendingSendBodyRef.current = body;
    try {
      const message = await authorizedRequest((accessToken) => sendMessage(accessToken, conversationId, { body }));
      if (!seenMessageIdsRef.current.has(message.messageId)) {
        seenMessageIdsRef.current.add(message.messageId);
        shouldScrollBottomRef.current = true;
        setMessages((current) => [...current, message]);
      }
      pendingSendBodyRef.current = null;
      setDraft("");
      if (restoreComposerFocusRef.current) window.requestAnimationFrame(() => composerRef.current?.focus({ preventScroll: true }));
    } catch (error) {
      if (pendingSendBodyRef.current === null) return;
      if (isConversationNotFound(error)) setState({ status: "not-found" });
      else {
        setSendUncertain(axios.isAxiosError(error) && !error.response);
        setSendError(getChatErrorMessage(error));
      }
    } finally {
      pendingSendBodyRef.current = null;
      sendingRef.current = false;
      setSending(false);
    }
  }

  async function retryMessageSafely() {
    if (!sendUncertain) {
      await submitMessage();
      return;
    }

    const body = draft.trim();
    if (!body || sendingRef.current) return;
    sendingRef.current = true;
    setSending(true);
    setSendError(null);
    try {
      const page = await authorizedRequest((accessToken) => getMessages(accessToken, conversationId));
      const confirmed = page.items.find((message) => (
        message.mine && message.body === body && !seenMessageIdsRef.current.has(message.messageId)
      ));
      if (confirmed) {
        page.items.slice().reverse().forEach((message) => {
          if (seenMessageIdsRef.current.has(message.messageId)) return;
          seenMessageIdsRef.current.add(message.messageId);
          setMessages((current) => [...current, message]);
        });
        shouldScrollBottomRef.current = true;
        setDraft("");
        setSendUncertain(false);
        if (!window.matchMedia("(pointer: coarse)").matches) window.requestAnimationFrame(() => composerRef.current?.focus({ preventScroll: true }));
        return;
      }
    } catch (error) {
      setSendError(getChatErrorMessage(error));
      return;
    } finally {
      sendingRef.current = false;
      setSending(false);
    }
    setSendUncertain(false);
    await submitMessage();
  }

  function handleMessagesScroll() {
    const element = scrollRef.current;
    if (!element) return;
    const nextAtBottom = isNearChatBottom(element, 160);
    atBottomRef.current = nextAtBottom;
    setAtBottom((current) => (current === nextAtBottom ? current : nextAtBottom));
  }

  function jumpToLatest() {
    const element = scrollRef.current;
    if (!element) return;
    element.scrollTo({ top: element.scrollHeight, behavior: prefersReducedMotion() ? "auto" : "smooth" });
    atBottomRef.current = true;
    setAtBottom(true);
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    formRef.current?.requestSubmit();
  }

  if (state.status === "loading") {
    return (
      <div className="page-shell py-8" aria-busy="true" aria-label="Caricamento conversazione">
        <div className="mx-auto h-[70dvh] max-w-3xl animate-pulse rounded-[1.5rem] bg-white/70 ring-1 ring-black/5 motion-reduce:animate-none" />
      </div>
    );
  }

  if (state.status === "not-found") {
    return (
      <div className="page-shell page-with-mobile-nav py-12">
        <div className="surface-card mx-auto max-w-xl p-6 text-center" role="alert">
          <h1 className="text-2xl font-extrabold text-ink">Conversazione non disponibile</h1>
          <p className="mt-2 text-sm leading-6 text-muted">La chat non esiste oppure non puoi accedervi.</p>
          <Link className="button-secondary mt-5 inline-flex items-center gap-2" to="/chats"><ArrowLeft size={17} aria-hidden="true" /> Torna alle chat</Link>
        </div>
      </div>
    );
  }

  if (state.status === "error") {
    return (
      <div className="page-shell page-with-mobile-nav py-12">
        <div className="form-message form-message--error mx-auto max-w-xl" role="alert">
          <p>{state.message}</p>
          <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
            <RotateCcw size={17} aria-hidden="true" /> Riprova
          </button>
        </div>
      </div>
    );
  }

  const { conversation, hasMore } = state;
  const now = new Date();
  // Un messaggio mio già letto mostra "Letto" solo sull'ultimo: è l'unica informazione utile e non rumorosa.
  const lastMineId = [...messages].reverse().find((message) => message.mine)?.messageId;
  return (
    <div ref={pageRef} className="page-shell page-with-mobile-nav chat-thread-page py-5 sm:py-8">
      <section className="chat-thread surface-card mx-auto flex max-w-3xl flex-col overflow-hidden" aria-labelledby="chat-person-title">
        <header className="chat-thread__header flex items-center gap-3 p-3.5 sm:p-4">
          <Link className="icon-button shrink-0" to="/chats" aria-label="Torna alle chat"><ArrowLeft size={19} aria-hidden="true" /></Link>
          <UserAvatar displayName={conversation.otherUser.displayName} avatarUrl={conversation.otherUser.avatarUrl} className="size-11 text-xs" />
          <div className="min-w-0">
            <h1 id="chat-person-title" className="truncate font-extrabold text-ink">{conversation.otherUser.displayName}</h1>
            <p className="truncate text-xs font-bold text-muted">@{conversation.otherUser.username}</p>
          </div>
        </header>

        {connectionStatus === "disconnected" ? <p className="chat-thread__notice px-4 py-2 text-center text-xs font-bold text-muted">Realtime non disponibile: invio e cronologia REST restano attivi.</p> : null}

        <div className="chat-thread__viewport relative min-h-0 flex-1 overflow-hidden">
          <div ref={scrollRef} className="chat-thread__messages h-[min(60dvh,38rem)] overflow-y-auto overscroll-contain px-4 py-5" role="log" aria-label="Messaggi della conversazione" aria-live="polite" tabIndex={0} onScroll={handleMessagesScroll}>
            <div className="mb-5 text-center">
              {olderError ? <p className="form-message form-message--error mb-3" role="alert">{olderError}</p> : null}
              {hasMore ? (
                <button className="button-secondary inline-flex min-w-48 items-center" type="button" disabled={loadingOlder} onClick={() => void loadOlderMessages()}>
                  {loadingOlder ? "Caricamento…" : olderError ? "Riprova" : "Carica messaggi precedenti"}
                </button>
              ) : messages.length > 0 ? <p className="text-xs font-bold text-muted">Inizio della conversazione</p> : null}
            </div>

            {messages.length === 0 ? (
              <div className="grid h-full place-items-center text-center" role="status">
                <div>
                  <p className="font-extrabold text-ink">Inizia la conversazione</p>
                  <p className="mt-1 text-sm text-muted">Scrivi un messaggio a {conversation.otherUser.displayName}.</p>
                </div>
              </div>
            ) : (
              <ol className="space-y-1.5">
                {messages.map((message, index) => {
                  const previous = messages[index - 1];
                  const next = messages[index + 1];
                  const newDay = !previous || new Date(previous.createdAt).toDateString() !== new Date(message.createdAt).toDateString();
                  const startsGroup = !previous || previous.mine !== message.mine || newDay;
                  const endsGroup = !next || next.mine !== message.mine || new Date(next.createdAt).toDateString() !== new Date(message.createdAt).toDateString();
                  return (
                    <li key={message.messageId} className={`chat-message${startsGroup && index > 0 ? " pt-2" : ""}`} data-group-start={startsGroup || undefined} data-group-end={endsGroup || undefined}>
                      {newDay ? (
                        <p className="chat-day-label" role="separator">{formatDayLabel(message.createdAt, now)}</p>
                      ) : null}
                      <div className={`flex ${message.mine ? "justify-end" : "justify-start"}`}>
                        <div className="chat-bubble" data-mine={message.mine || undefined}>
                          <p className="whitespace-pre-wrap break-words text-[0.9375rem] leading-6">{message.body}</p>
                          {endsGroup ? <p className="chat-bubble__time">{formatClock(message.createdAt)}</p> : null}
                        </div>
                      </div>
                      {message.mine && message.messageId === lastMineId && message.readAt ? (
                        <p className="mt-1 text-right text-[0.68rem] font-bold text-muted">Letto</p>
                      ) : null}
                    </li>
                  );
                })}
              </ol>
            )}
          </div>

          {!atBottom && messages.length > 0 ? (
            <div className="chat-thread__jump-layer">
              <button className="chat-thread__jump glass glass--strong content-reveal" type="button" onClick={jumpToLatest}>
                <ArrowDown size={16} aria-hidden="true" /> Ultimi messaggi
              </button>
            </div>
          ) : null}
        </div>

        <form ref={formRef} className="chat-thread__composer p-3 sm:p-4" onSubmit={(event) => void submitMessage(event)}>
          <label className="sr-only" htmlFor="chat-message">Messaggio</label>
          <div className="flex items-end gap-2">
            <textarea
              ref={composerRef}
              id="chat-message"
              className="form-input min-h-12 max-h-36 min-w-0 flex-1 resize-none overflow-y-auto py-3"
              placeholder="Scrivi un messaggio…"
              rows={1}
              maxLength={2_000}
              value={draft}
              readOnly={sending}
              aria-busy={sending}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={handleComposerKeyDown}
            />
            <button className="button-primary inline-flex size-12 shrink-0 items-center px-0" type="submit" disabled={sending || draft.trim().length === 0} aria-label={sending ? "Invio in corso" : "Invia messaggio"} aria-busy={sending}>
              {sending ? <LoaderCircle className="animate-spin motion-reduce:animate-none" size={18} aria-hidden="true" /> : <Send size={18} aria-hidden="true" />}
            </button>
          </div>
          <div className="mt-2 flex items-start justify-between gap-3">
            <p className="text-xs font-semibold text-muted">Invio con Enter · nuova riga con Shift+Enter</p>
            <p className="shrink-0 text-xs font-bold text-muted">{draft.length}/2000</p>
          </div>
          {sending ? <p className="mt-2 text-sm font-bold text-muted" role="status">Invio in corso…</p> : null}
          {sendError ? (
            <div className="mt-2" role="alert">
              <p className="text-sm font-bold text-danger">{sendError}</p>
              <button className="mt-2 min-h-11 font-extrabold text-ink" type="button" disabled={sending} onClick={() => void retryMessageSafely()}>
                {sendUncertain ? "Verifica e riprova" : "Riprova invio"}
              </button>
            </div>
          ) : null}
        </form>
      </section>
    </div>
  );
}
