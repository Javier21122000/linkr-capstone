import type { ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { Link } from "react-router";

interface AuthLayoutProps {
  readonly title: string;
  readonly description: string;
  readonly children: ReactNode;
}

export function AuthLayout({ title, description, children }: AuthLayoutProps) {
  return (
    <div className="page-shell pb-12 pt-8 sm:pb-20 sm:pt-12">
      <div className="mx-auto w-full max-w-md">
        <Link to="/" className="inline-flex min-h-11 items-center gap-2 rounded-full font-extrabold text-ink">
          <ArrowLeft size={18} aria-hidden="true" /> Esplora gli eventi
        </Link>

        <section className="auth-panel mt-6" aria-labelledby="auth-title">
          <p className="section-kicker">Il tuo spazio LINKR</p>
          <h1 id="auth-title" className="mt-3 text-4xl font-extrabold leading-[1] tracking-[-0.055em] text-ink sm:text-5xl">
            {title}
          </h1>
          <p className="mt-4 text-sm leading-6 text-muted sm:text-base">{description}</p>
          <div className="mt-8">{children}</div>
        </section>
      </div>
    </div>
  );
}
