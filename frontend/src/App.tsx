import { Route, Routes } from "react-router";

import { AppShell } from "./components/layout/AppShell";
import { DiscoverPage } from "./pages/DiscoverPage";
import { EventDetailPage } from "./pages/EventDetailPage";
import { HealthPage } from "./pages/HealthPage";
import { NotFoundPage } from "./pages/NotFoundPage";

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<DiscoverPage />} />
        <Route path="events/:eventId" element={<EventDetailPage />} />
      </Route>
      <Route path="technical/health" element={<HealthPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
