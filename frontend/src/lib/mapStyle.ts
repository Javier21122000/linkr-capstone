import type { StyleSwapOptions, TransformStyleFunction } from "maplibre-gl";

import { MAP_MIN_ZOOM } from "./map";

/**
 * Cartografia essenziale di LINKR.
 *
 * Lo stile MapTiler `streets-v4` resta quello remoto, condiviso e invariato: qui lo si riceve (una sola richiesta, la stessa di prima)
 * e lo si alleggerisce lato client prima che la mappa lo usi. Strade, parchi, acque, quartieri, stazioni e pochi riferimenti
 * urbani restano; negozi, ristoranti, benzinai, parcheggi, arredo stradale, edifici 3D ed etichette di mezzo mondo no, così i marker
 * degli eventi sono l'unica cosa che chiama l'occhio.
 *
 * Reversibile senza toccare il codice: `VITE_MAP_STYLE=full` restituisce lo stile MapTiler completo.
 */
type MapStyle = Parameters<TransformStyleFunction>[1];

/**
 * Forma comune a tutti i layer che qui interessa. L'unione di `LayerSpecification` ha un tipo per ogni tipo di layer
 * (e ogni tipo le sue proprietà): per filtrare e ritoccare per id basta questa vista, e il risultato resta uno stile valido.
 */
interface MapLayer {
  readonly id: string;
  readonly type: string;
  readonly minzoom?: number;
  readonly maxzoom?: number;
  readonly filter?: unknown;
  readonly paint?: Readonly<Record<string, unknown>>;
}

export type MapStyleMode = "essential" | "full";

export function getMapStyleMode(): MapStyleMode {
  return import.meta.env.VITE_MAP_STYLE?.trim().toLowerCase() === "full" ? "full" : "essential";
}

/** Nomi dei layer di `streets-v4` da non mostrare. Un nome che MapTiler dovesse rinominare semplicemente non toglie più nulla. */
const REMOVED_LAYERS: ReadonlySet<string> = new Set([
  // Dettagli di strada e di marciapiede, visibili solo a zoom molto alti
  "Building number", "Oneway", "Pathway labels", "Cycleway labels", "Highway junctions", "Road shields",
  "Zebra crossing", "Traffic light", "Street furniture", "Tree", "Tree name",
  // Punti di interesse commerciali o di servizio
  "Shopping", "Mall", "Food", "Accommodation", "Healthcare", "Education", "Sport", "Public", "Religion",
  "Transport", "Parking special", "Parking", "Parking space special",
  // Trasporti e luoghi che a Milano non servono
  "Airport gate labels", "Aerialway station", "Aerialway", "Ferry terminal", "Ferry labels", "Ferry line",
  "Roller coaster", "Roller coaster labels", "Heliport", "Pier", "Pier road",
  "Military", "Military outline", "Military label", "Quarry", "Ice", "Zoo",
  // Etichette e confini di scala continentale o nazionale: con zoom ≥ 9 su Milano sono solo rumore
  "Country labels", "Disputed country labels", "Continent labels", "Archipelago labels", "Island labels",
  "State labels z2", "State labels z7", "State labels z9", "Ocean labels",
  "Country border", "Disputed border", "Sub border z2", "Sub border z7", "Sub border z9",
]);

/** Dei punti di interesse culturali e turistici restano solo i riferimenti che orientano (musei, teatri, monumenti, castelli). */
const CULTURE_CLASSES = ["arts_centre", "exhibition_centre", "gallery", "monument", "museum", "theatre"];
const TOURISM_CLASSES = ["attraction", "castle", "viewpoint"];

function namedPointsOf(classes: readonly string[]): unknown {
  return ["all", ["==", ["geometry-type"], "Point"], ["has", "name"], ["match", ["get", "class"], classes, true, false]];
}

/**
 * Autostrade e strade principali di MapTiler sono arancio e giallo saturi: sulla mappa competono con i marker corallo.
 * Si smorza solo la saturazione (la tonalità resta riconoscibile), e solo per queste strade.
 */
