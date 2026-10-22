import "@fontsource-variable/manrope";
// Urban Editorial Pop (sperimentale, vedi styles/urban-editorial.css): serif solo per pochi titoli. Il corsivo si scarica solo se usato.
import "@fontsource-variable/fraunces/wght.css";
import "@fontsource-variable/fraunces/wght-italic.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import { AuthProvider } from "./context/AuthContext";
import { AppErrorBoundary } from "./components/ui/AppErrorBoundary";
import { ChatRealtimeProvider } from "./context/ChatRealtimeContext";
import "./styles/index.css";
import "./styles/admin.css";

// La connessione anticipata a MapTiler serve solo a chi atterra su Discover: su Login, Chat o Persone la mappa non c'è e non si apre alcuna connessione.
if (window.location.pathname === "/") {
  const preconnect = document.createElement("link");
  preconnect.rel = "preconnect";
  preconnect.href = "https://api.maptiler.com";
  preconnect.crossOrigin = "";
  document.head.append(preconnect);
}

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("Root element not found");
}

createRoot(rootElement).render(
  <StrictMode>
    <AppErrorBoundary>
      <AuthProvider>
        <ChatRealtimeProvider>
          <App />
        </ChatRealtimeProvider>
      </AuthProvider>
    </AppErrorBoundary>
  </StrictMode>,
);
