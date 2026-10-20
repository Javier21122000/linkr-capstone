import {
  AttributionControl,
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { useEffect, useRef, useState } from "react";

import { categoryColors, categoryLabels, unknownCategoryColor } from "../../lib/eventPresentation";
import { getMapTilerStyleUrl, hasValidCoordinates, MILAN_CENTER, MILAN_INITIAL_ZOOM } from "../../lib/map";
import type { EventSummary } from "../../types/event";

setWorkerUrl(workerUrl);

type MapStatus = "loading" | "ready" | "missing-key" | "error";

interface MarkerEntry {
  readonly marker: Marker;
  readonly element: HTMLButtonElement;
}

interface EventMapProps {
  readonly events: readonly EventSummary[];
  readonly selectedEventId: string | null;
  readonly desktopListOpen: boolean;
  readonly onSelectEvent: (eventId: string) => void;
}

const reduceMotionQuery = "(prefers-reduced-motion: reduce)";
const SMALL_GROUP_MAX_ZOOM = 14.5;
const LARGE_GROUP_MAX_ZOOM = 13.5;

function animationDuration(): number {
  return window.matchMedia(reduceMotionQuery).matches ? 0 : 200;
}

function desktopPanelInset(container: HTMLElement): number {
  const rootFontSize = Number.parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  return Math.min(29 * rootFontSize, container.clientWidth * 0.46);
}

function cameraPadding(container: HTMLElement, desktopListOpen: boolean) {
  const compact = container.clientWidth < 768;
  const side = compact ? 40 : 64;

  return {
    top: compact ? 96 : 84,
    right: desktopListOpen ? desktopPanelInset(container) : side,
    bottom: compact ? 128 : 64,
    left: side,
  };
}

function selectedEventOffset(container: HTMLElement, desktopListOpen: boolean): [number, number] {
  return [desktopListOpen ? -desktopPanelInset(container) / 2 : 0, container.clientWidth < 768 ? -72 : -48];
}

function createMarkerElement(event: EventSummary, onSelect: () => void): HTMLButtonElement {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "event-map-marker";
  button.dataset.selected = "false";
  button.dataset.category = event.category;
  button.style.setProperty("--event-category-color", categoryColors[event.category] ?? unknownCategoryColor);
  button.setAttribute("aria-label", `Seleziona l’evento ${event.title}`);
  button.setAttribute("aria-pressed", "false");
  button.title = event.title;
  button.addEventListener("click", onSelect);

  const pin = document.createElement("span");
  pin.className = "event-map-marker__pin";

  const fallback = document.createElement("span");
  fallback.className = "event-map-marker__fallback";
  fallback.textContent = categoryLabels[event.category].slice(0, 1);
  pin.append(fallback);

  if (event.imageUrl) {
    const image = document.createElement("img");
    image.src = event.imageUrl;
    image.alt = "";
    image.loading = "lazy";
    image.className = "event-map-marker__image";
    image.addEventListener("error", () => image.remove(), { once: true });
    pin.append(image);
  }

  const label = document.createElement("span");
  label.className = "event-map-marker__label";
  label.textContent = event.title;

  button.append(pin, label);
  return button;
}

export function EventMap({ events, selectedEventId, desktopListOpen, onSelectEvent }: EventMapProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markersRef = useRef<Map<string, MarkerEntry>>(new Map());
  const onSelectRef = useRef(onSelectEvent);
  const [status, setStatus] = useState<MapStatus>(() => getMapTilerStyleUrl() ? "loading" : "missing-key");
  const styleUrl = getMapTilerStyleUrl();

  onSelectRef.current = onSelectEvent;

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
      map.addControl(new NavigationControl({ showCompass: false }), "top-right");
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

    const resizeObserver = new ResizeObserver(() => map?.resize());
    resizeObserver.observe(container);

    return () => {
      disposed = true;
      resizeObserver.disconnect();
      markersRef.current.forEach(({ marker }) => marker.remove());
      markersRef.current.clear();
      map?.remove();
      if (mapRef.current === map) mapRef.current = null;
    };
  }, [styleUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;

    markersRef.current.forEach(({ marker }) => marker.remove());
    markersRef.current.clear();

    events.filter(hasValidCoordinates).forEach((event) => {
      const element = createMarkerElement(event, () => onSelectRef.current(event.id));
      const marker = new Marker({ element, anchor: "bottom" })
        .setLngLat([event.longitude, event.latitude])
        .addTo(map);
      markersRef.current.set(event.id, { marker, element });
    });
  }, [events]);

  useEffect(() => {
    markersRef.current.forEach(({ element }, eventId) => {
      const selected = eventId === selectedEventId;
      element.dataset.selected = String(selected);
      element.setAttribute("aria-pressed", String(selected));
    });
  }, [selectedEventId]);

  useEffect(() => {
    const map = mapRef.current;
    const container = containerRef.current;
    if (!map || !container) return;

    const selectedEvent = selectedEventId
      ? events.find((event) => event.id === selectedEventId)
      : undefined;

    if (selectedEvent && hasValidCoordinates(selectedEvent)) {
      map.easeTo({
        center: [selectedEvent.longitude, selectedEvent.latitude],
        zoom: Math.max(map.getZoom(), 13),
        offset: selectedEventOffset(container, desktopListOpen),
        duration: animationDuration(),
        essential: true,
      });
      return;
    }

    const mappableEvents = events.filter(hasValidCoordinates);
    if (mappableEvents.length === 0) return;

    if (mappableEvents.length === 1) {
      const [event] = mappableEvents;
      map.easeTo({
        center: [event.longitude, event.latitude],
        zoom: 14.25,
        offset: selectedEventOffset(container, desktopListOpen),
        duration: animationDuration(),
        essential: true,
      });
      return;
    }

    const bounds = new LngLatBounds();
    mappableEvents.forEach((event) => bounds.extend([event.longitude, event.latitude]));

    map.fitBounds(bounds, {
      padding: cameraPadding(container, desktopListOpen),
      maxZoom: mappableEvents.length <= 3 ? SMALL_GROUP_MAX_ZOOM : LARGE_GROUP_MAX_ZOOM,
      duration: animationDuration(),
      essential: true,
    });
  }, [desktopListOpen, events, selectedEventId]);

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

      {status === "loading" && (
        <div className="event-map__loading" role="status">
          <span className="event-map__loading-dot" aria-hidden="true" />
          Caricamento mappa
        </div>
      )}

      {unavailable && (
        <div className="event-map__fallback" role="status">
          <div className="event-map__fallback-mark" aria-hidden="true">L</div>
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
