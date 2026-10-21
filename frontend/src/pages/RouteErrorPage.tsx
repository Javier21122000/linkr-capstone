import { useEffect } from "react";
import { useRouteError } from "react-router";

import { ErrorScreen } from "../components/ui/ErrorScreen";

/** `errorElement` delle rotte: errori di rendering di una pagina e codice di una pagina che non si riesce a scaricare. */
export function RouteErrorPage({ inline = false }: { readonly inline?: boolean }) {
  const error = useRouteError();

  useEffect(() => {
    console.error("Route error", error);
  }, [error]);

  return <ErrorScreen error={error} inline={inline} />;
}
