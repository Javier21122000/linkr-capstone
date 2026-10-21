interface CountBadgeProps {
  readonly count: number | null;
}

/** Badge numerico decorativo: il conteggio va annunciato dall'aria-label del contenitore. */
export function CountBadge({ count }: CountBadgeProps) {
  if (count === null || count === 0) return null;

  return (
    <span
      className="inline-flex min-w-5 items-center justify-center rounded-full bg-linkr-action px-1.5 py-0.5 text-[0.65rem] font-extrabold leading-none text-white"
      aria-hidden="true"
    >
      {count > 99 ? "99+" : count}
    </span>
  );
}
