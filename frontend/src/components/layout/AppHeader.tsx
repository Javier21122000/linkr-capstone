import { Compass, Link2, MapPin, MessageCircle, Plus, UserRound, UsersRound, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
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
  const cityRef = useRef<HTMLDivElement>(null);
  const cityButtonRef = useRef<HTMLButtonElement>(null);
  const cityContinueRef = useRef<HTMLButtonElement>(null);
  const [cityOpen, setCityOpen] = useState(false);
  useSlidingIndicator({ trackRef: navRef, activeSelector: '[aria-current="page"]', activeKey: pathname });
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const linksAccessibleLabel = incomingCount
    ? `Link, ${incomingCount} ${incomingCount === 1 ? "richiesta ricevuta in attesa" : "richieste ricevute in attesa"}`
    : "Link";
  const chatAccessibleLabel = unreadCount
    ? `Chat, ${unreadCount} ${unreadCount === 1 ? "messaggio non letto" : "messaggi non letti"}`
    : "Chat";

  useEffect(() => {
    if (!cityOpen) return undefined;
    cityContinueRef.current?.focus({ preventScroll: true });
    const close = (restoreFocus: boolean) => {
      setCityOpen(false);
      if (restoreFocus) window.requestAnimationFrame(() => cityButtonRef.current?.focus());
    };
    const handlePointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && !cityRef.current?.contains(event.target)) close(false);
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        close(true);
      }
    };
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [cityOpen]);

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

        <nav ref={navRef} className="app-nav glass" aria-label="Primary navigation" data-map-inset="top" data-tour="navigation">
          <span className="nav-thumb" aria-hidden="true" />
          <NavItem to="/" end className="desktop-nav-link">
            <Compass size={18} aria-hidden="true" /> <span>Discover</span>
          </NavItem>
          {status === "authenticated" ? (
            <>
              <NavItem to="/people" className="desktop-nav-link" aria-label="Persone" data-tour="nav-people">
                <UsersRound size={18} aria-hidden="true" /> <span className="hidden lg:inline">Persone</span>
              </NavItem>
              <NavItem to="/profile/links" className="desktop-nav-link" aria-label={linksAccessibleLabel} data-tour="nav-links">
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
          <div ref={cityRef} className="app-header__city-wrap">
            <button ref={cityButtonRef} className="app-header__city app-pill glass" type="button" aria-expanded={cityOpen} aria-haspopup="dialog" onClick={() => setCityOpen((current) => !current)}>
              <MapPin size={17} className="text-linkr-action" aria-hidden="true" />
              <span>Milano</span>
            </button>
            {cityOpen ? (
              <section className="city-popover glass glass--sheet" role="dialog" aria-modal="false" aria-labelledby="city-popover-title">
                <div className="flex items-start justify-between gap-3">
                  <div><p className="section-kicker">La nostra città</p><h2 id="city-popover-title" className="mt-1 text-xl font-extrabold tracking-[-0.04em] text-ink">LINKR parte da Milano.</h2></div>
                  <button className="icon-button city-popover__close" type="button" onClick={() => { setCityOpen(false); cityButtonRef.current?.focus(); }} aria-label="Chiudi"><X size={17} aria-hidden="true" /></button>
                </div>
                <p className="mt-3 text-sm font-semibold leading-6 text-muted">Stiamo costruendo nuovi modi per scoprire eventi, incontrare persone e vivere la città insieme.</p>
                <p className="mt-2 text-sm font-semibold leading-6 text-muted">Per ora ci trovi qui. Presto vogliamo esplorare nuove città.</p>
                <button ref={cityContinueRef} className="button-primary mt-5 inline-flex w-full items-center" type="button" onClick={() => { setCityOpen(false); cityButtonRef.current?.focus(); }}>Continua a esplorare</button>
              </section>
            ) : null}
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
