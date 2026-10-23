import { useSyncExternalStore } from "react";

import { bootStore, splashGone } from "../lib/bootStore";

/** `true` quando lo splash di avvio ha il via libera per andarsene (o ha esaurito il tempo massimo). Non torna mai a `false`. */
export function useBootReady(): boolean {
  return useSyncExternalStore(bootStore.subscribe, bootStore.getReady, bootStore.getReady);
}

/** `true` quando lo splash di avvio è già uscito (o non c'è mai stato): da qui la pagina è libera e visibile. Non torna mai a `false`. */
export function useSplashGone(): boolean {
  return useSyncExternalStore(splashGone.subscribe, splashGone.get, splashGone.get);
}
