import { Compass, Link2, LogIn, MessageCircle, UserRound, UsersRound } from "lucide-react";
import { useRef } from "react";
import { useLocation } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useChatRealtime } from "../../context/ChatRealtimeContext";
import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { useSlidingIndicator } from "../../hooks/useSlidingIndicator";
import { ChatUnreadBadge } from "../chat/ChatUnreadBadge";
import { IncomingLinkCountBadge } from "../links/IncomingLinkCountBadge";
import { NavItem } from "./NavItem";

const ICON_SIZE = 22;

export function MobileNav() {
  const { status, user } = useAuth();
  const { unreadCount } = useChatRealtime();
  const { incomingCount } = useLinkRequestCount();
  const { pathname } = useLocation();
  const navRef = useRef<HTMLElement>(null);
  useSlidingIndicator({ trackRef: navRef, activeSelector: '[aria-current="page"]', activeKey: `${pathname}:${status}` });
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const linksAccessibleLabel = incomingCount
    ? `Link, ${incomingCount} ${incomingCount === 1 ? "richiesta ricevuta in attesa" : "richieste ricevute in attesa"}`
    : "Link";
  const chatAccessibleLabel = unreadCount
    ? `Chat, ${unreadCount} ${unreadCount === 1 ? "messaggio non letto" : "messaggi non letti"}`
    : "Chat";

  return (
    <nav ref={navRef} className="mobile-nav glass" aria-label="Primary navigation" data-map-inset="bottom">
      <span className="nav-thumb" aria-hidden="true" />
      <NavItem to="/" end className="mobile-nav__item">
        <span className="mobile-nav__icon"><Compass size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" /></span>
        <span className="mobile-nav__label">Discover</span>
      </NavItem>
      {status === "anonymous" ? (
        <NavItem to="/login" className="mobile-nav__item">
          <span className="mobile-nav__icon"><LogIn size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" /></span>
          <span className="mobile-nav__label">Accedi</span>
        </NavItem>
      ) : (
        <>
          <NavItem to="/people" className="mobile-nav__item">
            <span className="mobile-nav__icon"><UsersRound size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" /></span>
            <span className="mobile-nav__label">Persone</span>
          </NavItem>
          <NavItem to="/profile/links" className="mobile-nav__item" aria-label={linksAccessibleLabel}>
            <span className="mobile-nav__icon">
              <Link2 size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" />
              <span className="mobile-nav__badge"><IncomingLinkCountBadge /></span>
            </span>
            <span className="mobile-nav__label">Link</span>
          </NavItem>
          <NavItem to="/chats" className="mobile-nav__item" aria-label={chatAccessibleLabel}>
            <span className="mobile-nav__icon">
              <MessageCircle size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" />
              <span className="mobile-nav__badge"><ChatUnreadBadge /></span>
            </span>
            <span className="mobile-nav__label">Chat</span>
          </NavItem>
          <NavItem
            to="/profile"
            excludePaths={["/profile/links"]}
            className="mobile-nav__item"
            aria-label={`Apri il profilo di ${profileLabel}`}
            title={profileLabel}
          >
            <span className="mobile-nav__icon"><UserRound size={ICON_SIZE} strokeWidth={2.2} aria-hidden="true" /></span>
            <span className="mobile-nav__label">Profilo</span>
          </NavItem>
        </>
      )}
    </nav>
  );
}
