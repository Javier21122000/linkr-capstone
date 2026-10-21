import { Client } from "@stomp/stompjs";
import type { IMessage } from "@stomp/stompjs";
import axios from "axios";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

import { getConversations } from "../lib/chat";
import type { MessageResponse } from "../types/chat";
import { useAuth } from "./AuthContext";

type ConnectionStatus = "disabled" | "connecting" | "connected" | "disconnected";
type MessageListener = (message: MessageResponse) => void;

interface ConversationActivity {
  readonly lastMessage: MessageResponse | null;
  readonly unreadCount: number;
}

type ConversationActivityById = Readonly<Record<string, ConversationActivity>>;

interface ChatRealtimeContextValue {
  readonly connectionStatus: ConnectionStatus;
  readonly conversationActivity: ConversationActivityById | null;
  readonly reconciliationRevision: number;
  readonly unreadCount: number | null;
  subscribe(listener: MessageListener): () => void;
  clearConversationUnread(conversationId: string): void;
  refreshUnreadCount(force?: boolean): Promise<void>;
}

interface ActiveUnreadRequest {
  readonly controller: AbortController;
  readonly promise: Promise<void>;
  readonly requestId: number;
}

const ChatRealtimeContext = createContext<ChatRealtimeContextValue | null>(null);

function websocketUrl(): string {
  const url = new URL("/ws", window.location.href);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return url.toString();
}

function isMessageResponse(value: unknown): value is MessageResponse {
  if (typeof value !== "object" || value === null) return false;
  const message = value as Partial<MessageResponse>;
  return typeof message.messageId === "string"
    && typeof message.conversationId === "string"
    && typeof message.senderId === "string"
    && typeof message.mine === "boolean"
    && typeof message.body === "string"
    && typeof message.createdAt === "string"
    && (typeof message.readAt === "string" || message.readAt === null);
}

