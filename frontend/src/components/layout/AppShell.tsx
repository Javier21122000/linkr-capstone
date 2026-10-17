import { Outlet, useLocation } from "react-router";

import { LinkRequestCountProvider } from "../../context/LinkRequestCountContext";
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

  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <a className="skip-link" href="#main-content">
        Skip to content
      </a>
      <AppHeader />
      <main id="main-content">
        <Outlet />
      </main>
      {showMobileNav ? <MobileNav /> : null}
    </div>
  );
}
