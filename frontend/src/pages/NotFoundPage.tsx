import { ArrowLeft } from "lucide-react";
import { Link } from "react-router";

import { BrandMark } from "../components/ui/BrandMark";

export function NotFoundPage() {
  return (
    <main className="grid min-h-dvh place-items-center bg-canvas px-5 py-12 text-ink">
      <div className="w-full max-w-xl text-center">
        <div className="flex justify-center">
          <BrandMark />
        </div>
        <p className="mt-16 text-sm font-extrabold uppercase tracking-[0.16em] text-linkr-strong">404</p>
        <h1 className="mt-3 text-5xl font-extrabold leading-[0.96] tracking-[-0.06em] sm:text-7xl">
          Questo piano è andato fuori rotta.
        </h1>
        <p className="mx-auto mt-6 max-w-md text-base leading-7 text-muted">
          La pagina non esiste, ma la città è ancora piena di cose da fare.
        </p>
        <Link to="/" className="button-primary mt-8 inline-flex items-center gap-2">
          <ArrowLeft size={18} aria-hidden="true" /> Torna alla scoperta
        </Link>
      </div>
    </main>
  );
}
