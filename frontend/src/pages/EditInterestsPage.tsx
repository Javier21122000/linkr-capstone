import axios from "axios";
import { ArrowLeft, RotateCcw } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useBlocker, useLocation, useNavigate } from "react-router";

import { InterestCategoryPanel } from "../components/interests/InterestCategoryPanel";
import { UnsavedChangesDialog } from "../components/interests/UnsavedChangesDialog";
import { useAuth } from "../context/AuthContext";
import { getInterestCatalog, getInterestsErrorMessage, getUserInterests, MAX_INTERESTS, replaceUserInterests } from "../lib/interests";
import type { InterestCategory } from "../types/interest";

type LoadState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly catalog: readonly InterestCategory[] }
  | { readonly status: "error"; readonly message: string };

function setsAreEqual(left: ReadonlySet<string>, right: ReadonlySet<string>): boolean {
  return left.size === right.size && [...left].every((id) => right.has(id));
}

function isOnboardingState(state: unknown): boolean {
  return typeof state === "object" && state !== null && "onboarding" in state && state.onboarding === true;
}

export function EditInterestsPage() {
  const { authorizedRequest, status } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const onboarding = isOnboardingState(location.state);
  const [requestKey, setRequestKey] = useState(0);
  const [loadState, setLoadState] = useState<LoadState>({ status: "loading" });
  const [initialIds, setInitialIds] = useState<ReadonlySet<string>>(new Set());
  const [selectedIds, setSelectedIds] = useState<ReadonlySet<string>>(new Set());
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const savingRef = useRef(false);
  const skipBlockRef = useRef(false);

  const dirty = useMemo(() => !setsAreEqual(initialIds, selectedIds), [initialIds, selectedIds]);
  const atLimit = selectedIds.size >= MAX_INTERESTS;
  const shouldBlock = useCallback(
    () => (dirty || savingRef.current) && !skipBlockRef.current && status === "authenticated",
    [dirty, status],
  );
  const blocker = useBlocker(shouldBlock);

  useEffect(() => {
    if (saving && blocker.state === "blocked") blocker.reset();
  }, [blocker, saving]);

  useEffect(() => {
    const controller = new AbortController();
    setLoadState({ status: "loading" });
    setSaveError(null);

    void Promise.all([
      getInterestCatalog(controller.signal),
      authorizedRequest((accessToken) => getUserInterests(accessToken, controller.signal)),
    ]).then(([catalog, response]) => {
      const ids = new Set(response.interests.map((interest) => interest.id));
      setInitialIds(ids);
      setSelectedIds(new Set(ids));
      setLoadState({ status: "success", catalog });
    }).catch((error: unknown) => {
      if (!axios.isCancel(error)) setLoadState({ status: "error", message: getInterestsErrorMessage(error) });
    });

    return () => controller.abort();
  }, [authorizedRequest, requestKey]);

  useEffect(() => {
    if (!dirty && !saving) return undefined;

    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [dirty, saving]);

  function handleBack() {
    navigate(-1);
  }

  function toggleInterest(interestId: string) {
    if (savingRef.current) return;
    setSaveError(null);
    setSelectedIds((current) => {
      const next = new Set(current);
      if (next.has(interestId)) next.delete(interestId);
      else if (next.size < MAX_INTERESTS) next.add(interestId);
      return next;
    });
  }

  async function handleSave() {
    if (savingRef.current || saving || !dirty || loadState.status !== "success") return;
    savingRef.current = true;
    setSaving(true);
    setSaveError(null);

    try {
      const response = await authorizedRequest((accessToken) => replaceUserInterests(accessToken, { interestIds: [...selectedIds] }));
      const savedIds = new Set(response.interests.map((interest) => interest.id));
      setInitialIds(savedIds);
      setSelectedIds(savedIds);
      skipBlockRef.current = true;
      if (onboarding) {
        navigate("/", { replace: true });
      } else {
        navigate("/profile", { replace: true, state: { interestsSaved: true, savedInterests: response.interests } });
      }
    } catch (error) {
      setSaveError(getInterestsErrorMessage(error));
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  return (
    <div className="page-shell pb-16 pt-8 sm:pt-12 lg:pt-16">
      <div className="mx-auto max-w-2xl">
        {!onboarding ? (
          <button className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink disabled:cursor-not-allowed disabled:opacity-50" type="button" onClick={handleBack} disabled={saving}>
            <ArrowLeft size={19} aria-hidden="true" /> Torna al profilo
          </button>
        ) : null}

        <header className="mt-6">
          <p className="section-kicker">I tuoi interessi</p>
          <h1 className="mt-3 text-4xl font-extrabold leading-[1] tracking-[-0.055em] text-ink sm:text-5xl">Cosa ti piace fare?</h1>
          <p className="mt-4 max-w-xl text-base leading-7 text-muted">
            {onboarding
              ? "Scegli fino a 15 interessi per scoprire eventi e persone con cui condividerli. Potrai modificarli quando vuoi."
              : "Scegli fino a 15 interessi. Ti aiuteranno a trovare eventi e persone con cui condividerli."}
          </p>
        </header>

        {loadState.status === "loading" ? (
          <div className="mt-8 space-y-3" aria-busy="true" aria-label="Caricamento catalogo e interessi personali">
            {Array.from({ length: 4 }, (_, index) => <div key={index} className="h-16 animate-pulse rounded-2xl bg-line/70 motion-reduce:animate-none" />)}
          </div>
        ) : null}

        {loadState.status === "error" ? (
          <div className="form-message form-message--error mt-8" role="alert">
            <p>{loadState.message}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        {loadState.status === "success" ? (
          <>
            <div className="mt-8 flex items-center justify-between gap-4" aria-live="polite">
              <p className="font-extrabold text-ink">{selectedIds.size} di {MAX_INTERESTS} selezionati</p>
              {saving
                ? <span className="text-xs font-bold text-linkr-strong">Salvataggio in corso…</span>
                : dirty ? <span className="text-xs font-bold text-linkr-strong">Modifiche non salvate</span> : null}
            </div>

            {atLimit ? <p className="form-message mt-4" role="status">Hai raggiunto il limite di 15. Puoi deselezionare un interesse per sceglierne un altro.</p> : null}

            <div className="mt-5 divide-y divide-line border-y border-line">
              {loadState.catalog.map((category, index) => (
                <InterestCategoryPanel
                  key={category.id}
                  category={category}
                  selectedIds={selectedIds}
                  atLimit={atLimit}
                  disabled={saving}
                  initiallyOpen={index === 0 || category.interests.some((interest) => initialIds.has(interest.id))}
                  onToggle={toggleInterest}
                />
              ))}
            </div>

            {saveError ? <div className="form-message form-message--error mt-6" role="alert">{saveError}</div> : null}

            <div className="sticky bottom-0 -mx-4 mt-8 border-t border-line bg-canvas/96 px-4 pb-[max(1rem,env(safe-area-inset-bottom))] pt-4 sm:static sm:mx-0 sm:border-0 sm:bg-transparent sm:px-0 sm:pb-0">
              <button className="button-primary flex w-full items-center" type="button" onClick={handleSave} disabled={saving || !dirty} aria-live="polite">
                {saving ? "Salvataggio…" : onboarding ? "Salva e continua" : "Salva interessi"}
              </button>
              {onboarding ? (
                <button className="mx-auto mt-3 flex min-h-11 items-center justify-center rounded-full px-4 text-sm font-extrabold text-muted disabled:cursor-not-allowed disabled:opacity-50" type="button" onClick={() => navigate("/")} disabled={saving}>
                  Lo farò più tardi
                </button>
              ) : null}
            </div>
          </>
        ) : null}
      </div>
      {!saving ? <UnsavedChangesDialog blocker={blocker} /> : null}
    </div>
  );
}
