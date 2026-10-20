import type { LinkRequestUserResponse } from "./linkRequest";

export interface MessageResponse {
  readonly messageId: string;
  readonly conversationId: string;
  readonly senderId: string;
  readonly mine: boolean;
  readonly body: string;
  readonly createdAt: string;
  readonly readAt: string | null;
}

export interface ConversationResponse {
  readonly conversationId: string;
  readonly otherUser: LinkRequestUserResponse;
  readonly lastMessage: MessageResponse | null;
  readonly unreadCount: number;
  readonly linkedAt: string;
}

export interface MessagePageResponse {
  readonly items: readonly MessageResponse[];
  readonly hasMore: boolean;
}

export interface SendMessageRequest {
  readonly body: string;
}
