/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_MAPTILER_KEY?: string;
  /** `full` = stile MapTiler completo; qualunque altro valore (o nessuno) = cartografia essenziale di LINKR. */
  readonly VITE_MAP_STYLE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
