import { Camera, ImagePlus, Trash2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { ChangeEvent } from "react";

import { EVENT_IMAGE_ACCEPT, isAllowedEventImageUrl, resolveEventImageUrl } from "../../lib/eventImages";
import { FormField } from "../auth/FormField";
import { CameraCapture } from "./CameraCapture";

interface EventCoverFieldProps {
  readonly imageUrl: string;
  readonly imageAlt: string;
  readonly selectedFile: File | null;
  readonly fileError?: string;
  readonly urlError?: string;
  readonly statusMessage?: string;
  readonly disabled: boolean;
  readonly onFileSelect: (file: File) => void;
  readonly onRemove: () => void;
  readonly onUrlChange: (value: string) => void;
}

export function EventCoverField({
  imageUrl,
  imageAlt,
  selectedFile,
  fileError,
  urlError,
  statusMessage,
  disabled,
  onFileSelect,
  onRemove,
  onUrlChange,
}: EventCoverFieldProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [localPreviewUrl, setLocalPreviewUrl] = useState<string | null>(null);
  const [cameraOpen, setCameraOpen] = useState(false);

  useEffect(() => {
    if (!selectedFile) {
      setLocalPreviewUrl(null);
      return undefined;
    }

    const objectUrl = URL.createObjectURL(selectedFile);
    setLocalPreviewUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [selectedFile]);

  const trimmedUrl = imageUrl.trim();
  const savedPreviewUrl = trimmedUrl && isAllowedEventImageUrl(trimmedUrl)
    ? resolveEventImageUrl(trimmedUrl)
    : null;
  const previewUrl = localPreviewUrl ?? savedPreviewUrl;
  const hasCover = Boolean(selectedFile || trimmedUrl);
  const removeLabel = selectedFile && trimmedUrl ? "Annulla sostituzione" : "Rimuovi foto";

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (file) onFileSelect(file);
  }

  return (
    <div className="space-y-5">
      <div>
        <span className="form-label">Foto di copertina (opzionale)</span>
        <div className="mt-2 overflow-hidden rounded-2xl border border-line bg-canvas">
          <div className="relative aspect-[16/9] min-h-44">
            {previewUrl ? (
              <img
                className="absolute inset-0 size-full object-cover"
                src={previewUrl}
                alt={imageAlt.trim() || "Anteprima della foto di copertina"}
              />
            ) : (
              <div className="absolute inset-0 grid place-items-center px-5 text-center text-muted">
                <span>
                  <ImagePlus className="mx-auto" size={30} strokeWidth={1.7} aria-hidden="true" />
                  <span className="mt-3 block text-sm font-bold">Nessuna foto selezionata</span>
                </span>
              </div>
            )}
          </div>
          <div className="border-t border-line p-4">
            <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
              <button
                className="button-secondary inline-flex items-center gap-2"
                type="button"
                onClick={() => fileInputRef.current?.click()}
                disabled={disabled}
                aria-describedby={fileError ? "event-cover-file-error" : "event-cover-file-hint"}
              >
                <ImagePlus size={17} aria-hidden="true" />
                {hasCover ? "Sostituisci foto" : "Carica foto"}
              </button>
              <button
                className="button-secondary inline-flex items-center gap-2"
                type="button"
                onClick={() => setCameraOpen(true)}
                disabled={disabled}
                aria-describedby={fileError ? "event-cover-file-error" : "event-cover-file-hint"}
              >
                <Camera size={17} aria-hidden="true" /> Scatta foto
              </button>
              {hasCover ? (
                <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={onRemove} disabled={disabled}>
                  <Trash2 size={17} aria-hidden="true" /> {removeLabel}
                </button>
              ) : null}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept={EVENT_IMAGE_ACCEPT}
              onChange={handleFileChange}
              disabled={disabled}
              hidden
            />
            {selectedFile ? (
              <p className="mt-3 truncate text-xs font-bold text-ink">Selezionata: {selectedFile.name}</p>
            ) : null}
            {fileError ? (
              <p id="event-cover-file-error" className="field-error mt-3" role="alert" tabIndex={-1}>{fileError}</p>
            ) : (
              <p id="event-cover-file-hint" className="mt-3 text-xs leading-5 text-muted">JPEG, PNG o WebP, massimo 5 MB. La foto viene caricata quando salvi l’evento.</p>
            )}
            {statusMessage ? <p className="mt-3 text-xs font-bold text-linkr-strong" role="status" aria-live="polite">{statusMessage}</p> : null}
          </div>
        </div>
      </div>

      <FormField
        id="imageUrl"
        label="Oppure URL immagine"
        type="url"
        value={imageUrl}
        onChange={(event) => onUrlChange(event.target.value)}
        error={urlError}
        hint="Puoi usare un indirizzo pubblico HTTPS. Se scegli un file, la foto selezionata ha la precedenza."
        maxLength={500}
        placeholder="https://…"
        disabled={disabled}
      />
      <CameraCapture
        open={cameraOpen}
        onClose={() => setCameraOpen(false)}
        onConfirm={onFileSelect}
        onFallback={() => fileInputRef.current?.click()}
      />
    </div>
  );
}
