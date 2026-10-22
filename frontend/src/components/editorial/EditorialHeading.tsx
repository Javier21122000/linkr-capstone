import type { ReactNode } from "react";

/**
 * Titolo editoriale: kicker a nastro + titolo in Fraunces con una parola evidenziata a pennarello.
 * Il markup è quello di un normale titolo (nel tema base resta con i suoi stili); la variante Urban Editorial Pop lo trasforma via CSS.
 */
interface EditorialHeadingProps {
  readonly as?: "h1" | "h2";
  readonly id: string;
  readonly kicker?: string;
  /** Testo prima della parola in evidenza. */
  readonly lead?: ReactNode;
  /** La parola (o le parole) evidenziata. */
  readonly emphasis?: ReactNode;
  readonly size?: "hero" | "title";
  readonly className?: string;
  readonly children?: ReactNode;
}

export function EditorialHeading({ as: Tag = "h1", id, kicker, lead, emphasis, size = "hero", className = "", children }: EditorialHeadingProps) {
  return (
    <>
      {kicker ? <p className="section-kicker ed-kicker">{kicker}</p> : null}
      <Tag id={id} className={`ed-heading ed-heading--${size} ${className}`.trim()}>
        {lead}
        {lead && emphasis ? " " : null}
        {emphasis ? <span className="ed-em">{emphasis}</span> : null}
        {children}
      </Tag>
    </>
  );
}
