import axios from "axios";
import { ArrowRight, RotateCcw } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router";

import { useAuth } from "../../context/AuthContext";
import { getInterestsErrorMessage, getUserInterests } from "../../lib/interests";
import type { UserInterest } from "../../types/interest";

type InterestsState =
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly interests: readonly UserInterest[] }
  | { readonly status: "error"; readonly message: string };

interface ProfileInterestsProps {
  readonly saved?: boolean;
  readonly initialInterests?: readonly UserInterest[];
}

export function ProfileInterests({ saved = false, initialInterests }: ProfileInterestsProps) {
  const { authorizedRequest } = useAuth();
  const [requestKey, setRequestKey] = useState(0);
  const [state, setState] = useState<InterestsState>(
    initialInterests ? { status: "success", interests: initialInterests } : { status: "loading" },
  );

  useEffect(() => {
    if (initialInterests) {
      setState({ status: "success", interests: initialInterests });
      return undefined;
    }

    const controller = new AbortController();
    setState({ status: "loading" });

    void authorizedRequest((accessToken) => getUserInterests(accessToken, controller.signal))
      .then((response) => setState({ status: "success", interests: response.interests }))
      .catch((error: unknown) => {
        if (!axios.isCancel(error)) setState({ status: "error", message: getInterestsErrorMessage(error) });
      });

    return () => controller.abort();
  }, [authorizedRequest, initialInterests, requestKey]);

  return (
    <section className="surface-card mt-10 p-5 sm:p-6" aria-labelledby="profile-interests-title">
      {saved ? <div className="form-message form-message--success mb-6" role="status">Interessi salvati.</div> : null}

      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="section-kicker">Preferenze</p>
          <h2 id="profile-interests-title" className="mt-2 text-2xl font-extrabold tracking-[-0.04em] text-ink">
            I tuoi interessi
          </h2>
        </div>
        {state.status === "success" && state.interests.length > 0 ? (
          <Link className="inline-flex min-h-11 items-center gap-1 rounded-full px-2 font-extrabold text-ink" to="/profile/interests">
            Modifica <ArrowRight size={17} aria-hidden="true" />
          </Link>
        ) : null}
      </div>

      {state.status === "loading" ? (
        <div className="mt-5 flex flex-wrap gap-2" aria-busy="true" aria-label="Caricamento interessi">
          <span className="h-10 w-24 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
          <span className="h-10 w-32 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
          <span className="h-10 w-20 animate-pulse rounded-full bg-line/70 motion-reduce:animate-none" />
        </div>
      ) : null}

      {state.status === "error" ? (
        <div className="form-message form-message--error mt-5" role="alert">
          <p>{state.message}</p>
          <button className="mt-3 inline-flex min-h-11 items-center gap-2 font-extrabold text-ink" type="button" onClick={() => setRequestKey((current) => current + 1)}>
            <RotateCcw size={17} aria-hidden="true" /> Riprova
          </button>
        </div>
      ) : null}

      {state.status === "success" && state.interests.length > 0 ? (
        <div className="mt-5 flex flex-wrap gap-2">
          {state.interests.map((interest) => <span className="interest-chip interest-chip--static" key={interest.id}>{interest.name}</span>)}
        </div>
      ) : null}

      {state.status === "success" && state.interests.length === 0 ? (
        <div className="mt-5">
          <p className="max-w-lg text-sm leading-6 text-muted">Scegli ciò che ti piace per personalizzare le future esperienze LINKR.</p>
          <Link className="button-primary mt-5 inline-flex items-center gap-2" to="/profile/interests">
            Scegli i tuoi interessi <ArrowRight size={17} aria-hidden="true" />
          </Link>
        </div>
      ) : null}
    </section>
  );
}
