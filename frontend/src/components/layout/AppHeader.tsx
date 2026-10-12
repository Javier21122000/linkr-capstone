import { MapPin } from "lucide-react";
import { NavLink } from "react-router";

import { BrandMark } from "../ui/BrandMark";

export function AppHeader() {
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
          <button className="desktop-nav-link gap-2" type="button" disabled title="Map arrives in Phase 3">
            Map <span className="nav-soon">Next</span>
          </button>
        </nav>

        <div className="inline-flex items-center gap-2 rounded-full border border-line bg-surface px-3 py-2 text-sm font-bold text-ink sm:px-4">
          <MapPin size={17} className="text-linkr-strong" aria-hidden="true" />
          <span>Milano</span>
        </div>
      </div>
    </header>
  );
}
