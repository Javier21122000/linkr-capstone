import {
  AttributionControl,
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { Minus, Plus } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

import { categoryColors, unknownCategoryColor } from "../../lib/eventPresentation";
import { getMapTilerStyleUrl, hasValidCoordinates, MILAN_CENTER, MILAN_INITIAL_ZOOM } from "../../lib/map";
import type { EventSummary } from "../../types/event";
import { LinkrSymbol } from "../ui/BrandMark";
import { IconButton } from "../ui/IconButton";
import { EventMarkerPin } from "./EventMarkerPin";

setWorkerUrl(workerUrl);

type MapStatus = "loading" | "ready" | "missing-key" | "error";

interface MarkerEntry {
  readonly marker: Marker;
  readonly element: HTMLButtonElement;
  readonly event: EventSummary;
}

interface EventMapProps {
  readonly events: readonly EventSummary[];
  readonly selectedEventId: string | null;
  /** La mappa resta montata tra le navigazioni: `false` quando Discover non è la pagina visibile. */
  readonly active: boolean;
  /** Cambia solo quando serve riquadrare gli eventi (es. nuova categoria): mai per un semplice refresh dei dati. */
  readonly fitKey: string;
  readonly onSelectEvent: (eventId: string) => void;
}

const reduceMotionQuery = "(prefers-reduced-motion: reduce)";
const SMALL_GROUP_MAX_ZOOM = 14.5;
const LARGE_GROUP_MAX_ZOOM = 13.5;
const COMPACT_MAP_WIDTH = 768;

function animationDuration(): number {
  return window.matchMedia(reduceMotionQuery).matches ? 0 : 220;
}

/** Margini lasciati liberi da header, filtri, anteprima e navigazione sovrapposti alla mappa. */
function cameraPadding(container: HTMLElement) {
  const compact = container.clientWidth < COMPACT_MAP_WIDTH;
  const side = compact ? 40 : 72;
  // Su schermi bassi (es. landscape) il padding non deve superare lo spazio disponibile.
  const maxVertical = container.clientHeight * 0.3;

  return {
    top: Math.min(compact ? 156 : 96, maxVertical),
    right: side,
    bottom: Math.min(compact ? 232 : 140, maxVertical),
    left: side,
  };
}

function selectedEventOffset(container: HTMLElement): [number, number] {
  return [0, container.clientWidth < COMPACT_MAP_WIDTH ? -84 : -56];
}

function isSameMarkerEvent(a: EventSummary, b: EventSummary): boolean {
  return a.id === b.id
    && a.title === b.title
    && a.category === b.category
    && a.latitude === b.latitude
    && a.longitude === b.longitude
    && a.imageUrl === b.imageUrl;
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

export function EventMap({ events, selectedEventId, active, fitKey, onSelectEvent }: EventMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markersRef = useRef<Map<string, MarkerEntry>>(new Map());
  const [markerEntries, setMarkerEntries] = useState<readonly MarkerEntry[]>([]);
  const onSelectRef = useRef(onSelectEvent);
  const eventsRef = useRef(events);
  const activeRef = useRef(active);
  const fittedKeyRef = useRef<string | null>(null);
  const [status, setStatus] = useState<MapStatus>(() => getMapTilerStyleUrl() ? "loading" : "missing-key");
  const styleUrl = getMapTilerStyleUrl();

  onSelectRef.current = onSelectEvent;
  eventsRef.current = events;
  activeRef.current = active;

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !styleUrl) {
      setStatus("missing-key");
      return;
    }

    let map: MapLibreMap | null = null;
    let hasLoaded = false;
    let disposed = false;

    setStatus("loading");

    try {
      map = new MapLibreMap({
        container,
        style: styleUrl,
        center: MILAN_CENTER,
        zoom: MILAN_INITIAL_ZOOM,
        minZoom: 9,
        maxZoom: 18,
        attributionControl: false,
        dragRotate: false,
        pitchWithRotate: false,
      });
      mapRef.current = map;
      map.touchZoomRotate.disableRotation();
      map.addControl(new AttributionControl({ compact: false }), "top-left");

      map.on("load", () => {
        hasLoaded = true;
        if (!disposed) setStatus("ready");
      });
      map.on("error", () => {
        if (!disposed && !hasLoaded) setStatus("error");
      });
    } catch {
      setStatus("error");
    }

    // Con Discover nascosto il container misura 0×0: si ridimensiona solo quando torna visibile.
    const resizeObserver = new ResizeObserver(() => {
      if (container.clientWidth > 0 && container.clientHeight > 0) map?.resize();
    });
    resizeObserver.observe(container);

    return () => {
      disposed = true;
      resizeObserver.disconnect();
      markersRef.current.forEach(({ marker }) => marker.remove());
      markersRef.current.clear();
      setMarkerEntries([]);
      map?.remove();
      if (mapRef.current === map) mapRef.current = null;
    };
  }, [styleUrl]);

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
  }, [events]);

  useEffect(() => {
    markersRef.current.forEach(({ element }, eventId) => {
      const selected = eventId === selectedEventId;
      element.dataset.selected = String(selected);
      element.setAttribute("aria-pressed", String(selected));
    });
    // Anche quando i marker cambiano: un marker ricreato da un refresh deve ereditare la selezione corrente.
  }, [selectedEventId, markerEntries]);

  // Selezione: porta l'evento scelto in vista. Non dipende dai dati, così un refresh non sposta la mappa.
  useEffect(() => {
    const map = mapRef.current;
    const container = containerRef.current;
    if (!selectedEventId || !activeRef.current || !map || !container) return;

    const selectedEvent = eventsRef.current.find((event) => event.id === selectedEventId);
    if (!selectedEvent || !hasValidCoordinates(selectedEvent)) return;

    map.easeTo({
      center: [selectedEvent.longitude, selectedEvent.latitude],
      zoom: Math.max(map.getZoom(), 13),
      offset: selectedEventOffset(container),
      duration: animationDuration(),
      essential: true,
    });
  }, [selectedEventId]);

  // Riquadro iniziale: una sola volta per `fitKey`. Tornare su Discover o aggiornare i dati non tocca la vista.
  useEffect(() => {
    const map = mapRef.current;
    const container = containerRef.current;
    if (!active || !map || !container || fittedKeyRef.current === fitKey) return;
    if (container.clientWidth === 0 || container.clientHeight === 0) return;

    const mappableEvents = events.filter(hasValidCoordinates);
    if (mappableEvents.length === 0) return;
    fittedKeyRef.current = fitKey;

    if (mappableEvents.length === 1) {
      const [event] = mappableEvents;
      map.easeTo({
        center: [event.longitude, event.latitude],
        zoom: 14.25,
        offset: selectedEventOffset(container),
        duration: animationDuration(),
        essential: true,
      });
      return;
    }

    const bounds = new LngLatBounds();
    mappableEvents.forEach((event) => bounds.extend([event.longitude, event.latitude]));

    map.fitBounds(bounds, {
      padding: cameraPadding(container),
      maxZoom: mappableEvents.length <= 3 ? SMALL_GROUP_MAX_ZOOM : LARGE_GROUP_MAX_ZOOM,
      duration: animationDuration(),
      essential: true,
    });
  }, [active, events, fitKey]);

  const unavailable = status === "missing-key" || status === "error";

  return (
    <div className="event-map" data-status={status}>
      <div
        ref={containerRef}
        className="event-map__canvas"
        role={unavailable ? undefined : "region"}
        aria-label={unavailable ? undefined : "Mappa degli eventi a Milano"}
        aria-hidden={unavailable}
      />

      {markerEntries.map(({ element, event }) =>
        createPortal(<EventMarkerPin event={event} selected={event.id === selectedEventId} />, element, event.id),
      )}

      {!unavailable && (
        <div className="map-controls glass glass--strong" role="group" aria-label="Zoom della mappa">
          <IconButton variant="bare" label="Ingrandisci la mappa" onClick={() => mapRef.current?.zoomIn({ duration: animationDuration() })}>
            <Plus size={20} strokeWidth={2.4} aria-hidden="true" />
          </IconButton>
          <span className="map-controls__divider" aria-hidden="true" />
          <IconButton variant="bare" label="Riduci la mappa" onClick={() => mapRef.current?.zoomOut({ duration: animationDuration() })}>
            <Minus size={20} strokeWidth={2.4} aria-hidden="true" />
          </IconButton>
        </div>
      )}

      {status === "loading" && (
        <div className="event-map__loading" role="status">
          <span className="event-map__loading-dot" aria-hidden="true" />
          Caricamento mappa
        </div>
      )}

      {unavailable && (
        <div className="event-map__fallback" role="status">
          <LinkrSymbol className="mb-3 size-12" />
          <p className="text-lg font-extrabold text-ink">Mappa non disponibile</p>
          <p className="mt-2 max-w-sm text-sm leading-6 text-muted">
            {status === "missing-key"
              ? "La basemap non è configurata in questo ambiente. Puoi continuare dalla lista eventi."
              : "Il provider cartografico non risponde. Gli eventi restano disponibili dalla lista."}
          </p>
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
