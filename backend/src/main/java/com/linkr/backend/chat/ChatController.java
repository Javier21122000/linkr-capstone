package com.linkr.backend.chat;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.linkr.backend.chat.dto.ConversationResponse;
import com.linkr.backend.chat.dto.MessagePageResponse;
import com.linkr.backend.chat.dto.MessageResponse;
import com.linkr.backend.chat.dto.SendMessageRequest;
import com.linkr.backend.common.page.PageResponse;

import jakarta.validation.Valid;

/**
 * Chat privata tra Link accettati. Tutte le rotte richiedono il JWT (vedi SecurityConfig, "anyRequest"): l'utente arriva
 * sempre dal token e il service verifica che faccia parte della conversazione.
 */
@RestController
@RequestMapping("/api/conversations")
public class ChatController {

	private final ChatService service;

	public ChatController(ChatService service) {
		this.service = service;
	}

	/** Esempio: GET /api/conversations?page=0&size=20 */
	@GetMapping
	public PageResponse<ConversationResponse> conversations(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageResponse.DEFAULT_SIZE) int size) {
		return service.listConversations(userId(jwt), page, size);
	}

	/** Esempio: GET /api/conversations/{id}/messages?before={messageId}&size=30 (dal piu' recente). */
	@GetMapping("/{id}/messages")
	public MessagePageResponse messages(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@RequestParam(required = false) UUID before,
			@RequestParam(defaultValue = "" + ChatService.DEFAULT_MESSAGES_SIZE) int size) {
		return service.messages(userId(jwt), id, before, size);
	}

	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public MessageResponse send(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody SendMessageRequest request) {
		return service.send(userId(jwt), id, request.body());
	}

	/** Segna come letti i messaggi ricevuti. Idempotente. */
	@PostMapping("/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		service.markRead(userId(jwt), id);
	}

	private UUID userId(Jwt jwt) {
		return UUID.fromString(jwt.getSubject());
	}

}
