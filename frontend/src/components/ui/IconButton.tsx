import type { ButtonHTMLAttributes, ReactNode, Ref } from "react";

interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, "aria-label"> {
  /** Obbligatorio: il pulsante non ha testo visibile. */
  readonly label: string;
  readonly variant?: "solid" | "glass" | "bare" | "accent";
  readonly ref?: Ref<HTMLButtonElement>;
  readonly children: ReactNode;
}

const variantClass = {
  solid: "icon-button",
  glass: "icon-button icon-button--glass",
  bare: "icon-button icon-button--bare",
  accent: "icon-button icon-button--accent",
} as const;

export function IconButton({ label, variant = "solid", className = "", type = "button", children, ...props }: IconButtonProps) {
  return (
    <button {...props} type={type} className={`${variantClass[variant]} ${className}`.trim()} aria-label={label}>
      {children}
    </button>
  );
}
