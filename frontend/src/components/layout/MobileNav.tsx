import { Compass, Map } from "lucide-react";
import { NavLink } from "react-router";

export function MobileNav() {
  return (
    <nav className="mobile-nav md:hidden" aria-label="Primary navigation">
      <NavLink to="/" end className="mobile-nav__item" aria-current="page">
        <Compass size={21} strokeWidth={2.4} aria-hidden="true" />
        <span>Discover</span>
      </NavLink>
      <button
        type="button"
        className="mobile-nav__item relative"
        disabled
        aria-label="Map, available in the next phase"
      >
        <Map size={21} strokeWidth={2.2} aria-hidden="true" />
        <span>Map</span>
        <span className="absolute right-3 top-2 size-2 rounded-full bg-linkr" aria-hidden="true" />
      </button>
    </nav>
  );
}
