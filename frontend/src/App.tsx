import type { ComponentType } from "react";
import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from "react-router";

import { ProtectedRoute } from "./components/auth/ProtectedRoute";
import { AdminRoute } from "./components/auth/AdminRoute";
import { AppShell } from "./components/layout/AppShell";
import { EventDetailPage } from "./pages/EventDetailPage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { RegisterPage } from "./pages/RegisterPage";
import { BootSplash } from "./components/ui/BootSplash";
import { bootStore } from "./lib/bootStore";
import { RouteErrorPage } from "./pages/RouteErrorPage";

/**
 * Pagina caricata alla prima visita. Restano nel pacchetto principale solo le schermate da cui si entra nell'app
 * (Discover, dettaglio evento da un link condiviso, accesso, registrazione): le altre si scaricano quando servono.
 * La navigazione attende il download senza svuotare la pagina corrente, quindi Discover (e la sua mappa) resta com'è.
 */
function lazyPage<Name extends string>(load: () => Promise<Record<Name, ComponentType>>, name: Name) {
  return async () => ({ Component: (await load())[name] });
}

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route errorElement={<RouteErrorPage />}>
      <Route element={<AppShell />}>
        {/* Un errore in una pagina compare dentro la struttura dell'app: intestazione e navigazione restano utilizzabili. */}
        <Route errorElement={<RouteErrorPage inline />}>
          {/* Discover è ospitato da AppShell e resta montato: vedi AppShell.tsx */}
          <Route index element={null} />
          <Route path="events/:eventId" element={<EventDetailPage />} />
          <Route path="login" element={<LoginPage />} />
          <Route path="register" element={<RegisterPage />} />
          <Route element={<ProtectedRoute />}>
            <Route path="chats" lazy={lazyPage(() => import("./pages/ChatsPage"), "ChatsPage")} />
            <Route path="chats/:conversationId" lazy={lazyPage(() => import("./pages/ChatThreadPage"), "ChatThreadPage")} />
            <Route path="people" lazy={lazyPage(() => import("./pages/DiscoverPeoplePage"), "DiscoverPeoplePage")} />
            <Route path="people/:personId" lazy={lazyPage(() => import("./pages/PublicProfilePage"), "PublicProfilePage")} />
            <Route path="events/new" lazy={lazyPage(() => import("./pages/CreateEventPage"), "CreateEventPage")} />
            <Route path="events/:eventId/edit" lazy={lazyPage(() => import("./pages/CreateEventPage"), "EditEventPage")} />
            <Route path="profile" lazy={lazyPage(() => import("./pages/ProfilePage"), "ProfilePage")} />
            <Route path="profile/events" lazy={lazyPage(() => import("./pages/MyEventsPage"), "MyEventsPage")} />
            <Route path="profile/interests" lazy={lazyPage(() => import("./pages/EditInterestsPage"), "EditInterestsPage")} />
            <Route path="profile/links" lazy={lazyPage(() => import("./pages/ProfileLinksPage"), "ProfileLinksPage")} />
            <Route element={<AdminRoute />}>
              <Route path="admin" lazy={lazyPage(() => import("./pages/AdminPage"), "AdminPage")} />
            </Route>
          </Route>
        </Route>
      </Route>
      <Route path="technical/health" lazy={lazyPage(() => import("./pages/HealthPage"), "HealthPage")} />
      <Route path="*" element={<NotFoundPage />} />
    </Route>,
  ),
);

// Lo splash di avvio aspetta che il router abbia scaricato e inizializzato la prima pagina (anche una pagina `lazy` aperta da un link diretto).
if (router.state.initialized) bootStore.mark({ routerReady: true });
else router.subscribe((state) => { if (state.initialized) bootStore.mark({ routerReady: true }); });

export function App() {
  return (
    <>
      <RouterProvider router={router} />
      <BootSplash />
    </>
  );
}
