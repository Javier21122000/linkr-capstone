import axios from "axios";
import { ArrowLeft, RotateCcw, Send } from "lucide-react";
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

function formatMessageTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("it-IT", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
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
  const { connectionStatus, subscribe } = useChatRealtime();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ThreadState>({ status: "loading" });
  const [messages, setMessages] = useState<readonly MessageResponse[]>([]);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const [sendUncertain, setSendUncertain] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const seenMessageIdsRef = useRef(new Set<string>());
  const pendingScrollRestoreRef = useRef<ScrollSnapshot | null>(null);
  const shouldScrollBottomRef = useRef(false);
  const pendingSendBodyRef = useRef<string | null>(null);

  const markRead = useCallback(() => authorizedRequest((accessToken) => (
    markConversationRead(accessToken, conversationId)
  )), [authorizedRequest, conversationId]);

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
    const nearBottom = !scroll || scroll.scrollHeight - scroll.scrollTop - scroll.clientHeight < 96;
    seenMessageIdsRef.current.add(message.messageId);
    shouldScrollBottomRef.current = message.mine || nearBottom;
    setMessages((current) => [...current, message]);

    if (message.mine && pendingSendBodyRef.current === message.body) {
      pendingSendBodyRef.current = null;
      setSendError(null);
      setDraft((current) => current === message.body ? "" : current);
    }
    if (!message.mine && document.visibilityState === "visible") void markRead().catch(() => undefined);
  }), [conversationId, markRead, subscribe]);

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
    if (sending || body.length === 0 || body.length > 2_000 || state.status !== "success") return;
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
    } catch (error) {
      if (pendingSendBodyRef.current === null) return;
      if (isConversationNotFound(error)) setState({ status: "not-found" });
      else {
        setSendUncertain(axios.isAxiosError(error) && !error.response);
        setSendError(getChatErrorMessage(error));
      }
    } finally {
      pendingSendBodyRef.current = null;
      setSending(false);
    }
  }

  async function retryMessageSafely() {
    if (!sendUncertain) {
      await submitMessage();
      return;
    }

    const body = draft.trim();
    if (!body || sending) return;
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
        return;
      }
    } catch (error) {
      setSendError(getChatErrorMessage(error));
      return;
    } finally {
      setSending(false);
    }
    setSendUncertain(false);
    await submitMessage();
  }

  function handleComposerKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== "Enter" || event.shiftKey || event.nativeEvent.isComposing) return;
    event.preventDefault();
    formRef.current?.requestSubmit();
  }

  if (state.status === "loading") {
    return (
      <div className="page-shell py-8" aria-busy="true" aria-label="Caricamento conversazione">
        <div className="mx-auto h-[70dvh] max-w-3xl animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />
      </div>
    );
  }

  if (state.status === "not-found") {
    return (
      <div className="page-shell page-with-mobile-nav py-12">
        <div className="mx-auto max-w-xl rounded-2xl border border-line bg-surface p-6 text-center" role="alert">
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
  return (
    <div className="page-shell page-with-mobile-nav py-5 sm:py-8">
      <section className="mx-auto flex max-w-3xl flex-col overflow-hidden rounded-2xl border border-line bg-surface" aria-labelledby="chat-person-title">
        <header className="flex items-center gap-3 border-b border-line p-4">
          <Link className="icon-button shrink-0" to="/chats" aria-label="Torna alle chat"><ArrowLeft size={19} aria-hidden="true" /></Link>
          <UserAvatar displayName={conversation.otherUser.displayName} avatarUrl={conversation.otherUser.avatarUrl} className="size-11 text-xs" />
          <div className="min-w-0">
            <h1 id="chat-person-title" className="truncate font-extrabold text-ink">{conversation.otherUser.displayName}</h1>
            <p className="truncate text-xs font-bold text-muted">@{conversation.otherUser.username}</p>
          </div>
        </header>

        {connectionStatus === "disconnected" ? <p className="border-b border-line px-4 py-2 text-center text-xs font-bold text-muted">Realtime non disponibile: invio e cronologia REST restano attivi.</p> : null}

        <div ref={scrollRef} className="h-[min(60dvh,38rem)] overflow-y-auto overscroll-contain bg-canvas/60 px-4 py-5" aria-live="polite">
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
            <ol className="space-y-3">
              {messages.map((message) => (
                <li className={`flex ${message.mine ? "justify-end" : "justify-start"}`} key={message.messageId}>
                  <div className={`max-w-[85%] rounded-2xl px-4 py-3 sm:max-w-[72%] ${message.mine ? "bg-linkr-strong text-white" : "border border-line bg-surface text-ink"}`}>
                    <p className="whitespace-pre-wrap break-words text-sm leading-6">{message.body}</p>
                    <p className={`mt-1 text-[0.68rem] font-bold ${message.mine ? "text-white/75" : "text-muted"}`}>{formatMessageTime(message.createdAt)}</p>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </div>

        <form ref={formRef} className="border-t border-line p-3 sm:p-4" onSubmit={(event) => void submitMessage(event)}>
          <label className="sr-only" htmlFor="chat-message">Messaggio</label>
          <div className="flex items-end gap-2">
            <textarea
              id="chat-message"
              className="form-input min-h-12 max-h-36 flex-1 resize-y py-3"
              placeholder="Scrivi un messaggio…"
              rows={1}
              maxLength={2_000}
              value={draft}
              disabled={sending}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={handleComposerKeyDown}
            />
            <button className="button-primary inline-flex size-12 shrink-0 items-center px-0" type="submit" disabled={sending || draft.trim().length === 0} aria-label="Invia messaggio">
              <Send size={18} aria-hidden="true" />
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
