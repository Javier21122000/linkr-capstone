export type LinkRequestStatus = "PENDING" | "ACCEPTED" | "DECLINED";
export type LinkRequestDirection = "INCOMING" | "OUTGOING";
export type LinkRequestDirectionFilter = "incoming" | "outgoing" | "all";

export interface CreateLinkRequest {
  readonly eventId?: string;
  readonly recipientId: string;
}

export interface LinkRequestUserResponse {
  readonly userId: string;
  readonly username: string;
  readonly displayName: string;
  readonly avatarUrl: string | null;
}

export interface LinkRequestResponse {
  readonly requestId: string;
  readonly status: LinkRequestStatus;
  readonly direction: LinkRequestDirection;
  readonly createdAt: string;
  readonly respondedAt: string | null;
  readonly eventId: string | null;
  readonly eventTitle: string | null;
  readonly otherUser: LinkRequestUserResponse;
}
