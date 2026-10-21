import {
  AttributionControl,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import { RotateCcw } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import {
  getMapTilerStyleUrl,
  MAP_MAX_PIXEL_RATIO,
  MAP_MAX_ZOOM,
  MAP_MIN_ZOOM,
  MILAN_CENTER,
  MILAN_INITIAL_ZOOM,
} from "../../lib/map";
import { attachMapRecovery, recoverableFrom } from "../../lib/mapRecovery";
import type { MapHealthState, MapRecovery } from "../../lib/mapRecovery";
import { getStyleSwapOptions } from "../../lib/mapStyle";

setWorkerUrl(workerUrl);

type MapStatus = "loading" | "ready" | "recovering" | "degraded" | "error" | "missing-key";

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
  const [health, setHealth] = useState<MapHealthState>({ phase: "loading", message: null, failure: null });
  const [attempt, setAttempt] = useState(0);
  /** Una per ogni istanza di MapLibre: il segnaposto si rimette sulla mappa ricreata. */
  const [generation, setGeneration] = useState(0);
  const recoveryRef = useRef<MapRecovery | null>(null);

  onChangeRef.current = onChange;
  disabledRef.current = disabled;

  useEffect(() => {
    const container = containerRef.current;
    if (!container || !styleUrl) return;

    let map: MapLibreMap | null = null;
    let recovery: MapRecovery | null = null;

    setHealth({ phase: "loading", message: null, failure: null });
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
      setGeneration((current) => current + 1);
      map.setPixelRatio(Math.min(window.devicePixelRatio || 1, MAP_MAX_PIXEL_RATIO));
      map.touchZoomRotate.disableRotation();
      map.addControl(new NavigationControl({ showCompass: false }), "top-right");
      map.addControl(new AttributionControl({ compact: true }), "bottom-right");
      // Stesso controllo della mappa di Discover: tasselli falliti non lasciano uno sfondo vuoto dichiarato "pronto".
      const watched = map;
      recovery = attachMapRecovery(recoverableFrom(watched), {
        reload: () => watched.setStyle(styleUrl, { ...getStyleSwapOptions(), diff: false }),
        recreate: () => setAttempt((current) => current + 1),
        onChange: setHealth,
        isVisible: () => container.clientWidth > 0 && container.clientHeight > 0,
      });
      recoveryRef.current = recovery;
      map.on("click", ({ lngLat }) => {
        if (!disabledRef.current) onChangeRef.current(lngLat.lat, lngLat.lng);
      });
      map.setStyle(styleUrl, getStyleSwapOptions());
    } catch {
      setHealth({ phase: "error", message: "Il browser non permette di mostrare la mappa.", failure: { kind: "unknown", permanent: true } });
    }

    const resizeObserver = new ResizeObserver(() => map?.resize());
    resizeObserver.observe(container);

    return () => {
      recovery?.dispose();
      if (recoveryRef.current === recovery) recoveryRef.current = null;
      resizeObserver.disconnect();
      markerRef.current?.remove();
      markerRef.current = null;
      map?.remove();
      if (mapRef.current === map) mapRef.current = null;
    };
  }, [styleUrl, attempt]);

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
  }, [disabled, latitude, longitude, generation]);

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

  const retry = useCallback(() => {
    if (recoveryRef.current) recoveryRef.current.retry();
    else setAttempt((current) => current + 1);
  }, []);

  const status: MapStatus = !styleUrl ? "missing-key" : health.phase === "slow" ? "loading" : health.phase;
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

      {status === "loading" || status === "recovering" ? (
        <div className="event-location-picker__status" role="status">
          {status === "recovering" ? "Ricarico la mappa…" : health.phase === "slow" ? "La mappa sta impiegando più del previsto…" : "Caricamento mappa…"}
          {health.phase === "slow" ? <button className="ml-2 font-extrabold text-ink underline" type="button" onClick={retry}>Riprova</button> : null}
        </div>
      ) : null}

      {status === "degraded" ? (
        <div className="event-location-picker__status" role="status">
          {health.message}
          <button className="ml-2 inline-flex items-center gap-1 font-extrabold text-ink" type="button" onClick={retry}>
            <RotateCcw size={13} aria-hidden="true" /> Riprova
          </button>
        </div>
      ) : null}

      {unavailable ? (
        <div className="event-location-picker__fallback" role="status">
          <p className="font-extrabold text-ink">Mappa non disponibile</p>
          <p className="mt-2 text-sm leading-6 text-muted">
            Inserisci manualmente latitudine e longitudine nei campi qui sotto.
          </p>
          {status === "error" && styleUrl ? (
            <button className="button-secondary mx-auto mt-4 inline-flex items-center gap-2" type="button" onClick={retry}>
              <RotateCcw size={16} aria-hidden="true" /> Riprova
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
