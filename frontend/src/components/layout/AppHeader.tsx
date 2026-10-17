import { MapPin, UserRound } from "lucide-react";
import { NavLink } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { IncomingLinkCountBadge } from "../links/IncomingLinkCountBadge";
import { BrandMark } from "../ui/BrandMark";

export function AppHeader() {
  const { status, user } = useAuth();
  const { incomingCount } = useLinkRequestCount();
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const profileAccessibleLabel = incomingCount
    ? `Apri il profilo di ${profileLabel}, ${incomingCount} richieste Link ricevute in attesa`
    : `Apri il profilo di ${profileLabel}`;

  return (
    <header className="sticky top-0 z-40 border-b border-line/80 bg-canvas/96">
      <div className="page-shell flex h-[4.5rem] items-center justify-between gap-4 lg:h-20">
        <NavLink to="/" className="rounded-md" aria-label="LINKR home">
          <BrandMark />
        </NavLink>

        <nav className="hidden items-center gap-1 md:flex" aria-label="Primary navigation">
          <NavLink to="/" end className="desktop-nav-link">
            Discover
          </NavLink>
        </nav>

        <div className="flex min-w-0 items-center gap-2">
          <div className="inline-flex shrink-0 items-center gap-2 rounded-full border border-line bg-surface px-3 py-2 text-sm font-bold text-ink sm:px-4">
            <MapPin size={17} className="text-linkr-strong" aria-hidden="true" />
            <span>Milano</span>
          </div>
          {status === "anonymous" ? (
            <>
              <NavLink to="/login" className="desktop-nav-link hidden md:inline-flex">Accedi</NavLink>
              <NavLink to="/register" className="button-primary hidden items-center lg:inline-flex">Registrati</NavLink>
            </>
          ) : (
            <NavLink
              to="/profile"
              className="desktop-nav-link hidden min-w-0 gap-2 md:inline-flex"
              aria-label={profileAccessibleLabel}
              title={profileLabel}
            >
              <UserRound size={18} className="shrink-0" aria-hidden="true" />
              <span className="account-nav-label">{profileLabel}</span>
              <IncomingLinkCountBadge />
            </NavLink>
          )}
        </div>
      </div>
    </header>
  );
}
