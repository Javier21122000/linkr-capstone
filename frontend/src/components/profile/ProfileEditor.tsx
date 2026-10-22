import { Camera, Pencil, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";

import { useAuth } from "../../context/AuthContext";
import {
  EVENT_IMAGE_ACCEPT,
  getEventImageUploadError,
  isInternalUploadedImageUrl,
  prepareEventImageFile,
  uploadEventImage,
} from "../../lib/eventImages";
import {
  BIO_MAX,
  DISPLAY_NAME_MAX,
  getProfileErrorMessage,
  getProfileFieldErrors,
  updateProfile,
} from "../../lib/profile";
import { cleanBio, cleanDisplayName, validateProfileDraft } from "../../lib/profileValidation";
import type { UserResponse } from "../../types/auth";
import { UserAvatar } from "../auth/UserAvatar";

interface ProfileEditorProps {
  readonly user: UserResponse;
}

type FieldErrors = Readonly<{ displayName?: string; bio?: string; avatarUrl?: string }>;

export function ProfileEditor({ user }: ProfileEditorProps) {
  const { authorizedRequest, updateCurrentUser } = useAuth();
  const [editing, setEditing] = useState(false);
  const [displayName, setDisplayName] = useState(user.displayName);
  const [bio, setBio] = useState(user.bio ?? "");
  const [avatarUrl, setAvatarUrl] = useState<string | null>(user.avatarUrl);
  const [avatarFile, setAvatarFile] = useState<File | null>(null);
  const [avatarPreviewUrl, setAvatarPreviewUrl] = useState<string | null>(null);
  const [validatingImage, setValidatingImage] = useState(false);
  const [saving, setSaving] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [requestError, setRequestError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const savingRef = useRef(false);

  useEffect(() => {
    if (editing) return;
    setDisplayName(user.displayName);
    setBio(user.bio ?? "");
    setAvatarUrl(user.avatarUrl);
    setAvatarFile(null);
  }, [editing, user]);

  useEffect(() => () => {
    if (avatarPreviewUrl) URL.revokeObjectURL(avatarPreviewUrl);
  }, [avatarPreviewUrl]);

  const dirty = displayName !== user.displayName
    || bio !== (user.bio ?? "")
    || avatarFile !== null
    || avatarUrl !== user.avatarUrl;
  const previewAvatar = avatarPreviewUrl ?? avatarUrl;

  function clearPreview() {
    setAvatarPreviewUrl(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function openEditor() {
    setSaved(false);
    setRequestError(null);
    setFieldErrors({});
    setEditing(true);
  }

  function cancelEditing() {
    setDisplayName(user.displayName);
    setBio(user.bio ?? "");
    setAvatarUrl(user.avatarUrl);
    setAvatarFile(null);
    clearPreview();
    setFieldErrors({});
    setRequestError(null);
    setEditing(false);
  }

  async function handleImageChange(file: File | undefined) {
    if (!file || validatingImage || savingRef.current) return;
    setValidatingImage(true);
    setRequestError(null);
    setFieldErrors((current) => ({ ...current, avatarUrl: undefined }));

    const prepared = await prepareEventImageFile(file);
    if (fileInputRef.current) fileInputRef.current.value = "";
    if (prepared.error || !prepared.file) {
      setFieldErrors((current) => ({ ...current, avatarUrl: prepared.error ?? "Immagine non valida." }));
      setValidatingImage(false);
      return;
    }

    setAvatarFile(prepared.file);
    setAvatarPreviewUrl(URL.createObjectURL(prepared.file));
    setAvatarUrl(user.avatarUrl);
    setValidatingImage(false);
  }

  function removeAvatar() {
    if (savingRef.current) return;
    setAvatarFile(null);
    clearPreview();
    setAvatarUrl(null);
    setFieldErrors((current) => ({ ...current, avatarUrl: undefined }));
    setRequestError(null);
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (savingRef.current || validatingImage || !dirty) return;

    const validationErrors = validateProfileDraft(displayName, bio);
    if (Object.keys(validationErrors).length > 0) {
      setFieldErrors(validationErrors);
      return;
    }

    savingRef.current = true;
    setSaving(true);
    setSaved(false);
    setFieldErrors({});
    setRequestError(null);

    let failureStage: "upload" | "profile" = avatarFile ? "upload" : "profile";
    let uploadedAvatarUrl: string | null = null;
    try {
      let nextAvatarUrl = avatarUrl;
      if (avatarFile) {
        const uploaded = await authorizedRequest((accessToken) => uploadEventImage(accessToken, avatarFile));
        if (!isInternalUploadedImageUrl(uploaded.imageUrl)) {
          throw new Error("Unexpected avatar upload path");
        }
        nextAvatarUrl = uploaded.imageUrl;
        uploadedAvatarUrl = uploaded.imageUrl;
        failureStage = "profile";
      }

      const updatedUser = await authorizedRequest((accessToken) => updateProfile(accessToken, {
        displayName: cleanDisplayName(displayName),
        bio: cleanBio(bio),
        avatarUrl: nextAvatarUrl,
      }));

      updateCurrentUser(updatedUser);
      setDisplayName(updatedUser.displayName);
      setBio(updatedUser.bio ?? "");
      setAvatarUrl(updatedUser.avatarUrl);
      setAvatarFile(null);
      clearPreview();
      setEditing(false);
      setSaved(true);
    } catch (error) {
      if (failureStage === "upload") {
        setRequestError(getEventImageUploadError(error));
      } else {
        if (uploadedAvatarUrl) {
          setAvatarUrl(uploadedAvatarUrl);
          setAvatarFile(null);
        }
        const backendErrors = getProfileFieldErrors(error);
        setFieldErrors({
          displayName: backendErrors.displayName,
          bio: backendErrors.bio,
          avatarUrl: backendErrors.avatarUrl,
        });
        setRequestError(Object.keys(backendErrors).length > 0 ? null : getProfileErrorMessage(error));
      }
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  if (!editing) {
    return (
      <div className="mt-5">
        {saved ? <div className="form-message form-message--success mb-4" role="status">Profilo aggiornato.</div> : null}
        <button className="button-secondary inline-flex w-full items-center gap-2 sm:w-auto" type="button" onClick={openEditor}>
          <Pencil size={17} aria-hidden="true" /> Modifica profilo
        </button>
      </div>
    );
  }

  return (
    <form className="surface-card content-reveal mt-4 p-5 sm:p-6" onSubmit={(event) => void handleSubmit(event)} noValidate>
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="section-kicker">Il tuo spazio</p>
          <h2 className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">Modifica profilo</h2>
        </div>
        <button className="grid size-11 shrink-0 place-items-center rounded-full text-muted transition-colors hover:bg-white/70 hover:text-ink" type="button" onClick={cancelEditing} disabled={saving} aria-label="Chiudi modifica profilo">
          <X size={20} aria-hidden="true" />
        </button>
      </div>

      <div className="mt-6 flex flex-col gap-4 sm:flex-row sm:items-center">
        <UserAvatar displayName={displayName || user.displayName} avatarUrl={previewAvatar} className="size-24 text-2xl" />
        <div className="flex min-w-0 flex-1 flex-wrap gap-2">
          <label className="button-secondary inline-flex cursor-pointer items-center gap-2" aria-disabled={saving || validatingImage}>
            <Camera size={17} aria-hidden="true" />
            {validatingImage ? "Verifica…" : previewAvatar ? "Cambia foto" : "Scegli foto"}
            <input
              ref={fileInputRef}
              className="sr-only"
              type="file"
              accept={EVENT_IMAGE_ACCEPT}
              disabled={saving || validatingImage}
              onChange={(event) => void handleImageChange(event.currentTarget.files?.[0])}
            />
          </label>
          {previewAvatar ? (
            <button className="button-secondary inline-flex items-center gap-2" type="button" onClick={removeAvatar} disabled={saving || validatingImage}>
              <Trash2 size={17} aria-hidden="true" /> Rimuovi
            </button>
          ) : null}
          <p className="w-full text-xs font-semibold leading-5 text-muted">JPEG, PNG o WebP, massimo 5 MB. La foto viene caricata solo al salvataggio.</p>
          {avatarFile ? <p className="w-full truncate text-xs font-extrabold text-linkr-strong">Pronta: {avatarFile.name}</p> : null}
          {fieldErrors.avatarUrl ? <p className="field-error w-full" role="alert">{fieldErrors.avatarUrl}</p> : null}
        </div>
      </div>

      <div className="mt-6">
        <label className="form-label" htmlFor="profile-display-name">Nome visualizzato</label>
        <input
          className="form-input mt-2"
          id="profile-display-name"
          value={displayName}
          maxLength={DISPLAY_NAME_MAX}
          autoComplete="name"
          disabled={saving}
          aria-invalid={fieldErrors.displayName ? "true" : undefined}
          aria-describedby={fieldErrors.displayName ? "profile-display-name-error" : "profile-display-name-help"}
          onChange={(event) => {
            setDisplayName(event.target.value);
            setFieldErrors((current) => ({ ...current, displayName: undefined }));
            setRequestError(null);
          }}
        />
        <div className="mt-1 flex items-start justify-between gap-3 text-xs font-semibold text-muted">
          <span id="profile-display-name-help">È il nome che vedranno le altre persone.</span>
          <span>{displayName.length}/{DISPLAY_NAME_MAX}</span>
        </div>
        {fieldErrors.displayName ? <p className="field-error mt-1" id="profile-display-name-error" role="alert">{fieldErrors.displayName}</p> : null}
      </div>

      <div className="mt-5">
        <label className="form-label" htmlFor="profile-bio">Bio <span className="font-semibold text-muted">(facoltativa)</span></label>
        <textarea
          className="form-input form-textarea mt-2 min-h-32"
          id="profile-bio"
          value={bio}
          maxLength={BIO_MAX}
          disabled={saving}
          aria-invalid={fieldErrors.bio ? "true" : undefined}
          aria-describedby={fieldErrors.bio ? "profile-bio-error" : "profile-bio-count"}
          onChange={(event) => {
            setBio(event.target.value);
            setFieldErrors((current) => ({ ...current, bio: undefined }));
            setRequestError(null);
          }}
        />
        <p className="mt-1 text-right text-xs font-semibold text-muted" id="profile-bio-count">{bio.length}/{BIO_MAX}</p>
        {fieldErrors.bio ? <p className="field-error mt-1" id="profile-bio-error" role="alert">{fieldErrors.bio}</p> : null}
      </div>

      {requestError ? <div className="form-message form-message--error mt-5" role="alert">{requestError}</div> : null}

      <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button className="button-secondary inline-flex items-center" type="button" onClick={cancelEditing} disabled={saving}>Annulla</button>
        <button className="button-primary inline-flex items-center" type="submit" disabled={saving || validatingImage || !dirty} aria-busy={saving}>
          {saving ? (avatarFile ? "Caricamento e salvataggio…" : "Salvataggio…") : "Salva profilo"}
        </button>
      </div>
    </form>
  );
}
