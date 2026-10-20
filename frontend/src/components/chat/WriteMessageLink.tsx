import { MessageCircle } from "lucide-react";
import { Link } from "react-router";

interface WriteMessageLinkProps {
  readonly conversationId: string;
  readonly className?: string;
}

export function WriteMessageLink({ conversationId, className = "button-secondary" }: WriteMessageLinkProps) {
  return (
    <Link className={`${className} inline-flex items-center gap-2`} to={`/chats/${conversationId}`}>
      <MessageCircle size={17} aria-hidden="true" /> Scrivi
    </Link>
  );
}
