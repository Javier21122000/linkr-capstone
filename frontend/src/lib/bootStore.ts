import { createBootStore, createFlagStore } from "./appBoot";

/**
 * Unico stato di avvio dell'app (vedi `appBoot.ts`). Si sa già dall'indirizzo se si atterra su Discover: lì lo splash aspetta anche la
 * prima risposta degli eventi; su ogni altra pagina basta il router.
 */
export const bootStore = createBootStore(window.location.pathname === "/");

/** Si accende quando lo splash di avvio è uscito del tutto: da lì in poi nulla copre più la pagina (l'onboarding aspetta questo, non il solo "pronto"). */
export const splashGone = createFlagStore();
