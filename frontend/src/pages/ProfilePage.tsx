import axios from "axios";
import { ArrowRight, CalendarDays, LogOut, Mail, RotateCcw, UsersRound } from "lucide-react";
import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";

import { UserAvatar } from "../components/auth/UserAvatar";
import { ProfileParticipations } from "../components/events/ProfileParticipations";
import { ProfileInterests } from "../components/interests/ProfileInterests";
import { IncomingLinkCountBadge } from "../components/links/IncomingLinkCountBadge";
import { useAuth } from "../context/AuthContext";
import { useLinkRequestCount } from "../context/LinkRequestCountContext";
import { getAuthRequestErrorMessage } from "../lib/auth";
import type { UserInterest } from "../types/interest";

function formatMemberSince(createdAt: string): string {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return "Data non disponibile";
  return new Intl.DateTimeFormat("it-IT", { day: "numeric", month: "long", year: "numeric" }).format(date);
}

function getSavedInterests(state: unknown): readonly UserInterest[] | undefined {
  if (typeof state !== "object" || state === null || !("interestsSaved" in state) || state.interestsSaved !== true) return undefined;
  if (!("savedInterests" in state) || !Array.isArray(state.savedInterests)) return undefined;

  const valid = state.savedInterests.every((interest: unknown) => (
    typeof interest === "object"
    && interest !== null
    && "id" in interest && typeof interest.id === "string"
    && "slug" in interest && typeof interest.slug === "string"
    && "name" in interest && typeof interest.name === "string"
    && "categoryId" in interest && typeof interest.categoryId === "string"
    && "categorySlug" in interest && typeof interest.categorySlug === "string"
  ));
  return valid ? state.savedInterests as readonly UserInterest[] : undefined;
}

export function ProfilePage() {
  const { user, logout, validateSession } = useAuth();
  const { incomingCount } = useLinkRequestCount();
  const location = useLocation();
  const navigate = useNavigate();
  const [requestKey, setRequestKey] = useState(0);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [savedInterests] = useState(() => getSavedInterests(location.state));
  const interestsSaved = savedInterests !== undefined;

  useEffect(() => {
    if (interestsSaved) navigate(location.pathname, { replace: true, state: null });
  }, [interestsSaved, location.pathname, navigate]);

  useEffect(() => {
    const controller = new AbortController();
    setLoadError(null);
    void validateSession(controller.signal).catch((error: unknown) => {
      if (!axios.isCancel(error)) setLoadError(getAuthRequestErrorMessage(error));
    });
    return () => controller.abort();
  }, [requestKey, validateSession]);

  if (!user) return null;

  function handleLogout() {
    logout();
    navigate("/", { replace: true });
  }

  return (
    <div className="page-shell page-with-mobile-nav pt-8 sm:pt-12 lg:pt-16">
      <section className="mx-auto max-w-2xl" aria-labelledby="profile-title">
        <p className="section-kicker">Il tuo profilo</p>
        <div className="mt-5 flex items-center gap-5">
          <UserAvatar displayName={user.displayName} avatarUrl={user.avatarUrl} className="size-20 text-xl sm:size-24 sm:text-2xl" />
          <div className="min-w-0">
            <h1 id="profile-title" className="truncate text-3xl font-extrabold tracking-[-0.05em] text-ink sm:text-4xl">
              {user.displayName}
            </h1>
            <p className="mt-1 truncate font-semibold text-muted">@{user.username}</p>
          </div>
        </div>

        {user.bio?.trim() ? <p className="mt-6 text-base leading-7 text-ink">{user.bio}</p> : null}

        {loadError ? (
          <div className="form-message form-message--error mt-6" role="alert">
            <p>{loadError}</p>
            <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
              <RotateCcw size={17} aria-hidden="true" /> Riprova
            </button>
          </div>
        ) : null}

        <div className="mt-8 divide-y divide-line border-y border-line">
          <div className="flex items-start gap-4 py-5">
            <Mail className="mt-0.5 shrink-0 text-linkr-strong" size={20} aria-hidden="true" />
            <div className="min-w-0">
              <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-muted">Email</p>
              <p className="mt-1 break-words font-semibold text-ink">{user.email}</p>
            </div>
          </div>
          <div className="flex items-start gap-4 py-5">
            <CalendarDays className="mt-0.5 shrink-0 text-linkr-strong" size={20} aria-hidden="true" />
            <div>
              <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-muted">Membro dal</p>
              <p className="mt-1 font-semibold text-ink">{formatMemberSince(user.createdAt)}</p>
            </div>
          </div>
        </div>

        <Link
          className="mt-8 flex min-h-16 items-center gap-4 rounded-2xl border border-line bg-surface p-4 text-ink transition-transform active:scale-[0.99] motion-reduce:transition-none"
          to="/profile/links"
          aria-label={incomingCount
            ? `I miei Link, ${incomingCount} richieste ricevute in attesa`
            : undefined}
        >
          <span className="grid size-11 shrink-0 place-items-center rounded-full bg-linkr-soft text-linkr-strong">
            <UsersRound size={20} aria-hidden="true" />
          </span>
          <span className="min-w-0 flex-1">
            <span className="flex items-center gap-2 font-extrabold">I miei Link <IncomingLinkCountBadge /></span>
            <span className="mt-1 block text-sm font-semibold text-muted">Richieste ricevute, inviate e collegamenti</span>
          </span>
          <ArrowRight className="shrink-0 text-muted" size={19} aria-hidden="true" />
        </Link>

        <ProfileParticipations />

        <ProfileInterests saved={interestsSaved} initialInterests={savedInterests} />

        <button className="button-secondary mt-8 flex w-full items-center gap-2 sm:w-auto" type="button" onClick={handleLogout}>
          <LogOut size={18} aria-hidden="true" /> Esci
        </button>
      </section>
    </div>
  );
}
