package com.linkr.backend.invite;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * Token d'invito firmati (HMAC-SHA256), senza stato: nessuna tabella. Un token afferma "questo utente ha condiviso questo evento" ed e'
 * emesso solo dal servizio, per l'utente autenticato che condivide (mai da un parametro arbitrario).
 *
 * Formato: {@code base64url(versione | eventId | senderId | scadenza) . base64url(HMAC)}, 41 byte di dati + 32 di firma.
 *
 * Chiave: NON e' il segreto del JWT tale e quale, ma una chiave derivata da esso con HKDF e un'etichetta propria ("linkr/invite-token/v1").
 * Cosi' un token d'invito non si puo' usare come JWT (ne' viceversa), e la firma di un invito non rivela nulla sul segreto. Non serve
 * nessuna nuova configurazione; cambiare LINKR_JWT_SECRET invalida anche gli inviti gia' condivisi (come le sessioni).
 *
 * L'identificativo del mittente sta nei dati del token, leggibile da chi ha il link (e' un UUID, non un nome): l'identita' vera si
 * mostra solo tramite {@code GET /api/invites/{token}}, che applica la visibilita' del mittente.
 */
@Service
public class InviteTokenService {

	/** Quanto vale un token dal momento dell'emissione. */
	public static final Duration VALIDITY = Duration.ofDays(30);

	private static final byte VERSION = 1;
	private static final int PAYLOAD_LENGTH = 1 + 16 + 16 + 8;
	private static final int SIGNATURE_LENGTH = 32;
	/** Un token ben formato e' lungo ~100 caratteri: oltre questo limite non si decodifica nemmeno. */
	private static final int MAX_TOKEN_LENGTH = 160;
	private static final String HMAC = "HmacSHA256";
	private static final byte[] KEY_PURPOSE = Hkdf.utf8("linkr/invite-token/v1");

	private final SecretKey signingKey;
	private final Clock clock;

	public InviteTokenService(SecretKey jwtSecretKey, Clock clock) {
		this.signingKey = new SecretKeySpec(Hkdf.derive(jwtSecretKey.getEncoded(), null, KEY_PURPOSE, 32), HMAC);
		this.clock = clock;
	}

	public IssuedToken issue(UUID eventId, UUID senderId) {
		Instant expiresAt = clock.instant().plus(VALIDITY).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		byte[] payload = ByteBuffer.allocate(PAYLOAD_LENGTH)
				.put(VERSION)
				.putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits())
				.putLong(senderId.getMostSignificantBits()).putLong(senderId.getLeastSignificantBits())
				.putLong(expiresAt.getEpochSecond())
				.array();
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		return new IssuedToken(encoder.encodeToString(payload) + "." + encoder.encodeToString(sign(payload)), expiresAt);
	}

	/** Le claim del token se firma, forma e scadenza sono valide; altrimenti vuoto, senza distinguere il motivo. */
	Optional<InviteClaims> verify(String token) {
		if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
			return Optional.empty();
		}
		int dot = token.indexOf('.');
		if (dot < 1 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
			return Optional.empty();
		}
		byte[] payload;
		byte[] signature;
		try {
			Base64.Decoder decoder = Base64.getUrlDecoder();
			payload = decoder.decode(token.substring(0, dot));
			signature = decoder.decode(token.substring(dot + 1));
		}
		catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		if (payload.length != PAYLOAD_LENGTH || signature.length != SIGNATURE_LENGTH || payload[0] != VERSION) {
			return Optional.empty();
		}
		// Confronto a tempo costante: la verifica non rivela quanti byte della firma sono giusti.
		if (!MessageDigest.isEqual(sign(payload), signature)) {
			return Optional.empty();
		}
		ByteBuffer buffer = ByteBuffer.wrap(payload, 1, PAYLOAD_LENGTH - 1);
		UUID eventId = new UUID(buffer.getLong(), buffer.getLong());
		UUID senderId = new UUID(buffer.getLong(), buffer.getLong());
		Instant expiresAt = Instant.ofEpochSecond(buffer.getLong());
		if (!clock.instant().isBefore(expiresAt)) {
			return Optional.empty();
		}
		return Optional.of(new InviteClaims(eventId, senderId, expiresAt));
	}

	private byte[] sign(byte[] payload) {
		try {
			Mac mac = Mac.getInstance(HMAC);
			mac.init(signingKey);
			return mac.doFinal(payload);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	public record IssuedToken(String token, Instant expiresAt) {
	}

}
