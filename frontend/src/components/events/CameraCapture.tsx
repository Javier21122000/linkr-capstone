import { Camera, ImagePlus, LoaderCircle, RefreshCw, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { createPortal } from "react-dom";

type CameraPhase = "starting" | "ready" | "captured" | "error";

interface CameraCaptureProps {
  readonly open: boolean;
  readonly onClose: () => void;
  readonly onConfirm: (file: File) => void;
  readonly onFallback: () => void;
}

function stopMediaStream(stream: MediaStream | null): void {
  stream?.getTracks().forEach((track) => track.stop());
}

function getCameraError(error: unknown): string {
  if (error instanceof DOMException) {
    if (error.name === "NotAllowedError" || error.name === "SecurityError") {
      return "Permesso fotocamera negato. Abilitalo nelle impostazioni del browser e riprova.";
    }
    if (error.name === "NotFoundError" || error.name === "OverconstrainedError") {
      return "Non è stata trovata una fotocamera disponibile su questo dispositivo.";
    }
    if (error.name === "NotReadableError" || error.name === "AbortError") {
      return "La fotocamera è già in uso oppure non può essere avviata in questo momento.";
    }
  }
  return "Non è stato possibile avviare la fotocamera. Controlla i permessi e riprova.";
}

export function CameraCapture({ open, onClose, onConfirm, onFallback }: CameraCaptureProps) {
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const captureRevisionRef = useRef(0);
  const [phase, setPhase] = useState<CameraPhase>("starting");
  const [error, setError] = useState<string>();
  const [capturedFile, setCapturedFile] = useState<File | null>(null);
  const [capturedPreviewUrl, setCapturedPreviewUrl] = useState<string>();
  const [restartKey, setRestartKey] = useState(0);

  function stopCamera() {
    stopMediaStream(streamRef.current);
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
  }

  function closeCamera() {
    captureRevisionRef.current += 1;
    stopCamera();
    setCapturedFile(null);
    setError(undefined);
    onClose();
  }

  useEffect(() => {
    if (!open) return undefined;
    const previouslyFocused = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    closeButtonRef.current?.focus();
    return () => previouslyFocused?.focus();
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;

    setPhase("starting");
    setError(undefined);
    setCapturedFile(null);

    async function startCamera() {
      if (!window.isSecureContext) {
        setPhase("error");
        setError("La fotocamera richiede una connessione sicura HTTPS o localhost.");
        return;
      }
      if (!navigator.mediaDevices?.getUserMedia) {
        setPhase("error");
        setError("Questo browser non supporta l’accesso diretto alla fotocamera.");
        return;
      }

      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: "environment" } },
          audio: false,
        });
        if (cancelled) {
          stopMediaStream(stream);
          return;
        }
        streamRef.current = stream;
        const video = videoRef.current;
        if (!video) throw new Error("Camera preview unavailable");
        video.srcObject = stream;
        await video.play();
        if (!cancelled) setPhase("ready");
      } catch (cameraError) {
        stopCamera();
        if (!cancelled) {
          setPhase("error");
          setError(getCameraError(cameraError));
        }
      }
    }

    void startCamera();
    return () => {
      cancelled = true;
      stopCamera();
    };
  }, [open, restartKey]);

  useEffect(() => {
    if (!capturedFile) {
      setCapturedPreviewUrl(undefined);
      return undefined;
    }
    const objectUrl = URL.createObjectURL(capturedFile);
    setCapturedPreviewUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [capturedFile]);

  if (!open) return null;

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") {
      event.preventDefault();
      closeCamera();
      return;
    }
    if (event.key !== "Tab") return;
    const buttons = dialogRef.current?.querySelectorAll<HTMLButtonElement>("button:not(:disabled)");
    if (!buttons?.length) return;
    const first = buttons[0];
    const last = buttons[buttons.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function captureFrame() {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas || video.videoWidth === 0 || video.videoHeight === 0) {
      setPhase("error");
      setError("L’anteprima non è ancora pronta. Riprova tra un momento.");
      return;
    }

    const maxDimension = 2_048;
    const scale = Math.min(1, maxDimension / Math.max(video.videoWidth, video.videoHeight));
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    const context = canvas.getContext("2d");
    if (!context) {
      setPhase("error");
      setError("Non è stato possibile acquisire la foto. Riprova.");
      return;
    }
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    const captureRevision = captureRevisionRef.current + 1;
    captureRevisionRef.current = captureRevision;
    canvas.toBlob((blob) => {
      if (captureRevisionRef.current !== captureRevision) return;
      if (!blob) {
        setPhase("error");
        setError("Non è stato possibile acquisire la foto. Riprova.");
        return;
      }
      stopCamera();
      setCapturedFile(new File([blob], `foto-evento-${Date.now()}.jpg`, {
        type: "image/jpeg",
        lastModified: Date.now(),
      }));
      setPhase("captured");
    }, "image/jpeg", 0.9);
  }

  function retryCamera() {
    captureRevisionRef.current += 1;
    stopCamera();
    setCapturedFile(null);
    setError(undefined);
    setRestartKey((current) => current + 1);
  }

  function confirmPhoto() {
    if (!capturedFile) return;
    onConfirm(capturedFile);
    closeCamera();
  }

  function useFilePicker() {
    closeCamera();
    onFallback();
  }

  // Portale su body: dentro un pannello con transform/filter un elemento `fixed` si posizionerebbe rispetto al pannello, non alla finestra.
  return createPortal(
    <div className="dialog-backdrop" role="presentation">
      <div
        ref={dialogRef}
        className="dialog-panel max-h-[calc(100dvh-2rem)] overflow-y-auto sm:!w-[min(100%,42rem)]"
        role="dialog"
        aria-modal="true"
        aria-labelledby="camera-capture-title"
        aria-describedby="camera-capture-description"
        onKeyDown={handleKeyDown}
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="section-kicker">Fotocamera</p>
            <h2 id="camera-capture-title" className="mt-1 text-2xl font-extrabold tracking-[-0.04em] text-ink">Scatta la copertina</h2>
          </div>
          <button ref={closeButtonRef} className="icon-button shrink-0" type="button" onClick={closeCamera} aria-label="Chiudi fotocamera">
            <X size={19} aria-hidden="true" />
          </button>
        </div>
        <p id="camera-capture-description" className="mt-3 text-sm leading-6 text-muted">La foto resta sul dispositivo finché non la confermi e salvi l’evento.</p>

        <div className="relative mt-5 aspect-[4/3] overflow-hidden rounded-2xl bg-ink text-white">
          {phase === "captured" && capturedPreviewUrl ? (
            <img className="size-full object-contain" src={capturedPreviewUrl} alt="Anteprima della foto appena scattata" />
          ) : (
            <video ref={videoRef} className="size-full object-contain" autoPlay muted playsInline aria-label="Anteprima fotocamera" />
          )}
          {phase === "starting" ? (
            <div className="absolute inset-0 grid place-items-center bg-ink/85 text-center" role="status">
              <span><LoaderCircle className="mx-auto animate-spin motion-reduce:animate-none" size={28} aria-hidden="true" /><span className="mt-3 block text-sm font-bold">Avvio fotocamera…</span></span>
            </div>
          ) : null}
          {phase === "ready" ? <p className="absolute left-3 top-3 rounded-full bg-ink/75 px-3 py-2 text-xs font-extrabold" role="status">Fotocamera attiva</p> : null}
          {phase === "error" ? (
            <div className="absolute inset-0 grid place-items-center bg-ink px-6 text-center" role="alert">
              <span><Camera className="mx-auto" size={30} aria-hidden="true" /><span className="mt-3 block text-sm font-bold leading-6">{error}</span></span>
            </div>
          ) : null}
        </div>
        <canvas ref={canvasRef} hidden />

        <div className="mt-5 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
          <button className="button-secondary inline-flex items-center" type="button" onClick={closeCamera}>Annulla</button>
          {phase === "ready" ? (
            <button className="button-primary inline-flex items-center gap-2" type="button" onClick={captureFrame}><Camera size={18} aria-hidden="true" /> Scatta</button>
          ) : null}
          {phase === "captured" ? (
            <>
              <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={retryCamera}><RefreshCw size={17} aria-hidden="true" /> Riprova</button>
              <button className="button-primary inline-flex items-center" type="button" onClick={confirmPhoto}>Usa foto</button>
            </>
          ) : null}
          {phase === "error" ? (
            <>
              <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={useFilePicker}><ImagePlus size={17} aria-hidden="true" /> Carica foto</button>
              <button className="button-primary inline-flex items-center gap-2" type="button" onClick={retryCamera}><RefreshCw size={17} aria-hidden="true" /> Riprova</button>
            </>
          ) : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}
