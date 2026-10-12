interface AttendeeStackProps {
  readonly initials: readonly string[];
  readonly count: number;
  readonly inverse?: boolean;
}

const swatches = ["bg-[#f4b860]", "bg-[#b7c9a8]", "bg-[#ef9273]"] as const;

export function AttendeeStack({ initials, count, inverse = false }: AttendeeStackProps) {
  return (
    <div className="flex min-w-0 items-center gap-2.5">
      <div className="flex -space-x-2" aria-hidden="true">
        {initials.slice(0, 3).map((initial, index) => (
          <span
            className={`grid size-8 place-items-center rounded-full border-2 text-[0.65rem] font-extrabold text-ink ${
              inverse ? "border-ink" : "border-surface"
            } ${swatches[index % swatches.length]}`}
            key={`${initial}-${index}`}
          >
            {initial}
          </span>
        ))}
      </div>
      <span className={`truncate text-sm font-semibold ${inverse ? "text-white/82" : "text-muted"}`}>
        {count} demo attendees
      </span>
    </div>
  );
}
