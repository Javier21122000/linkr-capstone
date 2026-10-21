import { useRef, useState } from "react";

import { getEventImageUploadError, prepareEventImageFile } from "../lib/eventImages";
import type { UploadedEventImage } from "../lib/eventImages";

interface UseEventCoverOptions {
  /** Aggiorna `imageUrl` nel form che ospita il campo. */
  readonly onUrlChange: (value: string) => void;
  /** Una foto valida è stata scelta: il form può togliere gli errori ormai superati. */
  readonly onFileAccepted: () => void;
}

/**
 * Stato della foto di copertina condiviso dal form completo e da Quick Create: controllo del file,
 * anteprima, sostituzione/rimozione e upload. Il file resta sul dispositivo finché `uploadSelected` non viene chiamato
 * (cioè alla pubblicazione); la validazione dei campi (es. testo alternativo) resta al form.
 */
export function useEventCover({ onUrlChange, onFileAccepted }: UseEventCoverOptions) {
  const revisionRef = useRef(0);
  const [selectedImage, setSelectedImage] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string>();
  const [statusMessage, setStatusMessage] = useState<string>();
  const [uploading, setUploading] = useState(false);
  const [validating, setValidating] = useState(false);

  async function selectFile(file: File) {
    const revision = revisionRef.current + 1;
    revisionRef.current = revision;
    setValidating(true);
    setFileError(undefined);
    setStatusMessage("Controllo immagine in corso…");
    const prepared = await prepareEventImageFile(file);
    if (revisionRef.current !== revision) return;
    setValidating(false);
    setStatusMessage(undefined);
    if (prepared.error || !prepared.file) {
      setFileError(prepared.error ?? "Non è stato possibile leggere questa immagine.");
      setSelectedImage(null);
      return;
    }

    setSelectedImage(prepared.file);
    onFileAccepted();
  }

  function removeImage() {
    revisionRef.current += 1;
    setValidating(false);
    setFileError(undefined);
    setStatusMessage(undefined);
    if (selectedImage) {
      setSelectedImage(null);
      return;
    }
    onUrlChange("");
  }

  function changeUrl(value: string) {
    revisionRef.current += 1;
    setValidating(false);
    setSelectedImage(null);
    setFileError(undefined);
    setStatusMessage(undefined);
    onUrlChange(value);
  }

  /** Carica il file scelto e restituisce l'URL pubblico, oppure `null` (con l'errore in `fileError`) se l'upload fallisce. */
  async function uploadSelected(upload: (file: File) => Promise<UploadedEventImage>): Promise<string | null> {
    if (!selectedImage) return null;
    setUploading(true);
    setFileError(undefined);
    setStatusMessage("Caricamento foto in corso…");
    try {
      const uploaded = await upload(selectedImage);
      onUrlChange(uploaded.imageUrl);
      setSelectedImage(null);
      setStatusMessage("Foto caricata. Salvataggio dell’evento in corso…");
      return uploaded.imageUrl;
    } catch (error) {
      setFileError(getEventImageUploadError(error));
      setStatusMessage(undefined);
      return null;
    } finally {
      setUploading(false);
    }
  }

  /** Azzera tutto (dopo una pubblicazione o uno scarto): un controllo in corso non deve riapplicarsi. */
  function reset() {
    revisionRef.current += 1;
    setSelectedImage(null);
    setFileError(undefined);
    setStatusMessage(undefined);
    setUploading(false);
    setValidating(false);
  }

  /**
   * Il file scelto è stato scartato dai controlli (nessuna foto selezionata): va sostituito, non si può pubblicare.
   * Un upload fallito invece lascia la foto selezionata con il suo errore: si può ritentare così com'è, senza sceglierla di nuovo.
   * È l'unico punto in cui questa regola esiste: il form completo e Quick Create devono usare solo questo valore.
   */
  const fileRejected = Boolean(fileError) && selectedImage === null;

  return {
    selectedImage,
    fileError,
    fileRejected,
    statusMessage,
    uploading,
    validating,
    selectFile,
    removeImage,
    changeUrl,
    uploadSelected,
    reset,
  };
}
