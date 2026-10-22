import type { ReactNode } from "react";
import { Link, matchPath, useLocation } from "react-router";

interface NavItemProps {
  readonly to: string;
  /** Confronta il percorso esatto (es. Discover su "/"). */
  readonly end?: boolean;
  /** Percorsi figli che appartengono a un'altra voce (es. /profile/links ha la sua voce "Link"). */
  readonly excludePaths?: readonly string[];
  readonly className: string;
  readonly "aria-label"?: string;
  readonly title?: string;
  /** Gancio del tour guidato (vedi ProductGuidanceContext). */
  readonly "data-tour"?: string;
  readonly children: ReactNode;
}

/** Come NavLink, ma permette di escludere sotto-percorsi per evitare due voci attive insieme. */
export function NavItem({ to, end = false, excludePaths = [], className, children, ...props }: NavItemProps) {
  const { pathname } = useLocation();
  const active = matchPath({ path: to, end }, pathname) !== null
    && !excludePaths.some((path) => matchPath({ path, end: false }, pathname) !== null);

  return (
    <Link to={to} className={className} aria-current={active ? "page" : undefined} {...props}>
      {children}
    </Link>
  );
}