export function ChatRealtimeProvider({ children }: { readonly children: ReactNode }) {
  const { accessToken, authorizedRequest } = useAuth();
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>("disabled");
  const [reconciliationRevision, setReconciliationRevision] = useState(0);
  const [conversationActivity, setConversationActivity] = useState<ConversationActivityById | null>(null);
  const listenersRef = useRef(new Set<MessageListener>());
  const seenMessageIdsRef = useRef(new Set<string>());
  const latestMessageAtRef = useRef<Readonly<Record<string, number>>>({});
  const clientRef = useRef<Client | null>(null);
  const deactivationRef = useRef<Promise<void>>(Promise.resolve());
  const generationRef = useRef(0);
  const activeUnreadRequestRef = useRef<ActiveUnreadRequest | null>(null);
  const unreadRequestIdRef = useRef(0);
  const unreadRevisionRef = useRef(0);
  const reconcileAfterRequestRef = useRef(false);
  const lastUnreadRefreshRef = useRef(0);
  const hasUnreadSnapshotRef = useRef(false);
  const refreshUnreadRef = useRef<(force?: boolean) => Promise<void>>(async () => undefined);

  const subscribe = useCallback((listener: MessageListener) => {
    listenersRef.current.add(listener);
    return () => listenersRef.current.delete(listener);
  }, []);

  const refreshUnreadCount = useCallback((force = false): Promise<void> => {
    if (!accessToken) {
      setConversationActivity(null);
      return Promise.resolve();
    }

    const active = activeUnreadRequestRef.current;
    if (!force && active) return active.promise;
    const now = Date.now();
    if (!force && now - lastUnreadRefreshRef.current < 1_200) return Promise.resolve();

    active?.controller.abort();
    const controller = new AbortController();
    const requestId = ++unreadRequestIdRef.current;
    const revision = unreadRevisionRef.current;
    lastUnreadRefreshRef.current = now;

    const promise = authorizedRequest(async (token) => {
      const activity: Record<string, ConversationActivity> = {};
      const lastMessages: MessageResponse[] = [];
      let page = 0;
      while (true) {
        const response = await getConversations(token, page, 50, controller.signal);
        response.items.forEach((conversation) => {
          activity[conversation.conversationId] = {
            lastMessage: conversation.lastMessage,
            unreadCount: conversation.unreadCount,
          };
          if (conversation.lastMessage) lastMessages.push(conversation.lastMessage);
        });
        page += 1;
        if (page >= response.totalPages) break;
      }
      return { activity, lastMessages };
    }).then(({ activity, lastMessages }) => {
      if (requestId !== unreadRequestIdRef.current) return;
      const missedMessage = hasUnreadSnapshotRef.current
        && lastMessages.some((message) => !seenMessageIdsRef.current.has(message.messageId));
      lastMessages.forEach((message) => seenMessageIdsRef.current.add(message.messageId));
      latestMessageAtRef.current = lastMessages.reduce<Readonly<Record<string, number>>>((latest, message) => {
        const messageAt = Date.parse(message.createdAt);
        const currentAt = latest[message.conversationId];
        return Number.isFinite(currentAt) && currentAt > messageAt
          ? latest
          : { ...latest, [message.conversationId]: messageAt };
      }, latestMessageAtRef.current);
      if (revision === unreadRevisionRef.current) {
        setConversationActivity(activity);
        hasUnreadSnapshotRef.current = true;
        if (missedMessage) setReconciliationRevision((current) => current + 1);
      }
      else reconcileAfterRequestRef.current = true;
    }).catch((error: unknown) => {
      if (!axios.isCancel(error) && requestId === unreadRequestIdRef.current) {
        // Mantiene l'ultimo conteggio noto: STOMP e la chat REST restano utilizzabili.
      }
    }).finally(() => {
      if (requestId !== unreadRequestIdRef.current) return;
      activeUnreadRequestRef.current = null;
      if (reconcileAfterRequestRef.current) {
        reconcileAfterRequestRef.current = false;
        void refreshUnreadRef.current(true);
      }
    });

    activeUnreadRequestRef.current = { controller, promise, requestId };
    return promise;
  }, [accessToken, authorizedRequest]);

  useEffect(() => {
    refreshUnreadRef.current = refreshUnreadCount;
  }, [refreshUnreadCount]);

  const clearConversationUnread = useCallback((conversationId: string) => {
    unreadRevisionRef.current += 1;
    if (activeUnreadRequestRef.current) reconcileAfterRequestRef.current = true;
    setConversationActivity((current) => current ? {
      ...current,
      [conversationId]: {
        lastMessage: current[conversationId]?.lastMessage ?? null,
        unreadCount: 0,
      },
    } : current);
  }, []);

  useEffect(() => {
    unreadRequestIdRef.current += 1;
    activeUnreadRequestRef.current?.controller.abort();
    activeUnreadRequestRef.current = null;
    reconcileAfterRequestRef.current = false;
    lastUnreadRefreshRef.current = 0;
    unreadRevisionRef.current = 0;
    hasUnreadSnapshotRef.current = false;
    seenMessageIdsRef.current.clear();
    latestMessageAtRef.current = {};
    setConversationActivity(null);
    setReconciliationRevision(0);
    if (accessToken) void refreshUnreadCount(true);
  }, [accessToken, refreshUnreadCount]);

  useEffect(() => {
    function refreshWhenVisible() {
      if (document.visibilityState === "visible") void refreshUnreadCount();
    }
    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [refreshUnreadCount]);

  useEffect(() => {
    if (!accessToken || connectionStatus !== "disconnected") return undefined;
    void refreshUnreadCount(true);
    const intervalId = window.setInterval(() => {
      if (document.visibilityState === "visible") void refreshUnreadCount(true);
    }, 60_000);
    return () => window.clearInterval(intervalId);
  }, [accessToken, connectionStatus, refreshUnreadCount]);

  useEffect(() => {
    const generation = ++generationRef.current;
    let disposed = false;
    if (!accessToken) {
      setConnectionStatus("disabled");
      return undefined;
    }

    setConnectionStatus("connecting");
    void deactivationRef.current.then(() => {
      if (disposed || generation !== generationRef.current) return;
      const client = new Client({
        brokerURL: websocketUrl(),
        connectHeaders: { Authorization: `Bearer ${accessToken}` },
        reconnectDelay: 5_000,
        heartbeatIncoming: 10_000,
        heartbeatOutgoing: 10_000,
        connectionTimeout: 10_000,
      });
      clientRef.current = client;

      client.onConnect = () => {
        if (disposed || generation !== generationRef.current) return;
        setConnectionStatus("connected");
        client.subscribe("/user/queue/messages", (frame: IMessage) => {
          try {
            const message: unknown = JSON.parse(frame.body);
            if (!isMessageResponse(message) || seenMessageIdsRef.current.has(message.messageId)) return;
            seenMessageIdsRef.current.add(message.messageId);
            const messageAt = Date.parse(message.createdAt);
            const snapshotAt = latestMessageAtRef.current[message.conversationId];
            if (Number.isFinite(snapshotAt) && Number.isFinite(messageAt) && messageAt <= snapshotAt) return;
            latestMessageAtRef.current = { ...latestMessageAtRef.current, [message.conversationId]: messageAt };
            unreadRevisionRef.current += 1;
            if (activeUnreadRequestRef.current) reconcileAfterRequestRef.current = true;
            if (!message.mine) {
              setConversationActivity((current) => ({
                ...(current ?? {}),
                [message.conversationId]: {
                  lastMessage: message,
                  unreadCount: (current?.[message.conversationId]?.unreadCount ?? 0) + 1,
                },
              }));
            } else {
              setConversationActivity((current) => ({
                ...(current ?? {}),
                [message.conversationId]: {
                  lastMessage: message,
                  unreadCount: current?.[message.conversationId]?.unreadCount ?? 0,
                },
              }));
            }
            listenersRef.current.forEach((listener) => listener(message));
          } catch {
            // Un frame non valido viene ignorato: il percorso REST resta operativo.
          }
        });
        void refreshUnreadCount();
      };
      client.onWebSocketClose = () => {
        if (!disposed && generation === generationRef.current) setConnectionStatus("disconnected");
      };
      client.onWebSocketError = () => {
        if (!disposed && generation === generationRef.current) setConnectionStatus("disconnected");
      };
      client.onStompError = () => {
        if (!disposed && generation === generationRef.current) setConnectionStatus("disconnected");
      };
      client.activate();
    });

    return () => {
      disposed = true;
      generationRef.current += 1;
      const client = clientRef.current;
      if (client) {
        clientRef.current = null;
        deactivationRef.current = client.deactivate().catch(() => undefined);
      }
    };
  }, [accessToken, refreshUnreadCount]);

  const unreadCount = conversationActivity === null
    ? null
    : Object.values(conversationActivity).reduce((total, activity) => total + activity.unreadCount, 0);
  const value = useMemo<ChatRealtimeContextValue>(() => ({
    connectionStatus,
    conversationActivity,
    reconciliationRevision,
    unreadCount,
    subscribe,
    clearConversationUnread,
    refreshUnreadCount,
  }), [clearConversationUnread, connectionStatus, conversationActivity, reconciliationRevision, refreshUnreadCount, subscribe, unreadCount]);
  return <ChatRealtimeContext.Provider value={value}>{children}</ChatRealtimeContext.Provider>;
}

export function useChatRealtime(): ChatRealtimeContextValue {
  const context = useContext(ChatRealtimeContext);
  if (!context) throw new Error("useChatRealtime must be used within ChatRealtimeProvider");
  return context;
}
