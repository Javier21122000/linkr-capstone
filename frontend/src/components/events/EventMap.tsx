import {
  AttributionControl,
  Map as MapLibreMap,
  Marker,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { Focus, Minus, Plus, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { categoryColors, unknownCategoryColor } from "../../lib/eventPresentation";
import {
  getMapTilerStyleUrl,
  hasValidCoordinates,
  MAP_MAX_PIXEL_RATIO,
  MAP_MAX_ZOOM,
  MAP_MIN_ZOOM,
  MILAN_CENTER,
  MILAN_INITIAL_ZOOM,
} from "../../lib/map";
import type { MapPickState, PickedPoint } from "../../lib/map";
import {
  applyCameraTarget,
  planEventsFraming,
  planSelectionFraming,
} from "../../lib/mapCamera";
import { nextRetryDelay } from "../../lib/mapHealth";
import { coversMap, hasRenderedOnce, mapLoadingMessage } from "../../lib/mapLoading";
import { attachMapRecovery, recoverableFrom } from "../../lib/mapRecovery";
import type { MapHealthState, MapRecovery } from "../../lib/mapRecovery";
import { canvasNeedsResize, MAP_SIZE_CHECK_DELAY_MS } from "../../lib/mapSize";
import { getStyleSwapOptions } from "../../lib/mapStyle";
import { prefersReducedMotion } from "../../lib/motion";
import type { EventSummary } from "../../types/event";
import type { ParticipationStatus } from "../../types/participation";
import { useLeaving } from "../../hooks/useLeaving";
import { LinkrSymbol } from "../ui/BrandMark";
import { IconButton } from "../ui/IconButton";
import { LinkrSplash } from "../ui/LinkrSplash";
import { EventMarkerPin } from "./EventMarkerPin";

setWorkerUrl(workerUrl);

/**
 * Stato visibile della mappa (`data-status`): `loading` (anche se lenta), `ready`, `recovering` (tentativo di recupero in corso o in attesa),
 * `degraded` (cartografia incompleta dopo i tentativi: marker e lista restano usabili), `error` (non può partire) e `missing-key`.
 */
export type MapStatus = "loading" | "ready" | "recovering" | "degraded" | "error" | "missing-key";

/** Camera da conservare se la mappa va ricreata: né il viewport né il lavoro dell'utente si perdono per un errore temporaneo. */
interface SavedCamera {
  readonly center: [number, number];
  readonly zoom: number;
}

interface MarkerEntry {
  readonly marker: Marker;
  readonly element: HTMLButtonElement;
  readonly event: EventSummary;
}

/** Selezione corrente. `source` dice da dove arriva (marker o lista); la camera si comporta allo stesso modo in entrambi i casi. */
export interface MapSelection {
  readonly id: string;
  readonly source: "map" | "list";
}

interface EventMapProps {
  /** Eventi da mostrare, già filtrati per categoria. */
  readonly events: readonly EventSummary[];
  /** `false` finché l'elenco non è caricato: mai inquadrare dati parziali o vuoti perché non ancora arrivati. */
  readonly eventsReady: boolean;
  /** Cambia solo per una scelta dell'utente (es. categoria) e chiede una nuova inquadratura. Mai per un refresh dei dati. */
  readonly viewKey: string;
  readonly selection: MapSelection | null;
  /** La mappa resta montata tra le navigazioni: `false` quando Discover non è la pagina visibile. */
  readonly active: boolean;
  readonly onSelectEvent: (eventId: string) => void;
  /** Modalità "scegli un punto" (Quick Create): `null` = spenta. Il tocco sulla mappa propone un punto, non crea nulla. */
  readonly pick?: MapPickState | null;
  readonly onPickPoint?: (point: PickedPoint) => void;
  /** Stato personale dell'utente autenticato per evento (vuoto da anonimi): decide il simbolo sul marker. */
  readonly participations?: ReadonlyMap<string, ParticipationStatus>;
  /** Stato della mappa a ogni cambio (`loading`, `ready`, `recovering`, `degraded`, `error`, `missing-key`): Discover lo usa per sapere quando è pronto. */
  readonly onStatusChange?: (status: MapStatus) => void;
}

/** Richiesta di inquadratura in attesa di dati: si esegue una sola volta e poi si consuma. */
interface FramingRequest {
  /** `initial`: primo caricamento (non deve scavalcare una mappa già mossa dall'utente). `view`: scelta esplicita dell'utente. */
  readonly reason: "initial" | "view";
}

function createMarkerElement(event: EventSummary, onSelect: () => void): HTMLButtonElement {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "event-marker";
  button.dataset.selected = "false";
  button.dataset.category = event.category;
  button.style.setProperty("--event-category-color", categoryColors[event.category] ?? unknownCategoryColor);
  button.setAttribute("aria-label", `Seleziona l’evento ${event.title}`);
  button.setAttribute("aria-pressed", "false");
  button.title = event.title;
  button.addEventListener("click", onSelect);
  return button;
}

const NO_PARTICIPATIONS: ReadonlyMap<string, ParticipationStatus> = new Map();

const participationSuffix: Record<ParticipationStatus, string> = {
  GOING: "parteciperai",
  INTERESTED: "ti interessa",
};

function isSameMarkerEvent(a: EventSummary, b: EventSummary): boolean {
  return a.id === b.id
    && a.title === b.title
    && a.category === b.category
    && a.latitude === b.latitude
    && a.longitude === b.longitude
    && a.imageUrl === b.imageUrl;
}

export function EventMap({ events, eventsReady, viewKey, selection, active, onSelectEvent, pick = null, onPickPoint, onStatusChange, participations = NO_PARTICIPATIONS }: EventMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markersRef = useRef<Map<string, MarkerEntry>>(new Map());
  const [markerEntries, setMarkerEntries] = useState<readonly MarkerEntry[]>([]);
  const onSelectRef = useRef(onSelectEvent);
  const eventsRef = useRef(events);
  const activeRef = useRef(active);
  const selectionRef = useRef(selection);
  const pickingRef = useRef(pick !== null);
  const onPickPointRef = useRef(onPickPoint);
  const pickMarkerRef = useRef<Marker | null>(null);
  const framingRef = useRef<FramingRequest | null>({ reason: "initial" });
  const viewKeyRef = useRef(viewKey);
  /** `true` quando la camera non è più quella scelta dall'app (gesto dell'utente o selezione): niente inquadrature automatiche sopra. */
  const adjustedRef = useRef(false);
  const [cameraAdjusted, setCameraAdjusted] = useState(false);
  const [health, setHealth] = useState<MapHealthState>({ phase: "loading", message: null, failure: null });
  /** Cambia quando la mappa va ricreata (errore sullo stile, contesto WebGL perso, "Riprova"). */
  const [attempt, setAttempt] = useState(0);
  /** Una per ogni istanza di MapLibre creata: marker, selezione e punto proposto si rimettono sulla mappa nuova. */
  const [generation, setGeneration] = useState(0);
  /** Dopo i tentativi automatici sullo stile senza esito: solo allora compare la schermata "non disponibile". */
  const [fatalExhausted, setFatalExhausted] = useState(false);
  const recoveryRef = useRef<MapRecovery | null>(null);
  const lastCameraRef = useRef<SavedCamera | null>(null);
  const styleAttemptsRef = useRef(0);
  const styleUrl = getMapTilerStyleUrl();
  const hasMappableEvents = useMemo(() => events.some(hasValidCoordinates), [events]);

  onSelectRef.current = onSelectEvent;
  eventsRef.current = events;
  activeRef.current = active;
  selectionRef.current = selection;
  pickingRef.current = pick !== null;
  onPickPointRef.current = onPickPoint;

  const markAdjusted = useCallback(() => {
    adjustedRef.current = true;
    setCameraAdjusted(true);
  }, []);

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !styleUrl) return;

    let map: MapLibreMap | null = null;
    let recovery: MapRecovery | null = null;
    const savedCamera = lastCameraRef.current;

    setHealth({ phase: "loading", message: null, failure: null });
    setFatalExhausted(false);

    try {
      // Lo stile si applica dopo i listener (sotto): arriva già alleggerito, senza un primo frame con la cartografia completa.
      map = new MapLibreMap({
        container,
        // Una mappa ricreata riparte da dove l'utente l'aveva lasciata; una nuova parte da Milano.
        center: savedCamera?.center ?? MILAN_CENTER,
        zoom: savedCamera?.zoom ?? MILAN_INITIAL_ZOOM,
        minZoom: MAP_MIN_ZOOM,
        maxZoom: MAP_MAX_ZOOM,
        attributionControl: false,
        dragRotate: false,
        pitchWithRotate: false,
        // Mappa 2D: nessuna inclinazione, né col gesto a due dita né da tastiera.
        maxPitch: 0,
      });
      mapRef.current = map;
      setGeneration((current) => current + 1);
      map.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAP_MAX_PIXEL_RATIO));
      map.touchZoomRotate.disableRotation();
      map.addControl(new AttributionControl({ compact: false }), "top-left");

      if (savedCamera) {
        // Mappa ricreata: la camera è quella di prima, niente inquadratura automatica sopra.
        framingRef.current = null;
      } else {
        // Una mappa nuova parte da Milano: serve una prima inquadratura sugli eventi.
        framingRef.current = { reason: "initial" };
        adjustedRef.current = false;
        setCameraAdjusted(false);
      }

      // Con tasselli falliti MapLibre dichiara comunque `load` e `idle` (verificato): lo stato "pronta" lo decide il controllore,
      // che controlla che la mappa disegni davvero e riprova (sulla stessa istanza) se un errore temporaneo l'ha lasciata vuota.
      const mapForRecovery = map;
      recovery = attachMapRecovery(recoverableFrom(mapForRecovery), {
        reload: () => mapForRecovery.setStyle(styleUrl, { ...getStyleSwapOptions(), diff: false }),
        recreate: () => setAttempt((current) => current + 1),
        onChange: setHealth,
        isVisible: () => activeRef.current && container.clientWidth > 0 && container.clientHeight > 0,
      });
      recoveryRef.current = recovery;
      map.on("moveend", () => {
        const center = mapForRecovery.getCenter();
        lastCameraRef.current = { center: [center.lng, center.lat], zoom: mapForRecovery.getZoom() };
      });
      // Solo i gesti reali portano un `originalEvent` (trascinamento, pinch, doppio click, tastiera): le transizioni
      // avviate dall'app (inquadrature, selezioni) no, quindi non si scambiano per navigazione manuale.
      // Lo zoom con la rotellina è l'eccezione: MapLibre emette `movestart` senza `originalEvent`, quindi si ascolta `wheel`.
      map.on("movestart", (event) => {
        if (event.originalEvent) markAdjusted();
      });
      map.on("wheel", markAdjusted);
      // `click` di MapLibre non scatta dopo un trascinamento: pan e pinch non scelgono mai un punto. Fuori dalla modalità non fa nulla.
      map.on("click", (event) => {
        if (!pickingRef.current) return;
        // Un tocco sul segnaposto stesso non è un nuovo punto: il suo ancoraggio sposterebbe il punto di qualche pixel.
        if (event.originalEvent.target instanceof Element && event.originalEvent.target.closest(".maplibregl-marker")) return;
        onPickPointRef.current?.({ latitude: event.lngLat.lat, longitude: event.lngLat.lng });
      });
      map.setStyle(styleUrl, getStyleSwapOptions());
    } catch {
      // Il costruttore fallisce senza WebGL: non è un errore temporaneo, quindi nessun tentativo automatico.
      setHealth({ phase: "error", message: "Il browser non permette di mostrare la mappa.", failure: { kind: "unknown", permanent: true } });
    }

    // Con Discover nascosto il container misura 0×0: si ridimensiona solo quando torna visibile.
    const resizeObserver = new ResizeObserver(() => {
      if (container.clientWidth > 0 && container.clientHeight > 0) map?.resize();
    });
    resizeObserver.observe(container);

    return () => {
      recovery?.dispose();
      if (recoveryRef.current === recovery) recoveryRef.current = null;
      resizeObserver.disconnect();
      markersRef.current.forEach(({ marker }) => marker.remove());
      markersRef.current.clear();
      pickMarkerRef.current?.remove();
      pickMarkerRef.current = null;
      setMarkerEntries([]);
      map?.remove();
      if (mapRef.current === map) mapRef.current = null;
    };
  }, [styleUrl, markAdjusted, attempt]);

  // Tornando su Discover la mappa deve avere la dimensione del suo contenitore. Di norma lo garantisce il ResizeObserver, ma non sempre (vedi
  // `lib/mapSize.ts`): un controllo dopo il ritorno la riallinea comunque. Solo `resize()`: stessa istanza, la camera, i marker e la selezione non si toccano.
  useEffect(() => {
    if (!active) return undefined;
    const timeoutId = window.setTimeout(() => {
      const map = mapRef.current;
      const container = containerRef.current;
      if (!map || !container) return;
      const canvas = map.getCanvas();
      const canvasSize = { width: canvas.clientWidth, height: canvas.clientHeight };
      if (canvasNeedsResize(canvasSize, { width: container.clientWidth, height: container.clientHeight })) map.resize();
    }, MAP_SIZE_CHECK_DELAY_MS);
    return () => window.clearTimeout(timeoutId);
  }, [active]);

  // Stile non scaricabile: due tentativi automatici (con attesa), poi la schermata "non disponibile". Mai per errori permanenti.
  useEffect(() => {
    if (health.phase === "ready") styleAttemptsRef.current = 0;
    if (health.phase !== "error" || !health.failure) return undefined;
    const delay = nextRetryDelay(styleAttemptsRef.current, health.failure);
    if (delay === null) {
      setFatalExhausted(true);
      return undefined;
    }
    styleAttemptsRef.current += 1;
    const timeoutId = window.setTimeout(() => setAttempt((current) => current + 1), delay);
    return () => window.clearTimeout(timeoutId);
  }, [health]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    // Diff per id: i marker invariati restano gli stessi (niente sfarfallio né animazione ripetuta).
    const next = new Map<string, MarkerEntry>();
    events.filter(hasValidCoordinates).forEach((event) => {
      const existing = markersRef.current.get(event.id);
      if (existing && isSameMarkerEvent(existing.event, event)) {
        next.set(event.id, existing);
        return;
      }
      existing?.marker.remove();
      const element = createMarkerElement(event, () => onSelectRef.current(event.id));
      const marker = new Marker({ element, anchor: "bottom" })
        .setLngLat([event.longitude, event.latitude])
        .addTo(map);
      next.set(event.id, { marker, element, event });
    });
    markersRef.current.forEach((entry, eventId) => {
      if (!next.has(eventId)) entry.marker.remove();
    });
    markersRef.current = next;
    setMarkerEntries([...next.values()]);
  }, [events, generation]);

  // Segnaposto del punto proposto: trascinabile, stesso aspetto di quello del form completo. Segue sempre `pick.point`.
  const pickLatitude = pick?.point?.latitude ?? null;
  const pickLongitude = pick?.point?.longitude ?? null;
  useEffect(() => {
    const map = mapRef.current;
    if (!map || pickLatitude === null || pickLongitude === null) {
      pickMarkerRef.current?.remove();
      pickMarkerRef.current = null;
      return;
    }

    if (pickMarkerRef.current) {
      pickMarkerRef.current.setLngLat([pickLongitude, pickLatitude]);
      return;
    }
    const marker = new Marker({ color: "#e84d2f", draggable: true })
      .setLngLat([pickLongitude, pickLatitude])
      .addTo(map);
    marker.on("dragend", () => {
      const point = marker.getLngLat();
      onPickPointRef.current?.({ latitude: point.lat, longitude: point.lng });
    });
    pickMarkerRef.current = marker;
  }, [pickLatitude, pickLongitude, generation]);

  const selectedEventId = selection?.id ?? null;

  useEffect(() => {
    markersRef.current.forEach(({ element }, eventId) => {
      const selected = eventId === selectedEventId;
      element.dataset.selected = String(selected);
      element.setAttribute("aria-pressed", String(selected));
    });
    // Anche quando i marker cambiano: un marker ricreato da un refresh deve ereditare la selezione corrente.
  }, [selectedEventId, markerEntries]);

  // Lo stato personale arriva dopo la creazione dei marker (e cambia quando si risponde a un evento): si aggiornano gli attributi
  // sullo stesso elemento, senza ricrearlo, e il nome accessibile dice la stessa cosa del simbolo.
  useEffect(() => {
    markersRef.current.forEach(({ element, event }, eventId) => {
      const status = participations.get(eventId);
      if (status) element.dataset.participation = status;
      else delete element.dataset.participation;
      const suffix = status ? ` (${participationSuffix[status]})` : "";
      element.setAttribute("aria-label", `Seleziona l’evento ${event.title}${suffix}`);
      element.title = `${event.title}${suffix}`;
    });
  }, [participations, markerEntries]);

  // Selezione: ogni nuovo evento (marker o lista, stesso comportamento) viene portato al centro dell'area libera dagli overlay.
  // `easeTo` sostituisce l'animazione in corso, quindi con selezioni rapide vince l'ultima. Riselezionare l'evento già attivo mentre la camera
  // lo sta raggiungendo non riparte; a camera ferma si ricentra solo se nel frattempo è stato spostato (vedi `planSelectionFraming`).
  const framedSelectionRef = useRef<string | null>(null);
  useEffect(() => {
    if (!selection) {
      framedSelectionRef.current = null;
      return;
    }
    const map = mapRef.current;
    const container = containerRef.current;
    if (!activeRef.current || !map || !container) return;

    const selectedEvent = eventsRef.current.find((event) => event.id === selection.id);
    if (!selectedEvent || !hasValidCoordinates(selectedEvent)) return;
    if (framedSelectionRef.current === selection.id && map.isMoving()) return;

    framedSelectionRef.current = selection.id;
    const target = planSelectionFraming(map, container, selectedEvent);
    if (!target) return;

    applyCameraTarget(map, target, { animate: true, kind: "selection" });
    markAdjusted();
  }, [selection, markAdjusted]);

  // Una nuova `viewKey` (categoria scelta dall'utente) apre una richiesta di inquadratura.
  useEffect(() => {
    if (viewKeyRef.current === viewKey) return;
    viewKeyRef.current = viewKey;
    framingRef.current = { reason: "view" };
  }, [viewKey]);

  // Inquadratura: eseguita una sola volta per richiesta, e solo con i dati che le appartengono (`eventsReady`).
  // Refresh silenziosi, ritorno da altre pagine e resize non aprono richieste, quindi non spostano mai la camera.
  useEffect(() => {
    const request = framingRef.current;
    if (!request || !eventsReady || !active) return;

    const map = mapRef.current;
    const container = containerRef.current;
    if (!map || !container || container.clientWidth === 0 || container.clientHeight === 0) return;

    framingRef.current = null;
    // Un evento appena portato in vista (es. "Visualizza sulla mappa", che toglie anche il filtro) vince sull'inquadratura generale.
    if (request.reason === "view" && selectionRef.current) return;
    // Categoria senza eventi: stato vuoto, la mappa resta dov'è.
    const mappableEvents = events.filter(hasValidCoordinates);
    if (mappableEvents.length === 0) return;
    // Se l'utente ha già mosso la mappa prima che arrivassero i dati, la sua scelta vince sull'inquadratura iniziale.
    if (request.reason === "initial" && adjustedRef.current) return;

    const target = planEventsFraming(map, container, mappableEvents);
    if (!target) return;

    applyCameraTarget(map, target, { animate: map.loaded(), kind: "framing" });
    adjustedRef.current = false;
    setCameraAdjusted(false);
  }, [events, eventsReady, active, viewKey]);

  const reframe = useCallback(() => {
    const map = mapRef.current;
    const container = containerRef.current;
    if (!map || !container) return;

    const target = planEventsFraming(map, container, eventsRef.current.filter(hasValidCoordinates));
    if (!target) return;

    applyCameraTarget(map, target, { animate: true, kind: "framing" });
    adjustedRef.current = false;
    setCameraAdjusted(false);
  }, []);

  const zoomBy = useCallback((direction: "in" | "out") => {
    const map = mapRef.current;
    if (!map) return;

    markAdjusted();
    const options = { duration: prefersReducedMotion() ? 0 : 220 };
    if (direction === "in") map.zoomIn(options);
    else map.zoomOut(options);
  }, [markAdjusted]);

  const retry = useCallback(() => {
    styleAttemptsRef.current = 0;
    if (recoveryRef.current) recoveryRef.current.retry();
    else setAttempt((current) => current + 1);
  }, []);

  const status: MapStatus = !styleUrl
    ? "missing-key"
    : fatalExhausted
      ? "error"
      : health.phase === "slow" ? "loading" : health.phase === "error" ? "recovering" : health.phase;
  const unavailable = status === "missing-key" || status === "error";

  const onStatusChangeRef = useRef(onStatusChange);
  onStatusChangeRef.current = onStatusChange;
  useEffect(() => {
    onStatusChangeRef.current?.(status);
  }, [status]);

  // Overlay LINKR: solo finché la mappa non ha mai disegnato (primo caricamento, o "Riprova" dopo un errore iniziale). Quando è pronta
  // svanisce in 240 ms; dopo, un recupero o una camera che si muove non la coprono mai (restano la pillola e l'avviso qui sotto).
  const [renderedOnce, setRenderedOnce] = useState(false);
  useEffect(() => {
    if (hasRenderedOnce(status)) setRenderedOnce(true);
  }, [status]);
  const overlay = useLeaving(coversMap(status, renderedOnce) ? true : null, 240);
  const slow = health.phase === "slow";

  return (
    <div className="event-map" data-status={status} data-picking={pick !== null ? "true" : undefined}>
      <div
        ref={containerRef}
        className="event-map__canvas"
        role={unavailable ? undefined : "region"}
        aria-label={unavailable ? undefined : "Mappa degli eventi a Milano"}
        aria-hidden={unavailable}
      />

      {markerEntries.map(({ element, event }) =>
        createPortal(<EventMarkerPin event={event} selected={event.id === selectedEventId} participation={participations.get(event.id)} />, element, event.id),
      )}

      {!unavailable && !coversMap(status, renderedOnce) && (
        <div className="map-controls" data-map-inset="right">
          <div className="map-controls__zoom glass" role="group" aria-label="Zoom della mappa">
            <IconButton variant="bare" label="Ingrandisci la mappa" onClick={() => zoomBy("in")}>
              <Plus size={20} strokeWidth={2.4} aria-hidden="true" />
            </IconButton>
            <span className="map-controls__divider" aria-hidden="true" />
            <IconButton variant="bare" label="Riduci la mappa" onClick={() => zoomBy("out")}>
              <Minus size={20} strokeWidth={2.4} aria-hidden="true" />
            </IconButton>
          </div>

          {/* Compare solo dopo una navigazione manuale o una selezione: riporta la vista sugli eventi mostrati. */}
          {cameraAdjusted && hasMappableEvents && (
            <IconButton
              variant="glass"
              className="map-controls__reframe content-reveal"
              label="Inquadra di nuovo gli eventi"
              onClick={reframe}
            >
              <Focus size={19} strokeWidth={2.3} aria-hidden="true" />
            </IconButton>
          )}
        </div>
      )}

      {overlay.shown ? (
        <LinkrSplash variant="panel" leaving={overlay.leaving} message={mapLoadingMessage(status, slow)}>
          {slow ? <button className="button-secondary" type="button" onClick={retry}>Riprova</button> : null}
        </LinkrSplash>
      ) : null}

      {(status === "loading" || status === "recovering") && renderedOnce && (
        <div className="event-map__loading" role="status">
          <span className="event-map__loading-dot" aria-hidden="true" />
          {status === "recovering" ? "Ricarico la mappa…" : health.phase === "slow" ? "La mappa sta impiegando più del previsto…" : "Caricamento mappa"}
          {health.phase === "slow" ? <button className="event-map__retry" type="button" onClick={retry}>Riprova</button> : null}
        </div>
      )}

      {/* Cartografia incompleta dopo i tentativi: marker e lista restano usabili, e l'utente decide se riprovare. Non è un errore mascherato. */}
      {status === "degraded" && (
        <div className="event-map__loading event-map__notice" role="status">
          <span className="event-map__notice-text">{health.message}</span>
          <button className="event-map__retry" type="button" onClick={retry}>
            <RotateCcw size={14} aria-hidden="true" /> Riprova
          </button>
        </div>
      )}

      {unavailable && (
        <div className="event-map__fallback" role="status">
          <LinkrSymbol className="mb-3 size-12" />
          <p className="text-lg font-extrabold text-ink">Mappa non disponibile</p>
          <p className="mt-2 max-w-sm text-sm leading-6 text-muted">
            {status === "missing-key"
              ? "La basemap non è configurata in questo ambiente. Puoi continuare dalla lista eventi."
              : `${health.message ?? "Il provider cartografico non risponde."} Gli eventi restano disponibili dalla lista.`}
          </p>
          {status === "error" ? (
            <button className="button-secondary mt-4 inline-flex items-center gap-2" type="button" onClick={retry}>
              <RotateCcw size={16} aria-hidden="true" /> Riprova
            </button>
          ) : null}
        </div>
      )}

      {styleUrl && status !== "error" && (
        <a
          className="maptiler-logo-attribution"
          href="https://www.maptiler.com"
          target="_blank"
          rel="noreferrer"
          aria-label="MapTiler"
        >
          <img src="https://api.maptiler.com/resources/logo.svg" alt="MapTiler" />
        </a>
      )}
    </div>
  );
}
