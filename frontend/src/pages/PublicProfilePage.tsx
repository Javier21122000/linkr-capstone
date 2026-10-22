import axios from "axios";
import { ArrowLeft, Check, Link2, RotateCcw, UserRoundX } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { EmptyState } from "../components/ui/EmptyState";
import { useAuth } from "../context/AuthContext";
import { getAuthRequestErrorMessage } from "../lib/auth";
import { categoryForInterest, discoverPathForInterest, splitInterests } from "../lib/interestFilter";
import { categoryLabels } from "../lib/eventPresentation";
import { getPublicProfile, isPublicProfileUnavailable } from "../lib/profile";
import type { ProfileInterest, PublicProfile } from "../lib/profile";

type ProfileState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly profile: PublicProfile }
  | { readonly status: "unavailable" }
  | { readonly status: "error"; readonly message: string };

function interestLinkLabel(interest: ProfileInterest, shared: boolean): string {
  const category = categoryForInterest(interest);
  const target = category ? `mostra gli eventi di ${categoryLabels[category]}` : "apri Discover";
  return `${shared ? "In comune con te: " : ""}${interest.name}, ${target}`;
}

function InterestPill({ interest, shared }: { readonly interest: ProfileInterest; readonly shared: boolean }) {
  return (
    <Link className={shared ? "profile-chip profile-chip--shared" : "profile-chip"} to={discoverPathForInterest(interest)} aria-label={interestLinkLabel(interest, shared)}>
      {shared ? <Check size={14} strokeWidth={3} aria-hidden="true" /> : null}
      {interest.name}
    </Link>
  );
}

function linkStatusLabel(status: PublicProfile["linkStatus"]): string | null {
  if (status === "ACCEPTED") return "Siete collegati";
  if (status === "OUTGOING_PENDING") return "Richiesta Link inviata";
  if (status === "INCOMING_PENDING") return "Richiesta Link ricevuta";
  if (status === "INCOMING_DECLINED") return "Richiesta Link archiviata";
  return null;
}

