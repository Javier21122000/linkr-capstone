import { useState } from "react";
import { Outlet, useLocation, useNavigation } from "react-router";

import { LinkRequestCountProvider } from "../../context/LinkRequestCountContext";
import { DiscoverPage } from "../../pages/DiscoverPage";
import { AppErrorBoundary } from "../ui/AppErrorBoundary";
import { AppHeader } from "./AppHeader";
import { AmbientMap } from "./AmbientMap";
import { MobileNav } from "./MobileNav";

/** Quanto lo sfondo ambientale deve farsi da parte: nelle chat i messaggi sono tutto, in accesso e registrazione i campi. */
function ambientKind(pathname: string): "page" | "chat" | "form" {
  if (pathname.startsWith("/chats")) return "chat";
  if (pathname === "/login" || pathname === "/register" || pathname.endsWith("/edit") || pathname === "/events/new") return "form";
  return "page";
}

export function AppShell() {
  return (
    <LinkRequestCountProvider>
      <AppShellContent />
    </LinkRequestCountProvider>
  );
}

function AppShellContent() {
  const { pathname } = useLocation();
  // Le pagine secondarie si scaricano alla prima visita: mentre il router aspetta, la pagina resta quella attuale
  // e una barra sottile fa capire che il tocco è stato recepito (compare solo se l'attesa supera ~180 ms).
  const navigating = useNavigation().state !== "idle";
  const showMobileNav = pathname !== "/login" && pathname !== "/register" && pathname !== "/profile/interests";
  const discoverActive = pathname === "/";
  // Discover si monta alla prima visita e poi resta montato (nascosto fuori da "/"): una sola istanza MapLibre
  // per tutta la sessione, con vista, filtro e selezione intatti al ritorno.
  const [discoverMounted, setDiscoverMounted] = useState(discoverActive);
  if (discoverActive && !discoverMounted) setDiscoverMounted(true);

  return (
    <div className="app-shell text-ink" data-view={discoverActive ? "discover" : "page"} data-ambient={ambientKind(pathname)}>
      {/* Fuori da Discover la mappa non c'è: resta un fondo cartografico leggero derivato dai token, senza richieste né WebGL. */}
      <AmbientMap />
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      {navigating ? (
        <div className="route-progress" role="status">
          <span className="sr-only">Caricamento della pagina in corso</span>
        </div>
      ) : null}
      <AppHeader />
      <main id="main-content">
        {discoverMounted ? (
          <div className="discover-host" hidden={!discoverActive}>
            {/* Se Discover si rompe, intestazione e navigazione restano utilizzabili. */}
            <AppErrorBoundary inline>
              <DiscoverPage active={discoverActive} />
            </AppErrorBoundary>
          </div>
        ) : null}
        {/* Ogni pagina entra con un breve movimento (opacità + traslazione). La chiave la rimonta a ogni cambio di percorso, quindi anche tra due chat. */}
        <div className="app-content" key={pathname}>
          <Outlet />
        </div>
      </main>
      {showMobileNav ? <MobileNav /> : null}
    </div>
  );
}
