import { Check, Heart } from "lucide-react";

import type { ParticipationStatus } from "../../types/participation";

export const participationLabels: Record<ParticipationStatus, string> = {
  GOING: "Parteciperò",
  INTERESTED: "Mi interessa",
};

interface ParticipationMarkProps {
  readonly status: ParticipationStatus;
  readonly size?: number;
  readonly className?: string;
}

/**
 * Il tuo stato su un evento, solo con un simbolo: cuore con il contorno per "Mi interessa", cerchio pieno con spunta per "Parteciperò".
 * Decorativo (`aria-hidden`): chi lo legge lo trova nel nome accessibile del marker o come testo accanto, mai come informazione solo grafica.
 */
export function ParticipationMark({ status, size = 11, className = "" }: ParticipationMarkProps) {
  return (
    <span className={`participation-mark ${className}`.trim()} data-status={status} aria-hidden="true">
      {status === "GOING" ? <Check size={size} strokeWidth={3.6} /> : <Heart size={size} strokeWidth={2.7} />}
    </span>
  );
}

/** Come sopra, con la parola: per le righe della lista e per l'anteprima, dove c'è spazio. */
export function ParticipationChip({ status }: { readonly status: ParticipationStatus }) {
  return (
    <span className="participation-chip" data-status={status}>
      <ParticipationMark status={status} size={10} className="participation-mark--inline" />
      {participationLabels[status]}
    </span>
  );
}
