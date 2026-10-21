import { Compass, Link2, MapPin, MessageCircle, Plus, UserRound, UsersRound } from "lucide-react";
import { useRef } from "react";
import { Link, useLocation } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useChatRealtime } from "../../context/ChatRealtimeContext";
import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { useSlidingIndicator } from "../../hooks/useSlidingIndicator";
import { ChatUnreadBadge } from "../chat/ChatUnreadBadge";
import { IncomingLinkCountBadge } from "../links/IncomingLinkCountBadge";
import { BrandMark } from "../ui/BrandMark";
import { NavItem } from "./NavItem";

/**
 * Navigazione principale: tre gruppi di vetro (brand, navigazione, città/azioni) che galleggiano in alto su ogni pagina,
 * sopra la mappa di Discover o sopra lo sfondo ambientale delle altre. L'indicatore della voce attiva è un solo elemento che
 * scorre da una voce all'altra. Gli elementi con `data-map-inset="top"` dicono alla camera quanto spazio coprono sopra la mappa.
 */
export function AppHeader() {
  const { status, user } = useAuth();
  const { pathname } = useLocation();
  const { unreadCount } = useChatRealtime();
  const { incomingCount } = useLinkRequestCount();
  const navRef = useRef<HTMLElement>(null);
  useSlidingIndicator({ trackRef: navRef, activeSelector: '[aria-current="page"]', activeKey: pathname });
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const linksAccessibleLabel = incomingCount
    ? `Link, ${incomingCount} ${incomingCount === 1 ? "richiesta ricevuta in attesa" : "richieste ricevute in attesa"}`
    : "Link";
  const chatAccessibleLabel = unreadCount
    ? `Chat, ${unreadCount} ${unreadCount === 1 ? "messaggio non letto" : "messaggi non letti"}`
    : "Chat";

  return (
    <header className="app-header" data-view={pathname === "/" ? "discover" : "page"}>
      <div className="app-header__inner">
        <div className="app-header__start">
          <Link
            to="/"
            className="app-header__brand glass"
            aria-label="LINKR home"
            data-map-inset="top"
          >
            <BrandMark />
          </Link>
        </div>

        <nav ref={navRef} className="app-nav glass" aria-label="Primary navigation" data-map-inset="top">
          <span className="nav-thumb" aria-hidden="true" />
          <NavItem to="/" end className="desktop-nav-link">
            <Compass size={18} aria-hidden="true" /> <span>Discover</span>
          </NavItem>
          {status === "authenticated" ? (
            <>
              <NavItem to="/people" className="desktop-nav-link" aria-label="Persone">
                <UsersRound size={18} aria-hidden="true" /> <span className="hidden lg:inline">Persone</span>
              </NavItem>
              <NavItem to="/profile/links" className="desktop-nav-link" aria-label={linksAccessibleLabel}>
                <span className="nav-icon">
                  <Link2 size={18} aria-hidden="true" />
                  <span className="nav-badge"><IncomingLinkCountBadge /></span>
                </span>
                <span className="hidden lg:inline">Link</span>
              </NavItem>
              <NavItem to="/chats" className="desktop-nav-link" aria-label={chatAccessibleLabel}>
                <span className="nav-icon">
                  <MessageCircle size={18} aria-hidden="true" />
                  <span className="nav-badge"><ChatUnreadBadge /></span>
                </span>
                <span className="hidden lg:inline">Chat</span>
              </NavItem>
            </>
          ) : null}
        </nav>

        <div className="app-header__end" data-map-inset="top">
          <div className="app-header__city app-pill glass">
            <MapPin size={17} className="text-linkr-action" aria-hidden="true" />
            <span>Milano</span>
          </div>
          {status === "anonymous" ? (
            <>
              <Link to="/login" className="app-pill app-header__login glass">Accedi</Link>
              <Link to="/register" className="app-header__create button-primary app-header__register inline-flex items-center">Registrati</Link>
            </>
          ) : (
            <>
              <Link to="/events/new" className="app-header__create button-primary inline-flex shrink-0 items-center gap-1.5 px-3 sm:px-4">
                <Plus size={18} strokeWidth={2.6} aria-hidden="true" />
                <span className="lg:hidden">Crea</span>
                <span className="hidden lg:inline">Crea evento</span>
              </Link>
              <NavItem
                to="/profile"
                excludePaths={["/profile/links"]}
                className="app-pill app-header__profile glass"
                aria-label={`Apri il profilo di ${profileLabel}`}
                title={profileLabel}
              >
                <UserRound size={18} className="shrink-0" aria-hidden="true" />
                <span className="account-nav-label hidden xl:inline">{profileLabel}</span>
              </NavItem>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
