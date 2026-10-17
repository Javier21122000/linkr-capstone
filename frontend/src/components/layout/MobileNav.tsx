import { Compass, LogIn, UserRound } from "lucide-react";
import { NavLink } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { IncomingLinkCountBadge } from "../links/IncomingLinkCountBadge";

export function MobileNav() {
  const { status, user } = useAuth();
  const { incomingCount } = useLinkRequestCount();
  const profileLabel = user ? `@${user.username}` : "Profilo";
  const profileAccessibleLabel = incomingCount
    ? `Apri il profilo di ${profileLabel}, ${incomingCount} richieste Link ricevute in attesa`
    : `Apri il profilo di ${profileLabel}`;

  return (
    <nav className="mobile-nav md:hidden" aria-label="Primary navigation">
      <NavLink to="/" end className="mobile-nav__item">
        <Compass size={21} strokeWidth={2.4} aria-hidden="true" />
        <span className="mobile-nav__label">Discover</span>
      </NavLink>
      {status === "anonymous" ? (
        <NavLink to="/login" className="mobile-nav__item">
          <LogIn size={21} strokeWidth={2.2} aria-hidden="true" />
          <span className="mobile-nav__label">Accedi</span>
        </NavLink>
      ) : (
        <NavLink
          to="/profile"
          className="mobile-nav__item"
          aria-label={profileAccessibleLabel}
          title={profileLabel}
        >
          <UserRound size={21} strokeWidth={2.2} className="shrink-0" aria-hidden="true" />
          <span className="mobile-nav__label">{profileLabel}</span>
          <IncomingLinkCountBadge />
        </NavLink>
      )}
    </nav>
  );
}
