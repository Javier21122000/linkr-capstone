/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_MAPTILER_KEY?: string;
  /** `full` = stile MapTiler completo; qualunque altro valore (o nessuno) = cartografia essenziale di LINKR. */
  readonly VITE_MAP_STYLE?: string;
  /** Origine pubblica del sito (`https://…`) per i link di invito; vuota = quella da cui l'app è aperta (se pubblica). */
  readonly VITE_PUBLIC_SITE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
