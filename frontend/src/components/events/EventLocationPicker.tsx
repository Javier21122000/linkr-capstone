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

import { getMapTilerStyleUrl, MILAN_CENTER, MILAN_INITIAL_ZOOM } from "../../lib/map";

setWorkerUrl(workerUrl);

type MapStatus = "loading" | "ready" | "missing-key" | "error";

interface EventLocationPickerProps {
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly disabled: boolean;
  readonly onChange: (latitude: number, longitude: number) => void;
}

export function EventLocationPicker({ latitude, longitude, disabled, onChange }: EventLocationPickerProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markerRef = useRef<Marker | null>(null);
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
      map.addControl(new AttributionControl({ compact: true }), "bottom-right");
      map.on("load", () => {
        loaded = true;
        if (!disposed) setStatus("ready");
      });
      map.on("click", ({ lngLat }) => {
        if (!disabledRef.current) onChangeRef.current(lngLat.lat, lngLat.lng);
      });
      map.on("error", () => {
        if (!disposed && !loaded) setStatus("error");
      });
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
      const marker = new Marker({ color: "#b9301d", draggable: !disabled })
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
