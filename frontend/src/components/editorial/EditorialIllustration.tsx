import { useId } from "react";
import type { ReactNode } from "react";

/**
 * Illustrazioni originali in stile "fotocopia a retino": forme piatte, puntini d'inchiostro, ritaglio bianco intorno (die-cut).
 * Sono SVG inline disegnati qui (nessuna immagine da scaricare) e puramente decorativi: `aria-hidden` e `.ed-deco`, che il tema base
 * nasconde e la variante Urban Editorial Pop mostra (vedi styles/editorial.css).
 */
export type IllustrationKind = "duomo" | "megaphone" | "duo" | "crowd";

interface EditorialIllustrationProps {
  readonly kind: IllustrationKind;
  readonly className?: string;
}

const INK = "#181816";
const PAPER = "#f6f1e4";
const YELLOW = "#f6cf4d";
const CORAL = "#e84d2f";
const PINK = "#f3b4a6";

const VIEWBOX: Record<IllustrationKind, string> = {
  duomo: "0 0 240 210",
  megaphone: "0 0 250 210",
  duo: "0 0 260 200",
  crowd: "0 0 270 200",
};

/** Tre densità di retino (chiaro, medio, scuro): i puntini si ingrandiscono dove l'ombra è più forte, come in stampa. */
function HalftoneDefs({ id }: { readonly id: string }) {
  return (
    <defs>
      <pattern id={`${id}-l`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="6" height="6" fill={PAPER} />
        <circle cx="3" cy="3" r="0.9" fill={INK} />
      </pattern>
      <pattern id={`${id}-m`} width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="5" height="5" fill={PAPER} />
        <circle cx="2.5" cy="2.5" r="1.4" fill={INK} />
      </pattern>
      <pattern id={`${id}-d`} width="4.2" height="4.2" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <rect width="4.2" height="4.2" fill={INK} />
        <circle cx="2.1" cy="2.1" r="1" fill={PAPER} />
      </pattern>
    </defs>
  );
}

const fill = (id: string, tone: "l" | "m" | "d") => `url(#${id}-${tone})`;

/** Ritaglio bianco: la stessa forma, più spessa, sotto il disegno. */
const DIE = { fill: "#fff", stroke: "#fff", strokeWidth: 9, strokeLinejoin: "round" } as const;

function spire(x: number, top: number, bottom = 194, half = 4.6, taper = 24): string {
  return `M${x - half} ${bottom} V${top + taper} L${x} ${top} L${x + half} ${top + taper} V${bottom} Z`;
}

function Duomo({ id }: { readonly id: string }) {
  const pinnacles = [[16, 108], [43, 84], [70, 46], [98, 36], [142, 36], [170, 46], [197, 84], [224, 108]] as const;
  const shape = (
    <>
      <path d="M70 194 V86 L120 54 L170 86 V194 Z" />
      <path d="M16 194 V132 L70 100 V194 Z" />
      <path d="M224 194 V132 L170 100 V194 Z" />
      {pinnacles.map(([x, top]) => <path d={spire(x, top)} key={x} />)}
      <path d="M108 72 V54 L113 34 L118 16 L120 0 L122 16 L127 34 L132 54 V72 Z" />
    </>
  );
  const windows = [84, 102, 138, 156] as const;
  return (
    <>
      <path d="M2 196 V122 L28 106 L40 68 L62 92 L80 36 L102 66 L120 4 L138 66 L160 36 L178 92 L200 68 L212 106 L238 122 V196 Z" fill={YELLOW} transform="translate(5 5)" />
      <g {...DIE}>{shape}</g>
      <g fill={fill(id, "m")}>{shape}</g>
      <g fill={fill(id, "d")} clipPath={`url(#${id}-right)`}>{shape}</g>
      <clipPath id={`${id}-right`}><path d="M120 0 H240 V210 H120 Z" /></clipPath>
      <g fill={INK}>
        {windows.map((x) => <path d={`M${x - 5} 172 V140 Q${x - 5} 126 ${x} 118 Q${x + 5} 126 ${x + 5} 140 V172 Z`} key={x} />)}
        <path d="M112 194 V160 Q112 148 120 142 Q128 148 128 160 V194 Z" />
        {[34, 52, 188, 206].map((x) => <path d={`M${x - 4} 176 V152 Q${x - 4} 144 ${x} 140 Q${x + 4} 144 ${x + 4} 152 V176 Z`} key={x} />)}
        <circle cx="120" cy="100" r="9" />
        <circle cx="120" cy="6" r="2.6" />
      </g>
      <circle cx="120" cy="100" r="4.5" fill={PAPER} />
      <path d="M8 196 H232" stroke={INK} strokeWidth="5" strokeLinecap="round" />
    </>
  );
}

function Megaphone({ id }: { readonly id: string }) {
  const handle = <path d="M84 112 L112 120 L104 170 L74 160 Z" />;
  return (
    <g transform="rotate(-14 125 100)">
      <path d="M184 34 L212 20 L200 48 L232 38 L204 72 L222 76" fill="none" stroke={YELLOW} strokeWidth="7" strokeLinecap="round" strokeLinejoin="round" transform="translate(10 -2)" />
      <g {...DIE}>
        <path d="M52 82 L170 38 V142 L52 118 Z" />
        <ellipse cx="172" cy="90" rx="21" ry="54" />
        <rect x="30" y="80" width="28" height="42" rx="9" />
        {handle}
      </g>
      <path d="M52 82 L170 38 V142 L52 118 Z" fill={fill(id, "m")} />
      <path d="M52 104 L170 92 V142 L52 118 Z" fill={fill(id, "d")} />
      <rect x="30" y="80" width="28" height="42" rx="9" fill={INK} />
      <ellipse cx="172" cy="90" rx="21" ry="54" fill={INK} />
      <ellipse cx="170" cy="90" rx="15" ry="46" fill={fill(id, "l")} />
      <ellipse cx="166" cy="92" rx="8" ry="11" fill={INK} />
      <ellipse cx="163" cy="89" rx="3.5" ry="4.5" fill={PAPER} />
      <path d="M84 112 L112 120 L104 170 L74 160 Z" fill={INK} />
    </g>
  );
}

type Hair = "short" | "long" | "curly" | "bun";

/** Capelli: `back` sta dietro la testa (volume), `front` sopra (frangia). */
const HAIR: Record<Hair, { readonly back?: ReactNode; readonly front: ReactNode }> = {
  short: { front: <path d="M-18 -3 Q-21 -27 0 -27 Q21 -27 18 -3 Q9 -15 -3 -14 Q-12 -12 -18 -3 Z" /> },
  long: {
    back: <rect x="-22" y="-26" width="44" height="64" rx="16" />,
    front: <path d="M-18 -2 Q-20 -27 0 -27 Q20 -27 18 -2 Q8 -16 -5 -14 Q-13 -12 -18 -2 Z" />,
  },
  curly: {
    front: (
      <>
        <circle cx="-14" cy="-15" r="9" /><circle cx="0" cy="-21" r="10" /><circle cx="14" cy="-15" r="9" /><circle cx="-19" cy="-3" r="6.5" /><circle cx="19" cy="-3" r="6.5" />
      </>
    ),
  },
  bun: {
    back: <circle cx="0" cy="-30" r="9" />,
    front: <path d="M-18 -3 Q-20 -25 0 -25 Q20 -25 18 -3 Q8 -13 -2 -12 Q-12 -10 -18 -3 Z" />,
  },
};

/** Busto: la testa è l'origine; si disegna due volte (ritaglio bianco, poi retino). */
function Bust({ id, x, y, scale = 1, hair, face = "l", body = "d" }: { readonly id: string; readonly x: number; readonly y: number; readonly scale?: number; readonly hair: Hair; readonly face?: "l" | "m"; readonly body?: "m" | "d" }) {
  const shoulders = "M-36 80 Q-36 34 -14 30 L14 30 Q36 34 36 80 Z";
  const { back, front } = HAIR[hair];
  return (
    <g transform={`translate(${x} ${y}) scale(${scale})`}>
      <g {...DIE} strokeWidth={9 / scale}>
        {back}
        <path d={shoulders} />
        <rect x="-7" y="16" width="14" height="18" />
        <ellipse rx="17" ry="21" />
        {front}
      </g>
      <g fill={INK}>{back}</g>
      <path d={shoulders} fill={fill(id, body)} />
      <rect x="-7" y="16" width="14" height="18" fill={fill(id, "m")} />
      <ellipse rx="17" ry="21" fill={fill(id, face)} />
      <g fill={INK}>{front}</g>
    </g>
  );
}

/** Foglio strappato su cui poggia un busto: poligono irregolare con ritaglio bianco. */
function Scrap({ points, color, rotate = 0 }: { readonly points: string; readonly color: string; readonly rotate?: number }) {
  return (
    <g transform={`rotate(${rotate} 130 100)`}>
      <polygon points={points} {...DIE} />
      <polygon points={points} fill={color} />
    </g>
  );
}

function Duo({ id }: { readonly id: string }) {
  return (
    <>
      <Scrap points="10,40 70,32 124,38 120,100 126,176 66,184 14,178 18,110" color={PINK} rotate={-4} />
      <Scrap points="146,22 200,16 252,26 246,84 252,150 196,158 142,152 150,86" color={YELLOW} rotate={3} />
      <Bust id={id} x={66} y={104} scale={1.25} hair="long" />
      <Bust id={id} x={198} y={84} scale={1.2} hair="short" />
    </>
  );
}

function Crowd({ id }: { readonly id: string }) {
  return (
    <>
      <path d="M4 176 V110 L26 96 L38 60 L60 84 L78 32 L100 62 L134 4 L166 62 L190 30 L208 84 L232 58 L244 96 L266 110 V176 Z" fill={YELLOW} transform="translate(4 4)" />
      <clipPath id={`${id}-ground`}><rect x="-10" y="-20" width="300" height="196" /></clipPath>
      <g clipPath={`url(#${id}-ground)`}>
        <Bust id={id} x={40} y={112} scale={0.9} hair="bun" body="m" />
        <Bust id={id} x={226} y={106} scale={0.92} hair="curly" body="m" />
        <Bust id={id} x={98} y={98} scale={1.05} hair="short" />
        <Bust id={id} x={170} y={94} scale={1.1} hair="long" />
        <Bust id={id} x={134} y={132} scale={1.2} hair="curly" body="m" />
      </g>
      <path d="M6 180 H264" stroke={INK} strokeWidth="5" strokeLinecap="round" />
    </>
  );
}

export function EditorialIllustration({ kind, className = "" }: EditorialIllustrationProps) {
  const id = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  const scenes: Record<IllustrationKind, ReactNode> = {
    duomo: <Duomo id={id} />,
    megaphone: <Megaphone id={id} />,
    duo: <Duo id={id} />,
    crowd: <Crowd id={id} />,
  };
  return (
    <svg className={`ed-deco ed-illustration ed-illustration--${kind} ${className}`.trim()} viewBox={VIEWBOX[kind]} aria-hidden="true" focusable="false">
      <HalftoneDefs id={id} />
      {scenes[kind]}
    </svg>
  );
}

export const illustrationColors = { ink: INK, paper: PAPER, yellow: YELLOW, coral: CORAL, pink: PINK } as const;
