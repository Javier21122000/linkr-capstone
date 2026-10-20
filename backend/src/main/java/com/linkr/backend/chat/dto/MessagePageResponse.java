package com.linkr.backend.chat.dto;

import java.util.List;

/**
 * Pagina di messaggi, dal piu' RECENTE al piu' vecchio. Per caricare i precedenti: before = messageId dell'ULTIMO elemento
 * (il piu' vecchio) e si richiede finche' hasMore e' true.
 */
public record MessagePageResponse(List<MessageResponse> items, boolean hasMore) {
}
