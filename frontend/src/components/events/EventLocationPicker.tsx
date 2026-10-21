import {
  AttributionControl,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { useEffect, useRef, useState } from "react";

import {
  getMapTilerStyleUrl,
  isFatalMapError,
  MAP_MAX_PIXEL_RATIO,
  MAP_MAX_ZOOM,
  MAP_MIN_ZOOM,
  MILAN_CENTER,
  MILAN_INITIAL_ZOOM,
} from "../../lib/map";
import { getStyleSwapOptions } from "../../lib/mapStyle";

setWorkerUrl(workerUrl);

type MapStatus = "loading" | "ready" | "missing-key" | "error";

interface EventLocationPickerProps {
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly focusRevision: number;
  readonly disabled: boolean;
  readonly onChange: (latitude: number, longitude: number) => void;
}

export function EventLocationPicker({ latitude, longitude, focusRevision, disabled, onChange }: EventLocationPickerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<Marker | null>(null);
  const handledFocusRevisionRef = useRef(0);
  const onChangeRef = useRef(onChange);
  const disabledRef = useRef(disabled);
  const styleUrl = getMapTilerStyleUrl();
  const [status, setStatus] = useState<MapStatus>(() => styleUrl ? "loading" : "missing-key");

  onChangeRef.current = onChange;
  disabledRef.current = disabled;

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !styleUrl) {
      setStatus("missing-key");
      return;
    }

    let map: MapLibreMap | null = null;
    let loaded = false;
    let disposed = false;

    setStatus("loading");
    try {
      map = new MapLibreMap({
        container,
        center: MILAN_CENTER,
        zoom: MILAN_INITIAL_ZOOM,
        minZoom: MAP_MIN_ZOOM,
        maxZoom: MAP_MAX_ZOOM,
        attributionControl: false,
        dragRotate: false,
        pitchWithRotate: false,
        maxPitch: 0,
      });
      mapRef.current = map;
      map.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAP_MAX_PIXEL_RATIO));
      map.touchZoomRotate.disableRotation();
      map.addControl(new NavigationControl({ showCompass: false }), "top-right");
      map.addControl(new AttributionControl({ compact: true }), "bottom-right");
      map.on("load", () => {
        loaded = true;
        if (!disposed) setStatus("ready");
      });
      map.on("click", ({ lngLat }) => {
        if (!disabledRef.current) onChangeRef.current(lngLat.lat, lngLat.lng);
      });
      map.on("error", (event) => {
        if (!disposed && !loaded && isFatalMapError(event)) setStatus("error");
      });
      map.setStyle(styleUrl, getStyleSwapOptions());
    } catch {
      setStatus("error");
    }

    const resizeObserver = new ResizeObserver(() => map?.resize());
    resizeObserver.observe(container);

    return () => {
      disposed = true;
      resizeObserver.disconnect();
      markerRef.current?.remove();
      markerRef.current = null;
      map?.remove();
      if (mapRef.current === map) mapRef.current = null;
    };
  }, [styleUrl]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || latitude === null || longitude === null) {
      markerRef.current?.remove();
      markerRef.current = null;
      return;
    }

    if (!markerRef.current) {
      const marker = new Marker({ color: "#e84d2f", draggable: !disabled })
        .setLngLat([longitude, latitude])
        .addTo(map);
      marker.on("dragend", () => {
        if (disabledRef.current) return;
        const point = marker.getLngLat();
        onChangeRef.current(point.lat, point.lng);
      });
      markerRef.current = marker;
      return;
    }

    markerRef.current.setLngLat([longitude, latitude]);
    markerRef.current.setDraggable(!disabled);
  }, [disabled, latitude, longitude]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map
      || focusRevision === 0
      || handledFocusRevisionRef.current === focusRevision
      || latitude === null
      || longitude === null) return;
    handledFocusRevisionRef.current = focusRevision;
    map.easeTo({
      center: [longitude, latitude],
      zoom: Math.max(map.getZoom(), 15),
      duration: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? 0 : 650,
    });
  }, [focusRevision, latitude, longitude]);

  const unavailable = status === "missing-key" || status === "error";

  return (
    <div className="event-location-picker" data-status={status}>
      <div
        ref={containerRef}
        className="event-location-picker__canvas"
        role={unavailable ? undefined : "region"}
        aria-label={unavailable ? undefined : "Scegli il punto dell’evento sulla mappa"}
        aria-hidden={unavailable}
      />

      {status === "loading" ? (
        <div className="event-location-picker__status" role="status">Caricamento mappa…</div>
      ) : null}

      {unavailable ? (
        <div className="event-location-picker__fallback" role="status">
          <p className="font-extrabold text-ink">Mappa non disponibile</p>
          <p className="mt-2 text-sm leading-6 text-muted">
            Inserisci manualmente latitudine e longitudine nei campi qui sotto.
          </p>
        </div>
      ) : null}
    </div>
  );
}
