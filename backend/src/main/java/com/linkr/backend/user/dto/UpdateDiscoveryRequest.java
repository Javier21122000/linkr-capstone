package com.linkr.backend.user.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Attiva o disattiva la propria visibilita' in Discover People. Il valore e' obbligatorio e deve essere un vero booleano JSON:
 * niente default implicito e niente conversioni (1, "true"...), perche' e' un'impostazione di privacy.
 */
public record UpdateDiscoveryRequest(@JsonDeserialize(using = StrictBooleanDeserializer.class) @NotNull Boolean discoverable) {
}
