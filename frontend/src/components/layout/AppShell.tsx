import { useState } from "react";
import { Outlet, useLocation } from "react-router";

import { LinkRequestCountProvider } from "../../context/LinkRequestCountContext";
import { DiscoverPage } from "../../pages/DiscoverPage";
import { AppHeader } from "./AppHeader";
import { MobileNav } from "./MobileNav";

export function AppShell() {
  return (
    <LinkRequestCountProvider>
      <AppShellContent />
    </LinkRequestCountProvider>
  );
}

function AppShellContent() {
  const { pathname } = useLocation();
  const showMobileNav = pathname !== "/login" && pathname !== "/register" && pathname !== "/profile/interests";
  const discoverActive = pathname === "/";
  // Discover si monta alla prima visita e poi resta montato (nascosto fuori da "/"): una sola istanza MapLibre
  // per tutta la sessione, con vista, filtro e selezione intatti al ritorno.
  const [discoverMounted, setDiscoverMounted] = useState(discoverActive);
  if (discoverActive && !discoverMounted) setDiscoverMounted(true);

  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <AppHeader />
      <main id="main-content">
        {discoverMounted ? (
          <div hidden={!discoverActive}>
            <DiscoverPage active={discoverActive} />
          </div>
        ) : null}
        <Outlet />
      </main>
      {showMobileNav ? <MobileNav /> : null}
    </div>
  );
}
