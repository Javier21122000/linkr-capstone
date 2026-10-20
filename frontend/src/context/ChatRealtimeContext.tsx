import { Client } from "@stomp/stompjs";
import type { IMessage } from "@stomp/stompjs";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

import type { MessageResponse } from "../types/chat";
import { useAuth } from "./AuthContext";

type ConnectionStatus = "disabled" | "connecting" | "connected" | "disconnected";
type MessageListener = (message: MessageResponse) => void;

interface ChatRealtimeContextValue {
  readonly connectionStatus: ConnectionStatus;
  subscribe(listener: MessageListener): () => void;
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
  const { accessToken } = useAuth();
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>("disabled");
  const listenersRef = useRef(new Set<MessageListener>());
  const clientRef = useRef<Client | null>(null);
  const generationRef = useRef(0);

  const subscribe = useCallback((listener: MessageListener) => {
    listenersRef.current.add(listener);
    return () => listenersRef.current.delete(listener);
  }, []);

  useEffect(() => {
    const generation = ++generationRef.current;
    if (!accessToken) {
      setConnectionStatus("disabled");
      return undefined;
    }

    setConnectionStatus("connecting");
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
      if (generation !== generationRef.current) return;
      setConnectionStatus("connected");
      client.subscribe("/user/queue/messages", (frame: IMessage) => {
        try {
          const message: unknown = JSON.parse(frame.body);
          if (!isMessageResponse(message)) return;
          listenersRef.current.forEach((listener) => listener(message));
        } catch {
          // Un frame non valido viene ignorato: il percorso REST resta operativo.
        }
      });
    };
    client.onWebSocketClose = () => {
      if (generation === generationRef.current) setConnectionStatus("disconnected");
    };
    client.onWebSocketError = () => {
      if (generation === generationRef.current) setConnectionStatus("disconnected");
    };
    client.onStompError = () => {
      if (generation === generationRef.current) setConnectionStatus("disconnected");
    };
    client.activate();

    return () => {
      generationRef.current += 1;
      if (clientRef.current === client) clientRef.current = null;
      void client.deactivate();
    };
  }, [accessToken]);

  const value = useMemo<ChatRealtimeContextValue>(() => ({ connectionStatus, subscribe }), [connectionStatus, subscribe]);
  return <ChatRealtimeContext.Provider value={value}>{children}</ChatRealtimeContext.Provider>;
}

export function useChatRealtime(): ChatRealtimeContextValue {
  const context = useContext(ChatRealtimeContext);
  if (!context) throw new Error("useChatRealtime must be used within ChatRealtimeProvider");
  return context;
}
