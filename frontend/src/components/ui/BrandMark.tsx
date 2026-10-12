interface BrandMarkProps {
  readonly compact?: boolean;
}

export function BrandMark({ compact = false }: BrandMarkProps) {
  return (
    <span className="inline-flex items-center gap-2" aria-label="LINKR">
      <span aria-hidden="true" className="relative size-7 shrink-0">
        <span className="absolute left-0 top-1 size-4 rounded-full border-[3px] border-ink" />
        <span className="absolute bottom-1 right-0 size-4 rounded-full border-[3px] border-linkr" />
      </span>
      {!compact && (
        <span className="text-lg font-extrabold tracking-[-0.055em] text-ink sm:text-xl">
          LINKR
        </span>
      )}
    </span>
  );
}
