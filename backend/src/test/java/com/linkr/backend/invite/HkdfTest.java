package com.linkr.backend.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;

import org.junit.jupiter.api.Test;

/** HKDF-SHA256 con i vettori di prova dell'RFC 5869 (appendice A): la derivazione e' quella standard, non un'invenzione. */
class HkdfTest {

	private static final HexFormat HEX = HexFormat.of();

	@Test
	void rfc5869TestCase1() {
		byte[] okm = Hkdf.derive(HEX.parseHex("0b".repeat(22)), HEX.parseHex("000102030405060708090a0b0c"),
				HEX.parseHex("f0f1f2f3f4f5f6f7f8f9"), 42);
		assertThat(HEX.formatHex(okm)).isEqualTo("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
	}

	@Test
	void rfc5869TestCase3EmptySaltAndInfo() {
		byte[] okm = Hkdf.derive(HEX.parseHex("0b".repeat(22)), new byte[0], new byte[0], 42);
		assertThat(HEX.formatHex(okm)).isEqualTo("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8");
		assertThat(Hkdf.derive(HEX.parseHex("0b".repeat(22)), null, new byte[0], 42)).isEqualTo(okm);
	}

	@Test
	void differentPurposesGiveUnrelatedKeys() {
		byte[] secret = Hkdf.utf8("a-secret-of-at-least-thirty-two-characters!");
		byte[] invite = Hkdf.derive(secret, null, Hkdf.utf8("linkr/invite-token/v1"), 32);
		byte[] other = Hkdf.derive(secret, null, Hkdf.utf8("linkr/other/v1"), 32);
		assertThat(invite).hasSize(32).isNotEqualTo(other).isNotEqualTo(secret);
		assertThat(Hkdf.derive(secret, null, Hkdf.utf8("linkr/invite-token/v1"), 32)).isEqualTo(invite);
	}

	@Test
	void rejectsImpossibleLengths() {
		assertThatThrownBy(() -> Hkdf.derive(new byte[32], null, new byte[0], 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> Hkdf.derive(new byte[32], null, new byte[0], 255 * 32 + 1)).isInstanceOf(IllegalArgumentException.class);
	}

}
