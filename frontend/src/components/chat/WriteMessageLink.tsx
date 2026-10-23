import { MessageCircle } from "lucide-react";
import { Link } from "react-router";

interface WriteMessageLinkProps {
  readonly conversationId: string;
  readonly className?: string;
  /** Testo del collegamento: "Scrivi" nelle schede compatte, per esteso dove c'è posto. */
  readonly label?: string;
}

export function WriteMessageLink({ conversationId, className = "button-secondary", label = "Scrivi" }: WriteMessageLinkProps) {
  return (
    <Link className={`${className} inline-flex items-center gap-2`} to={`/chats/${conversationId}`}>
      <MessageCircle size={17} aria-hidden="true" /> {label}
    </Link>
  );
}
