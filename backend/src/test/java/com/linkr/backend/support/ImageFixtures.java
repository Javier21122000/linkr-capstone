package com.linkr.backend.support;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/**
 * File di prova per i test degli upload. PNG e JPEG li produce il JDK (encoder veri); i WebP sono file REALI generati con
 * cwebp (24x16 pixel) e verificati con dwebp, incorporati come base64 perche' il JDK non sa scrivere WebP.
 * Gli altri formati (SVG, GIF, PDF, ZIP...) sono i byte minimi che li identificano.
 */
public final class ImageFixtures {

	public static final int WIDTH = 24;
	public static final int HEIGHT = 16;

	private static final String WEBP_LOSSY = "UklGRrIAAABXRUJQVlA4IKYAAACQBgCdASoYABAAPm0skkWkIqGYBABABsS2AE6ZQjwnyv8M6MB5gP4BzgH9A0f/o//MA3hP/i/03rQASc4AAP7q3gN/jWyuBfMUjGKhFPP/OLm26YXJ7MsucK//4lqj4M0gv4i/A9++1pTaMvspBVCpz3MckAzP//FmwBg4gMn/Y//xY3c4OhYxdc66HXteRs6gN1PP8tC+/H2vZ3DwCoRD//laAAAA";
	private static final String WEBP_LOSSLESS = "UklGRjAAAABXRUJQVlA4TCQAAAAvF8ADAA9Qnlshnwd0/qMCmbRNHcy/yznYVyYhov8TgO5yQgM=";
	private static final String WEBP_WITH_EXIF = "UklGRlgAAABXRUJQVlA4WAoAAAAIAAAAFwAADwAAVlA4TCQAAAAvF8ADAA9Qnlshnwd0/qMCmbRNHcy/yznYVyYhov8TgO5yQgNFWElGDQAAAEdQUy1GQUtFLURBVEEA";
	private static final String WEBP_ANIMATED = "UklGRp4AAABXRUJQVlA4WAoAAAACAAAAFwAADwAAQU5JTQYAAAAAAAAAAABBTk1GQAAAAAAAAAAAABcAAA8AAGQAAAJWUDhMJwAAAC8XwAMAD3A84nuoHox7/oMHhtlIWoP1t1yD/i6HENH/cQCAZk/nDQBBTk1GKgAAAAAAAAAAABcAAA8AAGQAAABWUDhMEQAAAC8XwAMAB1DkKlSh/4GI6H8AAA==";

	private ImageFixtures() {
	}

	// ---------- immagini valide ----------

	public static byte[] png() {
		return png(WIDTH, HEIGHT);
	}

	public static byte[] png(int width, int height) {
		return encode("png", width, height);
	}

	public static byte[] jpeg() {
		return jpeg(WIDTH, HEIGHT);
	}

	public static byte[] jpeg(int width, int height) {
		return encode("jpg", width, height);
	}

	public static byte[] webpLossy() {
		return Base64.getDecoder().decode(WEBP_LOSSY);
	}

	public static byte[] webpLossless() {
		return Base64.getDecoder().decode(WEBP_LOSSLESS);
	}

	/** WebP "esteso" (VP8X) con un chunk EXIF: valido, i metadati sono ammessi. */
	public static byte[] webpWithExif() {
		return Base64.getDecoder().decode(WEBP_WITH_EXIF);
	}

	/** WebP animato (2 fotogrammi): formato valido ma NON accettato come copertina. */
	public static byte[] webpAnimated() {
		return Base64.getDecoder().decode(WEBP_ANIMATED);
	}

	// ---------- PNG costruiti a mano ----------

	/** PNG valido gonfiato a ESATTAMENTE "size" byte con un chunk privato prima di IEND (CRC corretto). */
	public static byte[] pngPaddedTo(int size) {
		byte[] base = png();
		int padding = size - base.length - 12;
		if (padding < 0) {
			throw new IllegalArgumentException("size too small");
		}
		byte[] beforeIend = Arrays.copyOfRange(base, 0, base.length - 12);
		byte[] iend = Arrays.copyOfRange(base, base.length - 12, base.length);
		return concat(beforeIend, chunk("prVt", new byte[padding]), iend);
	}

	/**
	 * PNG con struttura corretta (IHDR, IDAT, IEND con CRC giusti) che DICHIARA le dimensioni indicate: serve a provare i limiti
	 * di dimensione senza creare un'immagine enorme. Non e' decodificabile, e non serve esserlo.
	 */
	public static byte[] pngClaiming(long width, long height) {
		byte[] ihdr = new byte[13];
		putInt(ihdr, 0, width);
		putInt(ihdr, 4, height);
		ihdr[8] = 8;      // profondita'
		ihdr[9] = 2;      // RGB
		return concat(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A }, chunk("IHDR", ihdr),
				chunk("IDAT", new byte[] { 0x08, (byte) 0xD7, 0x63, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01 }), chunk("IEND", new byte[0]));
	}

	public static byte[] chunk(String type, byte[] data) {
		byte[] out = new byte[12 + data.length];
		putInt(out, 0, data.length);
		System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, out, 4, 4);
		System.arraycopy(data, 0, out, 8, data.length);
		CRC32 crc = new CRC32();
		crc.update(out, 4, 4 + data.length);
		putInt(out, 8 + data.length, crc.getValue());
		return out;
	}

	// ---------- altri formati (NON accettati) ----------

	public static byte[] svg() {
		return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] gif() {
		return Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");   // GIF 1x1 valida
	}

	public static byte[] html() {
		return "<!doctype html><html><body><script>alert(document.cookie)</script></body></html>".getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] text() {
		return "questo non e' un'immagine".getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] pdf() {
		return "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
	}

	public static byte[] zip() {
		return new byte[] { 'P', 'K', 3, 4, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
	}

	public static byte[] bmp() {
		byte[] data = new byte[58];
		data[0] = 'B';
		data[1] = 'M';
		return data;
	}

	public static byte[] executable() {
		return new byte[] { 'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0 };
	}

	// ---------- utilita' ----------

	public static byte[] truncate(byte[] data, int length) {
		return Arrays.copyOf(data, length);
	}

	public static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	private static byte[] encode(String format, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				image.setRGB(x, y, new Color(40 + (x * 7) % 200, 60 + (y * 11) % 190, 120).getRGB());
			}
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!ImageIO.write(image, format, out)) {
				throw new IllegalStateException("No encoder for " + format);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void putInt(byte[] target, int offset, long value) {
		target[offset] = (byte) (value >> 24);
		target[offset + 1] = (byte) (value >> 16);
		target[offset + 2] = (byte) (value >> 8);
		target[offset + 3] = (byte) value;
	}

}