const MAJOR_ROAD_LAYER = /^(Highway|Major road)/;
const MAJOR_ROAD_SATURATION = 0.55;
const HSL_COLOR = /^hsla?\(\s*([\d.]+)\s*,\s*([\d.]+)%\s*,\s*([\d.]+)%\s*(?:,\s*([\d.]+)\s*)?\)$/;

function desaturateColors(value: unknown, factor: number): unknown {
  if (typeof value === "string") {
    const match = HSL_COLOR.exec(value);
    if (!match) return value;
    const [, hue, saturation, lightness, alpha] = match;
    const next = `${(Number(saturation) * factor).toFixed(1)}%`;
    return alpha === undefined ? `hsl(${hue}, ${next}, ${lightness}%)` : `hsla(${hue}, ${next}, ${lightness}%, ${alpha})`;
  }
  if (Array.isArray(value)) return value.map((item) => desaturateColors(item, factor));
  // Le funzioni di zoom del formato vecchio (`{ stops: [[zoom, colore], …] }`) sono oggetti: stessa visita.
  if (typeof value === "object" && value !== null) {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, desaturateColors(item, factor)]));
  }
  return value;
}

/** Fermate dell'autobus: sono centinaia in città, compaiono solo quando la mappa è abbastanza vicina da leggerle. */
const BUS_STOP_MIN_ZOOM = 16;
/** Senza l'estrusione 3D gli edifici restano come impronta piatta anche oltre zoom 15, un po' più marcata da vicino. Beige, come il 3D che sostituiscono. */
const BUILDING_OPACITY = ["interpolate", ["linear"], ["zoom"], 12, 0.2, 13, 0.3, 16, 0.45] as const;
const BUILDING_COLOR = "hsl(40, 12%, 79%)";

function refineLayer(layer: MapLayer): MapLayer {
  switch (layer.id) {
    case "Culture":
      return { ...layer, filter: namedPointsOf(CULTURE_CLASSES) };
    case "Tourism":
      return { ...layer, filter: namedPointsOf(TOURISM_CLASSES) };
    case "Subway station":
      return { ...layer, filter: ["all", ["==", ["geometry-type"], "Point"], ["==", ["get", "subclass"], "subway"]] };
    case "Bus stop":
      return { ...layer, minzoom: BUS_STOP_MIN_ZOOM };
    case "Building": {
      // Questo layer smetteva a zoom 15 perché subentrava "Building 3D": ora è l'unico e deve coprire tutti gli zoom.
      const { maxzoom: _maxzoom, ...rest } = layer;
      return { ...rest, paint: { ...layer.paint, "fill-color": BUILDING_COLOR, "fill-opacity": BUILDING_OPACITY } };
    }
    default:
      if (layer.type === "line" && MAJOR_ROAD_LAYER.test(layer.id)) {
        return { ...layer, paint: desaturateColors(layer.paint, MAJOR_ROAD_SATURATION) as MapLayer["paint"] };
      }
      return layer;
  }
}

function isUselessHere(layer: MapLayer): boolean {
  if (REMOVED_LAYERS.has(layer.id)) return true;
  // 3D, rilievo e dati continui: la mappa è 2D e la camera non si inclina mai.
  if (layer.type === "fill-extrusion" || layer.type === "hillshade" || layer.type === "heatmap") return true;
  // Un layer che finisce prima dello zoom minimo consentito non verrebbe mai disegnato.
  return layer.maxzoom !== undefined && layer.maxzoom <= MAP_MIN_ZOOM;
}

export function toEssentialStyle(style: MapStyle): MapStyle {
  const { terrain: _terrain, sky: _sky, light: _light, ...rest } = style;
  const layers = (style.layers as readonly MapLayer[]).filter((layer) => !isUselessHere(layer)).map(refineLayer);
  return { ...rest, pitch: 0, layers: layers as unknown as MapStyle["layers"] };
}

/** Da passare a `map.setStyle(url, …)`: lo stile completo arriva da MapTiler e viene ridotto prima di essere applicato. */
export function getStyleSwapOptions(): StyleSwapOptions {
  if (getMapStyleMode() === "full") return {};
  return { transformStyle: (_previous, next) => toEssentialStyle(next) };
}
