/**
 * Sfondo ambientale delle pagine diverse da Discover.
 *
 * È un fondo cartografico *stilizzato*, derivato dai token del design system (verde dei parchi, azzurro dell'acqua, trama di strade):
 * non contiene dati geografici né ne finge. Costa un elemento fisso con gradienti CSS: nessuna istanza MapLibre in più, nessuna
 * richiesta a MapTiler, nessun elemento interattivo nascosto e nessun costo per frame. La mappa vera resta quella di Discover,
 * montata una volta sola (vedi AppShell): riusarla come sfondo avrebbe obbligato a caricarla anche su una pagina di chat aperta direttamente.
 * L'intensità segue la pagina (`data-ambient` in AppShell); con `prefers-contrast: more` sparisce del tutto.
 */
export function AmbientMap() {
  return <div className="ambient-map" aria-hidden="true" />;
}
