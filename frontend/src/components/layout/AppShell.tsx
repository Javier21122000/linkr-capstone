import { Outlet, useLocation } from "react-router";

import { AppHeader } from "./AppHeader";
import { MobileNav } from "./MobileNav";

export function AppShell() {
  const { pathname } = useLocation();
  const showMobileNav = pathname !== "/login" && pathname !== "/register";

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
