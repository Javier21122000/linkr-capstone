package com.linkr.backend.websocket;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP su WebSocket nativo per le NOTIFICHE della chat. Architettura semplice per una sola istanza: broker in memoria.
 *
 *  - endpoint: /ws (nessun SockJS). L'handshake HTTP e' pubblico (il browser non puo' mettere header sul WebSocket):
 *    l'autenticazione avviene nel frame CONNECT, vedi StompAuthChannelInterceptor.
 *  - origini ammesse: le stesse di CORS (app.cors.allowed-origins), mai "*".
 *  - coda privata: /user/queue/messages. Il client non puo' inviare nulla (SEND rifiutato): scrive via REST.
 *  - un socket che non invia CONNECT entro 15 secondi viene chiuso.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	private final String[] allowedOrigins;
	private final StompAuthChannelInterceptor authInterceptor;
	private final JsonMapper jsonMapper;

	public WebSocketConfig(@Value("${app.cors.allowed-origins}") String[] allowedOrigins,
			StompAuthChannelInterceptor authInterceptor, JsonMapper jsonMapper) {
		this.allowedOrigins = allowedOrigins;
		this.authInterceptor = authInterceptor;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void registerStompEndpoints(StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
	}

	@Override
	public void configureMessageBroker(MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/queue")
				.setHeartbeatValue(new long[] { 10_000, 10_000 })
				.setTaskScheduler(webSocketHeartbeatScheduler());
		registry.setApplicationDestinationPrefixes("/app");   // nessun @MessageMapping esiste: ogni SEND e' comunque rifiutato
		registry.setUserDestinationPrefix("/user");
	}

	@Override
	public void configureClientInboundChannel(ChannelRegistration registration) {
		registration.interceptors(authInterceptor);
	}

	@Override
	public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
		registration.setMessageSizeLimit(16 * 1024)           // il client invia solo CONNECT/SUBSCRIBE: bastano pochi KB
				.setSendBufferSizeLimit(512 * 1024)
				.setSendTimeLimit(20_000)
				.setTimeToFirstMessage(15_000);
	}

	/** Stesso JSON delle risposte REST (date ISO con offset di Milano). */
	@Override
	public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
		messageConverters.add(new JacksonJsonMessageConverter(jsonMapper));
		return false;
	}

	@Bean
	ThreadPoolTaskScheduler webSocketHeartbeatScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("ws-heartbeat-");
		return scheduler;
	}

}
