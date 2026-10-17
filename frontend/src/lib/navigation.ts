interface ReturnLocation {
  readonly pathname?: unknown;
  readonly search?: unknown;
  readonly hash?: unknown;
}

interface ReturnState {
  readonly from?: ReturnLocation;
}

export function getSafeReturnPath(state: unknown): string {
  if (typeof state !== "object" || state === null || !("from" in state)) return "/";

  const { from } = state as ReturnState;
  if (!from || typeof from.pathname !== "string") return "/";

  const pathname = from.pathname;
  if (!pathname.startsWith("/") || pathname.startsWith("//") || pathname.includes("\\")) return "/";
  if (pathname === "/login" || pathname === "/register") return "/";

  const search = typeof from.search === "string" && from.search.startsWith("?") ? from.search : "";
  const hash = typeof from.hash === "string" && from.hash.startsWith("#") ? from.hash : "";
  return `${pathname}${search}${hash}`;
}
