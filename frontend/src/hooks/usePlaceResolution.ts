import { useCallback, useEffect, useRef, useState } from "react";

import { getPlaceSearchErrorMessage, reverseGeocodeMilan } from "../lib/geocoding";
import { completePlace, needsReverse } from "../lib/placeDetails";
import type { CompletedPlace, ParsedPlace } from "../lib/placeDetails";

export type PlaceResolutionStatus = "idle" | "resolving" | "failed";

export interface PlaceResolutionTarget {
  readonly latitude: number;
  readonly longitude: number;
  /** Ciò che si sa già del luogo: per un punto sulla mappa niente (`venueName` vuoto, `landmark` falso). */
  readonly place: Pick<ParsedPlace, "address" | "addressQuality" | "neighbourhood" | "landmark" | "venueName">;
  /** `search`: luogo scelto dai suggerimenti. `map`: punto toccato sulla mappa. */
  readonly origin: "search" | "map";
}

/**
 * Dopo che l'utente ha scelto un luogo una volta sola, recupera da solo indirizzo e quartiere mancanti con una sola richiesta
 * di reverse geocoding (e nessuna se il suggerimento è già completo). Il chiamante riceve ciò che si è trovato e lo applica ai
 * campi ancora vuoti: qui non si scrive nulla e una nuova scelta annulla quella in corso, quindi una risposta tardiva non arriva mai.
 */
export function usePlaceResolution(onResolved: (target: { readonly latitude: number; readonly longitude: number }, completed: CompletedPlace) => void) {
  const [status, setStatus] = useState<PlaceResolutionStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const controllerRef = useRef<AbortController | null>(null);
  const targetRef = useRef<PlaceResolutionTarget | null>(null);
  const onResolvedRef = useRef(onResolved);
  onResolvedRef.current = onResolved;

  const cancel = useCallback(() => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    targetRef.current = null;
    setStatus("idle");
    setError(null);
  }, []);

  const resolve = useCallback(async (target: PlaceResolutionTarget) => {
    controllerRef.current?.abort();
    controllerRef.current = null;
    targetRef.current = target;
    setError(null);

    const apply = (completed: CompletedPlace) => onResolvedRef.current({ latitude: target.latitude, longitude: target.longitude }, completed);

    if (target.origin === "search" && !needsReverse(target.place)) {
      setStatus("idle");
      return;
    }

    const controller = new AbortController();
    controllerRef.current = controller;
    setStatus("resolving");
    try {
      const reverse = await reverseGeocodeMilan(target.latitude, target.longitude, controller.signal);
      if (controller.signal.aborted) return;
      apply(completePlace(target.place, reverse, target.origin));
      setStatus("idle");
    } catch (reason) {
      if (controller.signal.aborted) return;
      // Senza la risposta si usa comunque ciò che il luogo già dichiara (es. il nome di un parco come indirizzo descrittivo).
      apply(completePlace(target.place, null, target.origin));
      setStatus("failed");
      setError(getPlaceSearchErrorMessage(reason));
    } finally {
      if (controllerRef.current === controller) controllerRef.current = null;
    }
  }, []);

  const retry = useCallback(() => {
    if (targetRef.current) void resolve(targetRef.current);
  }, [resolve]);

  useEffect(() => () => controllerRef.current?.abort(), []);

  return { status, error, resolve, retry, cancel };
}
