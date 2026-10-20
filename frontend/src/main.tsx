import "@fontsource-variable/manrope";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { App } from "./App";
import { AuthProvider } from "./context/AuthContext";
import { ChatRealtimeProvider } from "./context/ChatRealtimeContext";
import "./styles/index.css";

const rootElement = document.getElementById("root");

if (!rootElement) {
  throw new Error("Root element not found");
}

createRoot(rootElement).render(
  <StrictMode>
    <AuthProvider>
      <ChatRealtimeProvider>
        <App />
      </ChatRealtimeProvider>
    </AuthProvider>
  </StrictMode>,
);
