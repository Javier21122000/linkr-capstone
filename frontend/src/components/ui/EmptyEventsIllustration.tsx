/** Illustrazione editoriale "nessun evento vicino": line art con accenti corallo. */
export function EmptyEventsIllustration({ className = "w-56" }: { readonly className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 240 160"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      <path d="M8 142H232" />

      <path d="M22 142V78H74V142" />
      <path d="M18 78H78" />
      <rect x="32" y="90" width="11" height="14" rx="2.5" />
      <rect x="53" y="90" width="11" height="14" rx="2.5" />
      <path d="M38 142V124a10 10 0 0 1 20 0V142" />

      <path d="M164 142V36H220V142" />
      <rect x="174" y="48" width="10" height="13" rx="2.5" />
      <rect x="200" y="48" width="10" height="13" rx="2.5" />
      <rect x="174" y="72" width="10" height="13" rx="2.5" />
      <rect x="200" y="72" width="10" height="13" rx="2.5" />
      <path d="M158 104H226L220 116H164Z" fill="var(--color-peach)" stroke="var(--color-linkr)" />
      <path d="M176 104L174 116M192 104V116M208 104L210 116" stroke="var(--color-linkr)" />
      <path d="M174 142V128H210V142" />

      <path d="M104 142V82Q104 72 114 72H122" />
      <path d="M120 72H132L130 80H122Z" fill="currentColor" />

      <path d="M88 138C98 112 122 120 132 100S146 70 138 54" strokeDasharray="0.5 7" />
      <circle cx="88" cy="138" r="4" fill="currentColor" stroke="none" />
      <path
        d="M138 52C130 44 124 38 124 30a14 14 0 0 1 28 0c0 8-6 14-14 22Z"
        fill="var(--color-linkr)"
        stroke="var(--color-linkr)"
      />
      <circle cx="138" cy="30" r="5" fill="var(--color-canvas)" stroke="none" />
    </svg>
  );
}
