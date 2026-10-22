import { createBootStore } from "./appBoot";

/**
 * Unico stato di avvio dell'app (vedi `appBoot.ts`). Si sa già dall'indirizzo se si atterra su Discover: lì lo splash aspetta anche la
 * prima risposta degli eventi; su ogni altra pagina basta il router.
 */
export const bootStore = createBootStore(window.location.pathname === "/");
