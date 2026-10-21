import type { ReactNode } from "react";

interface EmptyStateProps {
  readonly title: string;
  readonly description?: string;
  readonly illustration?: ReactNode;
  readonly action?: ReactNode;
  readonly className?: string;
}

export function EmptyState({ title, description, illustration, action, className = "" }: EmptyStateProps) {
  return (
    <div className={`flex flex-col items-center text-center ${className}`.trim()}>
      {illustration && <div className="mb-4 text-ink">{illustration}</div>}
      <p className="text-lg font-extrabold tracking-[-0.03em] text-ink">{title}</p>
      {description && <p className="mt-1.5 max-w-64 text-sm font-semibold leading-6 text-muted">{description}</p>}
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}
