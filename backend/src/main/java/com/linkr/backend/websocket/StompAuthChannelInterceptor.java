package com.linkr.backend.websocket;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Sicurezza dei frame STOMP in ingresso (dal client al server).
 *
 *  - CONNECT: obbligatorio l'header "Authorization: Bearer <jwt>", verificato dallo STESSO decoder dell'API REST (firma,
 *    scadenza, issuer). Il token NON viaggia nell'URL (finirebbe nei log): solo nel frame CONNECT. Senza token valido la
 *    connessione viene chiusa con un ERROR. L'identita' della sessione e' il "sub" del token (id utente).
 *  - SUBSCRIBE: consentito solo a "/user/queue/messages" (la propria coda privata: il broker la risolve per utente, quindi
 *    nessuno puo' ascoltare la coda di un altro). Ogni altra destinazione e' rifiutata.
 *  - SEND: sempre rifiutato. Il client scrive i messaggi SOLO via REST, dove vivono tutte le regole di autorizzazione;
 *    il WebSocket serve soltanto a ricevere notifiche.
 *  - Dopo la scadenza del token la sessione non puo' piu' sottoscrivere (e va riaperta con un token nuovo).
 *
 * I messaggi di errore sono fissi: non rivelano il motivo preciso del rifiuto.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

	/** Unica destinazione a cui ci si puo' iscrivere. */
	public static final String USER_QUEUE = "/user/queue/messages";

	private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
	private static final String EXPIRES_AT = "linkr.jwt.expiresAt";
	private static final Set<StompCommand> ALLOWED = Set.of(StompCommand.CONNECT, StompCommand.STOMP, StompCommand.SUBSCRIBE,
			StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT);

	private final JwtDecoder jwtDecoder;
	private final Clock clock;

	public StompAuthChannelInterceptor(JwtDecoder jwtDecoder, Clock clock) {
		this.jwtDecoder = jwtDecoder;
		this.clock = clock;
	}

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
		if (accessor == null || accessor.getCommand() == null) {
			return message;                                   // heartbeat: nessun comando
		}
		StompCommand command = accessor.getCommand();
		if (!ALLOWED.contains(command)) {
			throw reject("Forbidden");                        // SEND, ACK, BEGIN... non servono e non sono consentiti
		}
		switch (command) {
			case CONNECT, STOMP -> authenticate(accessor);
			case SUBSCRIBE -> {
				requireLiveSession(accessor);
				if (!USER_QUEUE.equals(accessor.getDestination())) {
					throw reject("Forbidden");
				}
			}
			default -> {
				// UNSUBSCRIBE e DISCONNECT: niente da controllare
			}
		}
		return message;
	}

	private void authenticate(StompHeaderAccessor accessor) {
		String header = accessor.getFirstNativeHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7) || header.length() <= 7) {
			throw reject("Unauthorized");
		}
		try {
			Jwt jwt = jwtDecoder.decode(header.substring(7).trim());
			UUID.fromString(jwt.getSubject());                // il "sub" deve essere un id utente
			List<SimpleGrantedAuthority> authorities = jwt.getClaimAsStringList("roles") == null ? List.of()
					: jwt.getClaimAsStringList("roles").stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
			accessor.setUser(new UsernamePasswordAuthenticationToken(jwt.getSubject(), null, authorities));
			Map<String, Object> session = accessor.getSessionAttributes();
			if (session != null) {
				session.put(EXPIRES_AT, jwt.getExpiresAt());
			}
		}
		catch (JwtException | IllegalArgumentException e) {
			log.debug("Rejected STOMP CONNECT: invalid token");     // niente token ne' motivo nei log
			throw reject("Unauthorized");
		}
	}

	/** Sessione autenticata e token non scaduto. */
	private void requireLiveSession(StompHeaderAccessor accessor) {
		if (accessor.getUser() == null) {
			throw reject("Unauthorized");
		}
		Map<String, Object> session = accessor.getSessionAttributes();
		Object expiresAt = session == null ? null : session.get(EXPIRES_AT);
		if (!(expiresAt instanceof Instant instant) || !clock.instant().isBefore(instant)) {
			throw reject("Unauthorized");
		}
	}

	private MessagingException reject(String reason) {
		return new MessagingException(reason);
	}

}
