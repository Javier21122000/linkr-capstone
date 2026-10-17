import { useState } from "react";
import type { FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";

import { AuthLayout } from "../components/auth/AuthLayout";
import { FormField } from "../components/auth/FormField";
import { PasswordField } from "../components/auth/PasswordField";
import { useAuth } from "../context/AuthContext";
import { getAuthRequestErrorMessage, getProblemDetail, login } from "../lib/auth";
import { getSafeReturnPath } from "../lib/navigation";

interface LoginErrors {
  readonly email?: string;
  readonly password?: string;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function validate(email: string, password: string): LoginErrors {
  const errors: { email?: string; password?: string } = {};
  const normalizedEmail = email.trim();

  if (!normalizedEmail) errors.email = "Inserisci la tua email.";
  else if (normalizedEmail.length > 254 || !EMAIL_PATTERN.test(normalizedEmail)) errors.email = "Inserisci un indirizzo email valido.";

  if (!password) errors.password = "Inserisci la password.";
  else if (password.length > 72) errors.password = "La password non può superare 72 caratteri.";

  return errors;
}

export function LoginPage() {
  const { status, authenticate } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<LoginErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (status === "authenticated") return <Navigate to={getSafeReturnPath(location.state)} replace />;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const validationErrors = validate(email, password);
    setErrors(validationErrors);
    setFormError(null);
    if (Object.keys(validationErrors).length > 0) return;

    setSubmitting(true);
    try {
      const response = await login({ email: email.trim(), password });
      authenticate(response);
      navigate(getSafeReturnPath(location.state), { replace: true });
    } catch (error) {
      const problem = getProblemDetail(error);
      if (problem?.status === 401) {
        setFormError("Email o password non corrette.");
      } else if (problem?.status === 400) {
        setFormError("Controlla i dati inseriti e riprova.");
      } else {
        setFormError(getAuthRequestErrorMessage(error));
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      title="Accedi a LINKR"
      description="Gli eventi sono aperti a tutti. Accedi per raggiungere il tuo spazio personale."
    >
      <form className="space-y-5" onSubmit={handleSubmit} noValidate>
        {formError ? <div className="form-message form-message--error" role="alert">{formError}</div> : null}

        <FormField
          id="email"
          label="Email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={errors.email}
          maxLength={254}
          disabled={submitting}
        />
        <PasswordField
          id="password"
          label="Password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.password}
          disabled={submitting}
        />

        <button className="button-primary flex w-full items-center" type="submit" disabled={submitting}>
          {submitting ? "Accesso in corso…" : "Accedi"}
        </button>

        <p className="text-center text-sm leading-6 text-muted">
          Non hai un account?{" "}
          <Link className="font-extrabold text-ink underline decoration-linkr/50 underline-offset-4" to="/register" state={location.state}>
            Registrati
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}
