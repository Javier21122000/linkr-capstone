import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from "react-router";

import { ProtectedRoute } from "./components/auth/ProtectedRoute";
import { AppShell } from "./components/layout/AppShell";
import { CreateEventPage, EditEventPage } from "./pages/CreateEventPage";
import { ChatsPage } from "./pages/ChatsPage";
import { ChatThreadPage } from "./pages/ChatThreadPage";
import { DiscoverPeoplePage } from "./pages/DiscoverPeoplePage";
import { EditInterestsPage } from "./pages/EditInterestsPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { HealthPage } from "./pages/HealthPage";
import { LoginPage } from "./pages/LoginPage";
import { MyEventsPage } from "./pages/MyEventsPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ProfilePage } from "./pages/ProfilePage";
import { ProfileLinksPage } from "./pages/ProfileLinksPage";
import { RegisterPage } from "./pages/RegisterPage";

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route element={<AppShell />}>
        {/* Discover è ospitato da AppShell e resta montato: vedi AppShell.tsx */}
        <Route index element={null} />
        <Route path="events/:eventId" element={<EventDetailPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="chats" element={<ChatsPage />} />
          <Route path="chats/:conversationId" element={<ChatThreadPage />} />
          <Route path="people" element={<DiscoverPeoplePage />} />
          <Route path="events/new" element={<CreateEventPage />} />
          <Route path="events/:eventId/edit" element={<EditEventPage />} />
          <Route path="profile" element={<ProfilePage />} />
          <Route path="profile/events" element={<MyEventsPage />} />
          <Route path="profile/interests" element={<EditInterestsPage />} />
          <Route path="profile/links" element={<ProfileLinksPage />} />
        </Route>
      </Route>
      <Route path="technical/health" element={<HealthPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Route>,
  ),
);

export function App() {
  return (
    <RouterProvider router={router} />
  );
}
