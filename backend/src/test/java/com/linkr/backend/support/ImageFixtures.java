package com.linkr.backend.support;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.CRC32;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

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

	// ---------- immagini CON metadati (per provare la rimozione) ----------

	/** Testi segnaposto dentro i metadati: nessuno deve comparire in un file pubblicato. */
	public static final List<String> SECRETS = List.of("SECRET-MAKE", "SECRET-GPS", "SECRET-XMP", "SECRET-COMMENT", "SECRET-IPTC",
			"SECRET-SECOND", "SECRET-TEXT", "SECRET-PRIVATE");

	/**
	 * JPEG come lo produce un telefono: EXIF (Make, Orientation, IFD GPS), XMP, commento, IPTC/Photoshop, un segmento MPF
	 * e, dopo l'EOI, una seconda immagine completa (come le immagini secondarie MPF) con un suo commento. Finisce con EOI, quindi
	 * per ImageInspector e' un file valido.
	 */
	public static byte[] jpegWithMetadata(int orientation, boolean littleEndianExif) {
		byte[] base = jpeg();
		byte[] second = jpeg(8, 8);
		byte[] secondWithComment = concat(Arrays.copyOfRange(second, 0, 2), segment(0xFE, ascii("SECRET-SECOND")), Arrays.copyOfRange(second, 2, second.length));
		return concat(
				new byte[] { (byte) 0xFF, (byte) 0xD8 },
				segment(0xE1, concat(ascii("Exif\0\0"), exifTiff(littleEndianExif, orientation))),
				segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>SECRET-XMP</x:xmpmeta>")),
				segment(0xFE, ascii("SECRET-COMMENT")),
				segment(0xED, ascii("Photoshop 3.0\0" + "8BIM SECRET-IPTC")),
				segment(0xE2, ascii("MPF\0" + "SECRET-SECOND-INDEX")),
				Arrays.copyOfRange(base, 2, base.length),
				secondWithComment);
	}

	/** JPEG progressivo (varie scansioni): serve a provare che i segmenti tra le scansioni sono letti correttamente. */
	public static byte[] jpegProgressive(int width, int height) {
		try {
			ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
				writer.setOutput(stream);
				writer.write(null, new IIOImage(gradient(width, height), null, null), param);
			}
			writer.dispose();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Inserisce un segmento (marker + lunghezza + contenuto) subito dopo SOI. */
	public static byte[] withJpegSegment(byte[] jpeg, int marker, byte[] payload) {
		return concat(Arrays.copyOfRange(jpeg, 0, 2), segment(marker, payload), Arrays.copyOfRange(jpeg, 2, jpeg.length));
	}

	/** APP2 "ICC_PROFILE" con un frammento del profilo (sequenza 1-based su "count" frammenti). */
	public static byte[] iccSegmentPayload(int sequence, int count, byte[] profilePart) {
		return concat(ascii("ICC_PROFILE\0"), new byte[] { (byte) sequence, (byte) count }, profilePart);
	}

	/** Profilo ICC "plausibile": dimensione dichiarata coerente e firma "acsp" a offset 36 (il resto e' a zero). */
	public static byte[] iccProfile(int size) {
		byte[] profile = new byte[size];
		ByteBuffer.wrap(profile).putInt(size);
		profile[36] = 'a';
		profile[37] = 'c';
		profile[38] = 's';
		profile[39] = 'p';
		return profile;
	}

	/** Segmento APP14 "Adobe" (12 byte di contenuto), necessario a decodificare CMYK/YCCK. */
	public static byte[] adobePayload() {
		return concat(ascii("Adobe"), new byte[] { 0, 100, 0, 0, 0, 0, 1 });
	}

	/**
	 * PNG con metadati ovunque: prima dei dati tEXt, iTXt (XMP), tIME, pHYs e un chunk privato; dopo i dati un eXIf con GPS e un altro tEXt.
	 * Parte da un PNG vero del JDK, quindi i pixel sono verificabili.
	 */
	public static byte[] pngWithMetadata(int orientation) {
		byte[] base = png();
		int afterIhdr = 8 + 12 + 13;
		int beforeIend = base.length - 12;
		return concat(
				Arrays.copyOfRange(base, 0, afterIhdr),
				chunk("tEXt", ascii("Comment\0SECRET-TEXT")),
				chunk("iTXt", ascii("XML:com.adobe.xmp\0\0\0\0\0<x:xmpmeta>SECRET-XMP</x:xmpmeta>")),
				chunk("tIME", new byte[] { 0x07, (byte) 0xEA, 10, 21, 12, 30, 15 }),
				chunk("pHYs", new byte[] { 0, 0, 0x0B, 0x13, 0, 0, 0x0B, 0x13, 1 }),
				chunk("prVt", ascii("SECRET-PRIVATE")),
				Arrays.copyOfRange(base, afterIhdr, beforeIend),
				chunk("eXIf", exifTiff(false, orientation)),
				chunk("tEXt", ascii("Comment\0SECRET-TEXT-LATE")),
				Arrays.copyOfRange(base, beforeIend, base.length));
	}

	/** WebP esteso (VP8X) con immagine lossless 24x16, un profilo ICC facoltativo, EXIF con GPS, XMP e un chunk sconosciuto. */
	public static byte[] webpWithMetadata(int orientation, byte[] iccProfile) {
		byte[] lossless = webpLossless();
		byte[] vp8l = Arrays.copyOfRange(lossless, 12, lossless.length);       // il chunk VP8L intero (8 di intestazione + dati)
		int flags = 0x08 | 0x04 | (iccProfile != null ? 0x20 : 0);
		byte[] vp8x = riffChunk("VP8X", new byte[] { (byte) flags, 0, 0, 0, WIDTH - 1, 0, 0, HEIGHT - 1, 0, 0 });
		byte[] body = concat(ascii("WEBP"), vp8x, iccProfile != null ? riffChunk("ICCP", iccProfile) : new byte[0], vp8l,
				riffChunk("EXIF", exifTiff(true, orientation)),
				riffChunk("XMP ", ascii("<x:xmpmeta>SECRET-XMP</x:xmpmeta>")),
				riffChunk("JUNK", ascii("SECRET-PRIVATE")));
		byte[] size = new byte[4];
		ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length);
		return concat(ascii("RIFF"), size, body);
	}

	/**
	 * Blocco TIFF/EXIF come lo scrive un telefono: IFD0 con Make, Orientation e puntatore GPS; IFD GPS con riferimento,
	 * latitudine (gradi/primi/secondi) e metodo di elaborazione testuale. Little o big endian.
	 */
	public static byte[] exifTiff(boolean littleEndian, int orientation) {
		byte[] make = ascii("SECRET-MAKE\0");
		byte[] method = ascii("SECRET-GPS\0");
		int gpsAt = 8 + (2 + 3 * 12 + 4) + make.length;
		int latitudeAt = gpsAt + (2 + 3 * 12 + 4);
		int methodAt = latitudeAt + 24;
		ByteBuffer b = ByteBuffer.allocate(methodAt + method.length).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		byte order = (byte) (littleEndian ? 'I' : 'M');
		b.put(order).put(order).putShort((short) 42).putInt(8);
		b.putShort((short) 3);                                                   // IFD0: 3 voci
		b.putShort((short) 0x010F).putShort((short) 2).putInt(make.length).putInt(8 + (2 + 3 * 12 + 4));
		b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsAt);
		b.putInt(0);
		b.put(make);
		b.putShort((short) 3);                                                   // IFD GPS: 3 voci
		b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
		b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latitudeAt);
		b.putShort((short) 0x001B).putShort((short) 7).putInt(method.length).putInt(methodAt);
		b.putInt(0);
		b.putInt(45).putInt(1).putInt(27).putInt(1).putInt(3000).putInt(100);    // 45 gradi 27 primi 30,00 secondi
		b.put(method);
		return b.array();
	}

	public static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}

	/** Segmento JPEG: 0xFF, marker, lunghezza (comprende i 2 byte della lunghezza), contenuto. */
	public static byte[] segment(int marker, byte[] payload) {
		byte[] out = new byte[4 + payload.length];
		out[0] = (byte) 0xFF;
		out[1] = (byte) marker;
		out[2] = (byte) ((payload.length + 2) >> 8);
		out[3] = (byte) (payload.length + 2);
		System.arraycopy(payload, 0, out, 4, payload.length);
		return out;
	}

	/** Chunk RIFF (little endian) con riempimento a lunghezza pari. */
	public static byte[] riffChunk(String type, byte[] data) {
		byte[] out = new byte[8 + data.length + (data.length & 1)];
		System.arraycopy(ascii(type), 0, out, 0, 4);
		ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length);
		System.arraycopy(data, 0, out, 8, data.length);
		return out;
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

	private static BufferedImage gradient(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				image.setRGB(x, y, new Color(40 + (x * 7) % 200, 60 + (y * 11) % 190, 120).getRGB());
			}
		}
		return image;
	}

	private static byte[] encode(String format, int width, int height) {
		BufferedImage image = gradient(width, height);
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
