import { Compass, Link2, MapPin, MessageCircle, Plus, UserRound, UsersRound } from "lucide-react";
import { Link, useLocation } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useChatRealtime } from "../../context/ChatRealtimeContext";
import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { ChatUnreadBadge } from "../chat/ChatUnreadBadge";
import { IncomingLinkCountBadge } from "../links/IncomingLinkCountBadge";
import { BrandMark } from "../ui/BrandMark";
import { NavItem } from "./NavItem";

export function AppHeader() {
  const { status, user } = useAuth();
  const { pathname } = useLocation();
  const { unreadCount } = useChatRealtime();
  const { incomingCount } = useLinkRequestCount();
  const immersive = pathname === "/";
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const linksAccessibleLabel = incomingCount
    ? `Link, ${incomingCount} ${incomingCount === 1 ? "richiesta ricevuta in attesa" : "richieste ricevute in attesa"}`
    : "Link";
  const chatAccessibleLabel = unreadCount
    ? `Chat, ${unreadCount} ${unreadCount === 1 ? "messaggio non letto" : "messaggi non letti"}`
    : "Chat";

  return (
    <header className="app-header" data-immersive={immersive}>
      <div className="page-shell app-header__inner">
        <Link to="/" className="app-header__brand" aria-label="LINKR home">
          <BrandMark />
        </Link>

        <nav className="hidden items-center gap-1 md:flex" aria-label="Primary navigation">
          <NavItem to="/" end className="desktop-nav-link">
            <Compass size={18} aria-hidden="true" /> Discover
          </NavItem>
          {status === "authenticated" ? (
            <>
              <NavItem to="/people" className="desktop-nav-link" aria-label="Persone">
                <UsersRound size={18} aria-hidden="true" /> <span className="hidden lg:inline">Persone</span>
              </NavItem>
              <NavItem to="/profile/links" className="desktop-nav-link" aria-label={linksAccessibleLabel}>
                <Link2 size={18} aria-hidden="true" /> <span className="hidden lg:inline">Link</span> <IncomingLinkCountBadge />
              </NavItem>
              <NavItem to="/chats" className="desktop-nav-link" aria-label={chatAccessibleLabel}>
                <MessageCircle size={18} aria-hidden="true" /> <span className="hidden lg:inline">Chat</span> <ChatUnreadBadge />
              </NavItem>
            </>
          ) : null}
        </nav>

        <div className="flex min-w-0 items-center gap-2">
          <div className="app-header__city">
            <MapPin size={17} className="text-linkr" aria-hidden="true" />
            <span>Milano</span>
          </div>
          {status === "anonymous" ? (
            <>
              <span className="hidden md:inline-flex">
                <Link to="/login" className="desktop-nav-link">Accedi</Link>
              </span>
              <Link to="/register" className="button-primary hidden items-center lg:inline-flex">Registrati</Link>
            </>
          ) : (
            <>
              <Link to="/events/new" className="app-header__create button-primary inline-flex shrink-0 items-center gap-1.5 px-3 sm:px-4">
                <Plus size={18} strokeWidth={2.6} aria-hidden="true" />
                <span className="lg:hidden">Crea</span>
                <span className="hidden lg:inline">Crea evento</span>
              </Link>
              <span className="hidden min-w-0 md:inline-flex">
                <NavItem
                  to="/profile"
                  excludePaths={["/profile/links"]}
                  className="desktop-nav-link min-w-0"
                  aria-label={`Apri il profilo di ${profileLabel}`}
                  title={profileLabel}
                >
                  <UserRound size={18} className="shrink-0" aria-hidden="true" />
                  <span className="account-nav-label hidden lg:inline">{profileLabel}</span>
                </NavItem>
              </span>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
