import { useSyncExternalStore } from "react";

import { bootStore } from "../lib/bootStore";

/** `true` quando lo splash di avvio ha il via libera per andarsene (o ha esaurito il tempo massimo). Non torna mai a `false`. */
export function useBootReady(): boolean {
  return useSyncExternalStore(bootStore.subscribe, bootStore.getReady, bootStore.getReady);
}
