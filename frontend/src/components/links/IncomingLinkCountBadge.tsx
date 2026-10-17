import { useLinkRequestCount } from "../../context/LinkRequestCountContext";

export function IncomingLinkCountBadge() {
  const { incomingCount } = useLinkRequestCount();
  if (incomingCount === null || incomingCount === 0) return null;

  return (
    <span
      className="inline-flex min-w-5 items-center justify-center rounded-full bg-linkr-strong px-1.5 py-0.5 text-[0.65rem] font-extrabold leading-none text-white"
      aria-hidden="true"
    >
      {incomingCount > 99 ? "99+" : incomingCount}
    </span>
  );
}
