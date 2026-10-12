import { Route, Routes } from "react-router";

import { AppShell } from "./components/layout/AppShell";
import { DiscoverPage } from "./pages/DiscoverPage";
import { NotFoundPage } from "./pages/NotFoundPage";

export function App() {
  return (
    <Routes>
      <Route element={<AppShell />}>
        <Route index element={<DiscoverPage />} />
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
