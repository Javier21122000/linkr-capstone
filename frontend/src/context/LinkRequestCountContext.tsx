import axios from "axios";
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useLocation } from "react-router";

import { useAuth } from "./AuthContext";
import { getLinkRequests } from "../lib/linkRequests";

interface LinkRequestCountContextValue {
  readonly incomingCount: number | null;
  refreshIncomingCount(force?: boolean): Promise<void>;
}

interface ActiveCountRequest {
  readonly ownerId: string;
  readonly controller: AbortController;
  readonly promise: Promise<void>;
}

const LinkRequestCountContext = createContext<LinkRequestCountContextValue | null>(null);

export function LinkRequestCountProvider({ children }: { readonly children: ReactNode }) {
  const { status, user, authorizedRequest } = useAuth();
  const { pathname } = useLocation();
  const [countState, setCountState] = useState<{ readonly userId: string; readonly count: number } | null>(null);
  const activeRequestRef = useRef<ActiveCountRequest | null>(null);
  const requestIdRef = useRef(0);
  const lastRequestRef = useRef<{ readonly ownerId: string; readonly startedAt: number } | null>(null);

  const refreshIncomingCount = useCallback((force = false): Promise<void> => {
    if (status !== "authenticated" || !user) {
      setCountState(null);
      return Promise.resolve();
    }

    const ownerId = user.id;
    const activeRequest = activeRequestRef.current;
    if (!force && activeRequest?.ownerId === ownerId) return activeRequest.promise;

    const now = Date.now();
    const lastRequest = lastRequestRef.current;
    if (!force && lastRequest?.ownerId === ownerId && now - lastRequest.startedAt < 1_200) {
      return Promise.resolve();
    }

    activeRequest?.controller.abort();
    const controller = new AbortController();
    const requestId = ++requestIdRef.current;
    setCountState(null);
    lastRequestRef.current = { ownerId, startedAt: now };

    const promise = authorizedRequest((accessToken) => getLinkRequests(accessToken, {
      direction: "incoming",
      status: "PENDING",
      page: 0,
      size: 1,
      signal: controller.signal,
    })).then((response) => {
      if (requestId === requestIdRef.current) {
        setCountState({ userId: ownerId, count: response.totalElements });
      }
    }).catch((error: unknown) => {
      if (!axios.isCancel(error) && requestId === requestIdRef.current) setCountState(null);
    }).finally(() => {
      if (requestId === requestIdRef.current) activeRequestRef.current = null;
    });

    activeRequestRef.current = { ownerId, controller, promise };
    return promise;
  }, [authorizedRequest, status, user]);

  useEffect(() => {
    if (status !== "authenticated" || !user) {
      requestIdRef.current += 1;
      activeRequestRef.current?.controller.abort();
      activeRequestRef.current = null;
      lastRequestRef.current = null;
      setCountState(null);
      return;
    }
    void refreshIncomingCount();
  }, [pathname, refreshIncomingCount, status, user]);

  useEffect(() => {
    function refreshWhenVisible() {
      if (document.visibilityState === "visible") void refreshIncomingCount();
    }

    window.addEventListener("focus", refreshWhenVisible);
    document.addEventListener("visibilitychange", refreshWhenVisible);
    return () => {
      window.removeEventListener("focus", refreshWhenVisible);
      document.removeEventListener("visibilitychange", refreshWhenVisible);
    };
  }, [refreshIncomingCount]);

  useEffect(() => () => {
    requestIdRef.current += 1;
    activeRequestRef.current?.controller.abort();
    activeRequestRef.current = null;
  }, []);

  const value = useMemo<LinkRequestCountContextValue>(() => ({
    incomingCount: status === "authenticated" && user && countState?.userId === user.id ? countState.count : null,
    refreshIncomingCount,
  }), [countState, refreshIncomingCount, status, user]);

  return <LinkRequestCountContext.Provider value={value}>{children}</LinkRequestCountContext.Provider>;
}

export function useLinkRequestCount(): LinkRequestCountContextValue {
  const context = useContext(LinkRequestCountContext);
  if (!context) throw new Error("useLinkRequestCount must be used within LinkRequestCountProvider");
  return context;
}
