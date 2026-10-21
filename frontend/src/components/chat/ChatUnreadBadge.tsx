import { useChatRealtime } from "../../context/ChatRealtimeContext";
import { CountBadge } from "../ui/CountBadge";

export function ChatUnreadBadge() {
  const { unreadCount } = useChatRealtime();
  return <CountBadge count={unreadCount} />;
}
