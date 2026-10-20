package com.linkr.backend.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Messaggio da inviare: testo non vuoto, al massimo 2000 caratteri. Il mittente e' sempre l'utente del JWT. */
public record SendMessageRequest(@NotBlank @Size(max = 2000) String body) {
}
