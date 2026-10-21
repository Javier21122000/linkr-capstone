interface ImageAltSource {
  readonly title: string;
  readonly venueName: string;
  readonly imageAlt: string;
}

function cleanImageFileName(value: string): string {
  const withoutQuery = value.split(/[?#]/, 1)[0] ?? "";
  const lastSegment = withoutQuery.split("/").pop() ?? "";
  let decoded = lastSegment;
  try {
    decoded = decodeURIComponent(lastSegment);
  } catch {
    // Un nome non codificato resta comunque un fallback valido.
  }
  return decoded
    .replace(/\.[a-z0-9]{2,5}$/i, "")
    .replace(/[-_]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** Testo personalizzato, poi titolo/luogo; il nome file è soltanto l'ultima rete di sicurezza. */
export function resolveEventImageAlt(values: ImageAltSource, imageUrl: string, fileName?: string): string {
  const custom = values.imageAlt.trim();
  if (custom) return custom.slice(0, 200);
  if (!imageUrl) return "";

  const title = values.title.trim();
  const place = values.venueName.trim();
  if (title && place) return `Copertina di ${title} presso ${place}`.slice(0, 200);
  if (title) return `Copertina di ${title}`.slice(0, 200);
  if (place) return `Evento presso ${place}`.slice(0, 200);

  const fallback = cleanImageFileName(fileName || imageUrl);
  return (fallback ? `Immagine ${fallback}` : "Immagine dell’evento").slice(0, 200);
}
