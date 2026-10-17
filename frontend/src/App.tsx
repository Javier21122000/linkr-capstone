import { createBrowserRouter, createRoutesFromElements, Route, RouterProvider } from "react-router";

import { ProtectedRoute } from "./components/auth/ProtectedRoute";
import { AppShell } from "./components/layout/AppShell";
import { DiscoverPage } from "./pages/DiscoverPage";
import { EditInterestsPage } from "./pages/EditInterestsPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { HealthPage } from "./pages/HealthPage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ProfilePage } from "./pages/ProfilePage";
import { RegisterPage } from "./pages/RegisterPage";

const router = createBrowserRouter(
  createRoutesFromElements(
    <Route>
      <Route element={<AppShell />}>
        <Route index element={<DiscoverPage />} />
        <Route path="events/:eventId" element={<EventDetailPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="profile" element={<ProfilePage />} />
          <Route path="profile/interests" element={<EditInterestsPage />} />
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
