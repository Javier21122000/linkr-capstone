package com.linkr.backend.invite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/** Token d'invito: firma, evento, mittente e scadenza a 30 giorni. Senza database e senza Spring. */
class InviteTokenServiceTest {

	private static final byte[] SECRET = "test-secret-with-more-than-thirty-two-characters!".getBytes(StandardCharsets.UTF_8);
	private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
	private static final UUID EVENT = UUID.fromString("018f0000-0000-7000-8000-000000000001");
	private static final UUID SENDER = UUID.fromString("018f0000-0000-7000-8000-0000000000aa");

	private static InviteTokenService service(Instant now) {
		return service(SECRET, now);
	}

	private static InviteTokenService service(byte[] secret, Instant now) {
		return new InviteTokenService(new SecretKeySpec(secret, "HmacSHA256"), Clock.fixed(now, ZoneOffset.UTC));
	}

	@Test
	void aValidTokenCarriesTheEventTheSenderAndAThirtyDayExpiry() {
		InviteTokenService tokens = service(NOW);
		InviteTokenService.IssuedToken issued = tokens.issue(EVENT, SENDER);

		assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
		InviteClaims claims = tokens.verify(issued.token()).orElseThrow();
		assertThat(claims.eventId()).isEqualTo(EVENT);
		assertThat(claims.senderId()).isEqualTo(SENDER);
		assertThat(claims.expiresAt()).isEqualTo(issued.expiresAt());
		assertThat(issued.token()).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").hasSizeLessThan(120);
	}

	@Test
	void expiresAfterThirtyDaysExactly() {
		String token = service(NOW).issue(EVENT, SENDER).token();
		Instant expiry = NOW.plus(Duration.ofDays(30));
		assertThat(service(expiry.minusSeconds(1)).verify(token)).isPresent();
		assertThat(service(expiry).verify(token)).isEmpty();
		assertThat(service(expiry.plus(Duration.ofDays(1))).verify(token)).isEmpty();
	}

	@Test
	void aTokenIsBoundToItsEventAndItsSender() {
		InviteTokenService tokens = service(NOW);
		String token = tokens.issue(EVENT, SENDER).token();
		String[] parts = token.split("\\.");
		byte[] payload = Base64.getUrlDecoder().decode(parts[0]);

		// cambiare un solo bit dell'evento, del mittente o della scadenza invalida la firma
		for (int index : new int[] {1, 8, 16, 17, 24, 32, 33, 40}) {
			byte[] altered = payload.clone();
			altered[index] ^= 0x01;
			String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(altered) + "." + parts[1];
			assertThat(tokens.verify(forged)).as("byte %d", index).isEmpty();
		}
		// stessa firma su un altro mittente: impersonificazione tramite modifica del link
		byte[] otherSender = payload.clone();
		UUID attacker = UUID.fromString("018f0000-0000-7000-8000-0000000000bb");
		java.nio.ByteBuffer.wrap(otherSender, 17, 16).putLong(attacker.getMostSignificantBits()).putLong(attacker.getLeastSignificantBits());
		assertThat(tokens.verify(Base64.getUrlEncoder().withoutPadding().encodeToString(otherSender) + "." + parts[1])).isEmpty();
	}

	@Test
	void signatureAndFormatAreChecked() {
		InviteTokenService tokens = service(NOW);
		String token = tokens.issue(EVENT, SENDER).token();
		String[] parts = token.split("\\.");

		assertThat(tokens.verify(parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA")).isEmpty();
		assertThat(tokens.verify(parts[0] + ".")).isEmpty();
		assertThat(tokens.verify("." + parts[1])).isEmpty();
		assertThat(tokens.verify(parts[0])).isEmpty();
		assertThat(tokens.verify(token + "." + parts[1])).isEmpty();
		assertThat(tokens.verify(parts[1] + "." + parts[0])).isEmpty();
		assertThat(tokens.verify("")).isEmpty();
		assertThat(tokens.verify(null)).isEmpty();
		assertThat(tokens.verify("not a token!")).isEmpty();
		assertThat(tokens.verify("a".repeat(5000))).isEmpty();
		assertThat(tokens.verify("%%%.%%%")).isEmpty();
	}

	@Test
	void aTokenSignedWithAnotherSecretIsRejected() {
		byte[] other = "another-secret-with-more-than-thirty-two-chars".getBytes(StandardCharsets.UTF_8);
		assertThat(service(other, NOW).verify(service(NOW).issue(EVENT, SENDER).token())).isEmpty();
	}

	@Test
	void theInviteKeyIsNotTheJwtSecretSoTheTwoNeverInterchange() throws Exception {
		InviteTokenService tokens = service(NOW);
		byte[] payload = Base64.getUrlDecoder().decode(tokens.issue(EVENT, SENDER).token().split("\\.")[0]);

		// una firma calcolata con il segreto del JWT "tale e quale" non vale come firma d'invito
		Mac jwtMac = Mac.getInstance("HmacSHA256");
		jwtMac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
		String withJwtKey = Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(jwtMac.doFinal(payload));
		assertThat(tokens.verify(withJwtKey)).isEmpty();

		// e la firma di un invito non e' la firma HS256 di un JWT con quel contenuto
		String token = tokens.issue(EVENT, SENDER).token();
		byte[] inviteSignature = Base64.getUrlDecoder().decode(token.split("\\.")[1]);
		String signingInput = "eyJhbGciOiJIUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
		assertThat(inviteSignature).isNotEqualTo(jwtMac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
	}

}
