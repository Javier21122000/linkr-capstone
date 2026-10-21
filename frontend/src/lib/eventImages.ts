import axios from "axios";

import { apiClient } from "./api";

export const EVENT_IMAGE_MAX_BYTES = 5 * 1024 * 1024;
export const EVENT_IMAGE_ACCEPT = "image/jpeg,image/png,image/webp,.jpg,.jpeg,.png,.webp";

const INTERNAL_EVENT_IMAGE = /^\/api\/uploads\/event-images\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(?:jpg|png|webp)$/;

export interface PreparedEventImage {
  readonly file?: File;
  readonly error?: string;
}

export interface UploadedEventImage {
  readonly imageUrl: string;
  readonly filename: string;
  readonly contentType: string;
  readonly size: number;
}

export function isAllowedEventImageUrl(value: string): boolean {
  return /^https:\/\/\S+$/.test(value) || INTERNAL_EVENT_IMAGE.test(value);
}

export function resolveEventImageUrl(value: string): string {
  if (!value.startsWith("/api/uploads/event-images/")) return value;

  const configuredBase = import.meta.env.VITE_API_BASE_URL?.trim();
  if (!configuredBase) return value;

  try {
    const apiOrigin = new URL(configuredBase, window.location.origin).origin;
    return new URL(value, apiOrigin).toString();
  } catch {
    return value;
  }
}

function detectImageType(bytes: Uint8Array): "image/jpeg" | "image/png" | "image/webp" | null {
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  if (bytes.length >= 8
    && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47
    && bytes[4] === 0x0d && bytes[5] === 0x0a && bytes[6] === 0x1a && bytes[7] === 0x0a) return "image/png";
  if (bytes.length >= 12
    && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46
    && bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return "image/webp";
  return null;
}

export async function prepareEventImageFile(file: File): Promise<PreparedEventImage> {
  if (file.size === 0) return { error: "Scegli un file immagine non vuoto." };
  if (file.size > EVENT_IMAGE_MAX_BYTES) return { error: "La foto non può superare 5 MB." };

  try {
    const header = new Uint8Array(await file.slice(0, 12).arrayBuffer());
    const detectedType = detectImageType(header);
    if (!detectedType) {
      return { error: "Formato non supportato. Usa un’immagine JPEG, PNG o WebP valida." };
    }

    if (file.type.toLowerCase() === detectedType) return { file };
    return {
      file: new File([file], file.name, {
        type: detectedType,
        lastModified: file.lastModified,
      }),
    };
  } catch {
    return { error: "Non riusciamo a leggere questa immagine. Scegli un altro file." };
  }
}

export async function uploadEventImage(accessToken: string, file: File): Promise<UploadedEventImage> {
  const formData = new FormData();
  formData.append("file", file);

  const { data } = await apiClient.post<UploadedEventImage>("/api/uploads/event-images", formData, {
    headers: { Authorization: `Bearer ${accessToken}` },
    timeout: 30_000,
  });
  return data;
}

export function getEventImageUploadError(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) return "Non riusciamo a caricare la foto. Controlla la connessione e riprova.";

    switch (error.response.status) {
      case 400:
        return "Il file non è un’immagine valida oppure è danneggiato. Scegli un’altra foto.";
      case 401:
        return "La sessione non è più valida. Accedi di nuovo prima di caricare la foto.";
      case 413:
        return "La foto supera il limite di 5 MB.";
      case 415:
        return "Formato non supportato. Usa un’immagine JPEG, PNG o WebP.";
      case 429:
        return "Hai effettuato troppi tentativi di caricamento. Riprova più tardi.";
      default:
        if (error.response.status >= 500) return "Il servizio immagini non è disponibile. Riprova tra poco.";
    }
  }

  return "Non è stato possibile caricare la foto. Scegli un altro file o riprova.";
}
