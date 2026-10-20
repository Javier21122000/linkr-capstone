package com.linkr.backend.user.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Accetta solo i booleani JSON veri (true/false): rifiuta 1, 0, "true", "yes"... Il null lo gestisce @NotNull. */
public class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {

	@Override
	public Boolean deserialize(JsonParser parser, DeserializationContext context) {
		JsonToken token = parser.currentToken();
		if (token == JsonToken.VALUE_TRUE) {
			return Boolean.TRUE;
		}
		if (token == JsonToken.VALUE_FALSE) {
			return Boolean.FALSE;
		}
		return (Boolean) context.handleUnexpectedToken(Boolean.class, parser);
	}

}
