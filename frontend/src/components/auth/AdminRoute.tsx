import { ShieldAlert } from "lucide-react";
import { Link, Outlet } from "react-router";

import { useAuth } from "../../context/AuthContext";

export function AdminRoute() {
  const { user } = useAuth();

  if (user?.role !== "ADMIN") {
    return (
      <div className="page-shell page-with-mobile-nav pt-10 sm:pt-16">
        <section className="surface-card mx-auto max-w-xl p-7 text-center" aria-labelledby="admin-forbidden-title">
          <span className="mx-auto grid size-14 place-items-center rounded-full bg-linkr-soft text-linkr-strong">
            <ShieldAlert size={26} aria-hidden="true" />
          </span>
          <p className="section-kicker mt-5">Errore 403</p>
          <h1 id="admin-forbidden-title" className="mt-2 text-3xl font-extrabold tracking-[-0.04em] text-ink">
            Accesso riservato
          </h1>
          <p className="mx-auto mt-3 max-w-md font-semibold leading-7 text-muted">
            Questa area è disponibile solo agli amministratori LINKR.
          </p>
          <Link className="button-secondary mt-6 inline-flex" to="/">
            Torna a Discover
          </Link>
        </section>
      </div>
    );
  }

  return <Outlet />;
}