export function PublicProfilePage() {
  const { personId } = useParams();
  const { authorizedRequest } = useAuth();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<ProfileState>({ status: "loading" });

  useEffect(() => {
    if (!personId) {
      setState({ status: "unavailable" });
      return undefined;
    }

    const controller = new AbortController();
    setState({ status: "loading" });
    void authorizedRequest((accessToken) => getPublicProfile(accessToken, personId, controller.signal))
      .then((profile) => setState({ status: "success", profile }))
      .catch((error: unknown) => {
        if (axios.isCancel(error)) return;
        if (isPublicProfileUnavailable(error)) setState({ status: "unavailable" });
        else setState({ status: "error", message: getAuthRequestErrorMessage(error) });
      });

    return () => controller.abort();
  }, [authorizedRequest, personId, requestKey]);

  const profile = state.status === "success" ? state.profile : null;
  const { shared: sharedInterests, others: otherInterests } = splitInterests(profile?.interests ?? [], profile?.sharedInterests ?? []);
  const statusLabel = profile ? linkStatusLabel(profile.linkStatus) : null;

  return (
    <div className="page-shell page-with-mobile-nav pt-8 sm:pt-12 lg:pt-16" data-editorial="profile">
      <section className="mx-auto max-w-2xl" aria-labelledby="public-profile-title">
        <Link className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink" to="/people">
          <ArrowLeft size={19} aria-hidden="true" /> Torna alle persone
        </Link>

        {state.status === "loading" ? (
          <div className="surface-card mt-6 p-6" aria-busy="true" aria-label="Caricamento profilo">
            <div className="flex items-center gap-4">
              <span className="size-24 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
              <div className="flex-1 space-y-3">
                <span className="block h-8 max-w-64 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
                <span className="block h-5 max-w-36 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
              </div>
            </div>
          </div>
        ) : null}

        {state.status === "unavailable" ? (
          <div className="surface-card content-reveal mt-6 px-6 py-10" role="status">
            <EmptyState
              className="mx-auto max-w-md"
              illustration={<span className="grid size-14 place-items-center rounded-full bg-linkr-soft text-linkr-strong"><UserRoundX size={27} aria-hidden="true" /></span>}
              title="Profilo non disponibile"
              description="Questa persona non esiste oppure le sue impostazioni di privacy non consentono di mostrare il profilo."
              action={<Link className="button-secondary inline-flex items-center" to="/people">Scopri persone</Link>}
            />
          </div>
        ) : null}

        {state.status === "error" ? (
          <div className="form-message form-message--error mt-6" role="alert">
            <p>{state.message}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        {profile ? (
          <>
            <article className="surface-card content-reveal mt-6 p-5 sm:p-7">
              {/* Spazio riservato a un'illustrazione collage originale (nascosto nel tema base). */}
              <div className="ed-slot" data-slot="profile-hero" aria-hidden="true" />
              <div className="flex flex-col items-start gap-5 sm:flex-row sm:items-center">
                <UserAvatar displayName={profile.displayName} avatarUrl={profile.avatarUrl} className="size-24 text-2xl sm:size-28 sm:text-3xl" />
                <div className="min-w-0">
                  <p className="section-kicker">Profilo</p>
                  <h1 id="public-profile-title" className="mt-2 break-words text-3xl font-extrabold tracking-[-0.05em] text-ink sm:text-4xl">{profile.displayName}</h1>
                  <p className="mt-1 break-words font-semibold text-muted">@{profile.username}</p>
                  {statusLabel ? (
                    <span className={`mt-3 inline-flex min-h-9 items-center gap-2 rounded-full px-3 py-1 text-xs font-extrabold ${profile.linkStatus === "ACCEPTED" ? "bg-[#edf8f3] text-success" : "bg-linkr-soft text-linkr-strong"}`}>
                      <Link2 size={15} aria-hidden="true" /> {statusLabel}
                    </span>
                  ) : null}
                </div>
              </div>
              {profile.bio?.trim() ? <p className="mt-6 whitespace-pre-line text-base leading-7 text-ink">{profile.bio}</p> : null}
            </article>

            <section className="surface-card content-reveal mt-4 p-5 sm:p-6" aria-labelledby="public-profile-interests-title">
              <p className="section-kicker">Affinità</p>
              <h2 id="public-profile-interests-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">Interessi</h2>
              {profile.interests.length > 0 ? (
                <div className="mt-4 space-y-5">
                  {sharedInterests.length > 0 ? (
                    <div role="group" aria-labelledby="public-profile-shared-title">
                      <h3 id="public-profile-shared-title" className="text-sm font-extrabold text-linkr-strong">
                        {sharedInterests.length === 1 ? "1 interesse in comune con te" : `${sharedInterests.length} interessi in comune con te`}
                      </h3>
                      <div className="mt-2.5 flex flex-wrap gap-2">
                        {sharedInterests.map((interest) => <InterestPill interest={interest} shared key={interest.id} />)}
                      </div>
                    </div>
                  ) : null}
                  {otherInterests.length > 0 ? (
                    <div role="group" aria-labelledby="public-profile-others-title">
                      <h3 id="public-profile-others-title" className="text-sm font-extrabold text-muted">
                        {sharedInterests.length > 0 ? "Altri interessi" : "Nessun interesse in comune per ora"}
                      </h3>
                      <div className="mt-2.5 flex flex-wrap gap-2">
                        {otherInterests.map((interest) => <InterestPill interest={interest} shared={false} key={interest.id} />)}
                      </div>
                    </div>
                  ) : null}
                  <p className="text-xs font-semibold leading-5 text-muted">Tocca un interesse per vedere gli eventi della sua categoria in Discover.</p>
                </div>
              ) : <p className="mt-4 text-sm leading-6 text-muted">Questa persona non ha ancora aggiunto interessi.</p>}
            </section>
          </>
        ) : null}
      </section>
    </div>
  );
}
