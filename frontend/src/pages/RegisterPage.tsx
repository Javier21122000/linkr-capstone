import { useRef, useState } from "react";
import type { FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";

import { AuthLayout } from "../components/auth/AuthLayout";
import { FormField } from "../components/auth/FormField";
import { PasswordField } from "../components/auth/PasswordField";
import { useAuth } from "../context/AuthContext";
import { getAuthRequestErrorMessage, getProblemDetail, register } from "../lib/auth";
import { getSafeReturnPath } from "../lib/navigation";

interface RegistrationValues {
  readonly displayName: string;
  readonly username: string;
  readonly email: string;
  readonly password: string;
  readonly confirmPassword: string;
}

type RegistrationErrors = Partial<Record<keyof RegistrationValues, string>>;

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const USERNAME_PATTERN = /^[A-Za-z0-9_.]+$/;

const backendFieldMessages: Readonly<Record<string, string>> = {
  displayName: "Inserisci un nome di massimo 60 caratteri.",
  username: "Usa da 3 a 30 caratteri: lettere, numeri, punto o trattino basso.",
  email: "Inserisci un indirizzo email valido.",
  password: "Usa una password da 8 a 72 caratteri.",
};

function validate(values: RegistrationValues): RegistrationErrors {
  const errors: RegistrationErrors = {};
  const displayName = values.displayName.trim();
  const username = values.username.trim();
  const email = values.email.trim();

  if (!displayName) errors.displayName = "Inserisci il tuo nome.";
  else if (displayName.length > 60) errors.displayName = "Il nome non può superare 60 caratteri.";

  if (!username) errors.username = "Scegli uno username.";
  else if (username.length < 3 || username.length > 30 || !USERNAME_PATTERN.test(username)) {
    errors.username = backendFieldMessages.username;
  }

  if (!email) errors.email = "Inserisci la tua email.";
  else if (email.length > 254 || !EMAIL_PATTERN.test(email)) errors.email = backendFieldMessages.email;

  if (!values.password) errors.password = "Crea una password.";
  else if (values.password.length < 8 || values.password.length > 72) errors.password = backendFieldMessages.password;

  if (!values.confirmPassword) errors.confirmPassword = "Conferma la password.";
  else if (values.confirmPassword !== values.password) errors.confirmPassword = "Le password non coincidono.";

  return errors;
}

export function RegisterPage() {
  const { status, authenticate } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const registrationSucceededRef = useRef(false);
  const [values, setValues] = useState<RegistrationValues>({ displayName: "", username: "", email: "", password: "", confirmPassword: "" });
  const [errors, setErrors] = useState<RegistrationErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === "authenticated" && !registrationSucceededRef.current) {
    return <Navigate to={getSafeReturnPath(location.state)} replace />;
  }

  function updateValue(field: keyof RegistrationValues, value: string) {
    setValues((current) => ({ ...current, [field]: value }));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validationErrors = validate(values);
    setErrors(validationErrors);
    setFormError(null);
    if (Object.keys(validationErrors).length > 0) return;

    setSubmitting(true);
    try {
      const response = await register({
        displayName: values.displayName.trim(),
        username: values.username.trim(),
        email: values.email.trim(),
        password: values.password,
      });
      registrationSucceededRef.current = true;
      authenticate(response);
      navigate("/profile/interests", { replace: true, state: { onboarding: true } });
    } catch (error) {
      const problem = getProblemDetail(error);
      if (problem?.status === 409) {
        if (problem.field === "email") setErrors({ email: "Esiste già un account con questa email." });
        else if (problem.field === "username") setErrors({ username: "Questo username è già in uso." });
        else setFormError("Email o username sono già in uso.");
      } else if (problem?.status === 400 && problem.errors) {
        const fieldErrors: RegistrationErrors = {};
        for (const field of Object.keys(problem.errors)) {
          if (field in backendFieldMessages) fieldErrors[field as keyof RegistrationValues] = backendFieldMessages[field];
        }
        setErrors(fieldErrors);
        if (Object.keys(fieldErrors).length === 0) setFormError("Controlla i dati inseriti e riprova.");
      } else {
        setFormError(getAuthRequestErrorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Crea il tuo profilo"
      description="Bastano pochi dati. Dopo la registrazione entrerai automaticamente nel tuo spazio LINKR."
    >
      <form className="space-y-5" onSubmit={handleSubmit} noValidate>
        {formError ? <div className="form-message form-message--error" role="alert">{formError}</div> : null}

        <FormField
          id="displayName"
          label="Nome visualizzato"
          autoComplete="name"
          value={values.displayName}
          onChange={(event) => updateValue("displayName", event.target.value)}
          error={errors.displayName}
          maxLength={60}
          disabled={submitting}
        />
        <FormField
          id="username"
          label="Username"
          autoComplete="username"
          value={values.username}
          onChange={(event) => updateValue("username", event.target.value)}
          error={errors.username}
          hint="Da 3 a 30 caratteri: lettere, numeri, punto o trattino basso."
          maxLength={30}
          disabled={submitting}
        />
        <FormField
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={values.email}
          onChange={(event) => updateValue("email", event.target.value)}
          error={errors.email}
          maxLength={254}
          disabled={submitting}
        />
        <PasswordField
          id="password"
          label="Password"
          autoComplete="new-password"
          value={values.password}
          onChange={(event) => updateValue("password", event.target.value)}
          error={errors.password}
          hint="Da 8 a 72 caratteri."
          disabled={submitting}
        />
        <PasswordField
          id="confirmPassword"
          label="Conferma password"
          autoComplete="new-password"
          value={values.confirmPassword}
          onChange={(event) => updateValue("confirmPassword", event.target.value)}
          error={errors.confirmPassword}
          disabled={submitting}
        />

        <button className="button-primary flex w-full items-center" type="submit" disabled={submitting}>
          {submitting ? "Creazione account…" : "Crea account"}
        </button>

        <p className="text-center text-sm leading-6 text-muted">
          Hai già un account?{" "}
          <Link className="font-extrabold text-ink underline decoration-linkr/50 underline-offset-4" to="/login" state={location.state}>
            Accedi
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}
