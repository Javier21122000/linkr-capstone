import axios from "axios";

import type { ConversationResponse, MessagePageResponse, MessageResponse, SendMessageRequest } from "../types/chat";
import type { PageResponse } from "../types/people";
import { apiClient } from "./api";
import { getProblemDetail } from "./auth";

function authorization(accessToken: string) {
  return { Authorization: `Bearer ${accessToken}` };
}

export async function getConversations(
  accessToken: string,
  page: number,
  size = 20,
  signal?: AbortSignal,
): Promise<PageResponse<ConversationResponse>> {
  const { data } = await apiClient.get<PageResponse<ConversationResponse>>("/api/conversations", {
    headers: authorization(accessToken),
    params: { page, size },
    signal,
  });
  return data;
}

export async function findConversation(
  accessToken: string,
  conversationId: string,
  signal?: AbortSignal,
): Promise<ConversationResponse | null> {
  let page = 0;
  while (true) {
    const response = await getConversations(accessToken, page, 50, signal);
    const conversation = response.items.find((item) => item.conversationId === conversationId);
    if (conversation) return conversation;
    page += 1;
    if (page >= response.totalPages) return null;
  }
}

export async function getMessages(
  accessToken: string,
  conversationId: string,
  before?: string,
  size = 30,
  signal?: AbortSignal,
): Promise<MessagePageResponse> {
  const { data } = await apiClient.get<MessagePageResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
    {
      headers: authorization(accessToken),
      params: { before, size },
      signal,
    },
  );
  return data;
}

export async function sendMessage(
  accessToken: string,
  conversationId: string,
  request: SendMessageRequest,
): Promise<MessageResponse> {
  const { data } = await apiClient.post<MessageResponse>(
    `/api/conversations/${encodeURIComponent(conversationId)}/messages`,
    request,
    { headers: authorization(accessToken) },
  );
  return data;
}

export async function markConversationRead(accessToken: string, conversationId: string): Promise<void> {
  await apiClient.post(
    `/api/conversations/${encodeURIComponent(conversationId)}/read`,
    undefined,
    { headers: authorization(accessToken) },
  );
}

export function isConversationNotFound(error: unknown): boolean {
  const problem = getProblemDetail(error);
  return problem?.status === 404 && problem.title === "Conversation not found";
}

export function getChatErrorMessage(error: unknown): string {
  const problem = getProblemDetail(error);
  if (problem?.title === "Conversation not found") return "La conversazione non è disponibile.";
  if (problem?.title === "Invalid filters") return "Il messaggio non è valido. Controlla il testo e riprova.";

  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a contattare il servizio. Controlla la connessione e riprova.";
    if (error.response.status === 401) return "La sessione è scaduta. Accedi di nuovo.";
    if (error.response.status >= 500) return "Il servizio non è disponibile in questo momento. Riprova tra poco.";
  }

  return "Non è stato possibile completare l’operazione. Riprova.";
}
