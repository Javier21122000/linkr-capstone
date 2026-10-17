import type { ChangeEventHandler, HTMLInputAutoCompleteAttribute, HTMLInputTypeAttribute } from "react";

interface FormFieldProps {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly onChange: ChangeEventHandler<HTMLInputElement>;
  readonly type?: HTMLInputTypeAttribute;
  readonly autoComplete?: HTMLInputAutoCompleteAttribute;
  readonly error?: string;
  readonly hint?: string;
  readonly maxLength?: number;
  readonly disabled?: boolean;
}

export function FormField({
  id,
  label,
  value,
  onChange,
  type = "text",
  autoComplete,
  error,
  hint,
  maxLength,
  disabled = false,
}: FormFieldProps) {
  const descriptionId = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div>
      <label className="form-label" htmlFor={id}>{label}</label>
      <input
        id={id}
        name={id}
        className="form-input mt-2"
        type={type}
        value={value}
        onChange={onChange}
        autoComplete={autoComplete}
        aria-invalid={error ? true : undefined}
        aria-describedby={descriptionId}
        maxLength={maxLength}
        disabled={disabled}
      />
      {error ? <p id={`${id}-error`} className="field-error mt-2">{error}</p> : null}
      {!error && hint ? <p id={`${id}-hint`} className="mt-2 text-xs leading-5 text-muted">{hint}</p> : null}
    </div>
  );
}
