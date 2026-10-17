import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import type { ChangeEventHandler, HTMLInputAutoCompleteAttribute } from "react";

interface PasswordFieldProps {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly onChange: ChangeEventHandler<HTMLInputElement>;
  readonly autoComplete: HTMLInputAutoCompleteAttribute;
  readonly error?: string;
  readonly hint?: string;
  readonly disabled?: boolean;
}

export function PasswordField({ id, label, value, onChange, autoComplete, error, hint, disabled = false }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  const descriptionId = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div>
      <label className="form-label" htmlFor={id}>{label}</label>
      <div className="relative mt-2">
        <input
          id={id}
          name={id}
          className="form-input pr-14"
          type={visible ? "text" : "password"}
          value={value}
          onChange={onChange}
          autoComplete={autoComplete}
          aria-invalid={error ? true : undefined}
          aria-describedby={descriptionId}
          maxLength={72}
          disabled={disabled}
        />
        <button
          type="button"
          className="absolute right-1.5 top-1/2 grid size-10 -translate-y-1/2 place-items-center rounded-lg text-muted"
          onClick={() => setVisible((current) => !current)}
          aria-label={visible ? "Nascondi password" : "Mostra password"}
          aria-pressed={visible}
          disabled={disabled}
        >
          {visible ? <EyeOff size={19} aria-hidden="true" /> : <Eye size={19} aria-hidden="true" />}
        </button>
      </div>
      {error ? <p id={`${id}-error`} className="field-error mt-2">{error}</p> : null}
      {!error && hint ? <p id={`${id}-hint`} className="mt-2 text-xs leading-5 text-muted">{hint}</p> : null}
    </div>
  );
}
