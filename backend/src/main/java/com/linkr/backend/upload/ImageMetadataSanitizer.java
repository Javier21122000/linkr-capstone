package com.linkr.backend.upload;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

import com.linkr.backend.upload.ImageInspector.ImageInfo;

/**
 * Riscrive un'immagine GIA' VALIDATA da ImageInspector tenendo solo cio' che serve a mostrarla. Si lavora sul contenitore
 * (segmenti JPEG, chunk PNG, chunk RIFF/WebP), SENZA decodificare ne' ricodificare i pixel: i dati compressi passano identici,
 * quindi nessuna perdita di qualita' e nessun costo di memoria legato alle dimensioni.
 *
 * Approccio a LISTA DI AMMISSIONE: quello che non e' esplicitamente elencato qui non viene copiato. Cosi' i metadati sconosciuti
 * o futuri non passano per caso, a differenza di un elenco di cose da cancellare.
 *
 *  - JPEG: restano tabelle, frame e scansioni; APP14 "Adobe" (serve a decodificare CMYK/YCCK) e il profilo colore ICC.
 *    Spariscono EXIF (GPS, data, modello del telefono, miniatura), XMP, IPTC/Photoshop, JFIF, commenti e tutto cio' che segue il
 *    primo EOI (le immagini secondarie MPF di molti telefoni, o dati incollati in coda).
 *  - PNG: restano IHDR, PLTE, IDAT, IEND, tRNS e i chunk colore (gAMA, cHRM, sRGB, cICP, iCCP). Spariscono tEXt/zTXt/iTXt (anche XMP),
 *    eXIf, tIME, pHYs, chunk privati e le parti animate (APNG): resta la sola immagine statica.
 *  - WebP: restano VP8/VP8L, ALPH e ICCP. Spariscono EXIF, XMP e ogni altro chunk.
 *
 * ORIENTAMENTO: le foto del telefono sono quasi sempre "ruotate via EXIF". Cancellare l'EXIF le mostrerebbe di lato, quindi dell'EXIF
 * si legge UNA sola informazione, l'orientamento (1-8), e se non e' quello neutro la si riscrive in un EXIF minimo costruito da noi
 * (26 byte di TIFF con la sola voce Orientation). Nulla dell'EXIF originale viene copiato.
 *
 * Il profilo ICC si conserva perche' serve ai colori (le foto iPhone sono Display P3: senza profilo apparirebbero spente) e non descrive
 * chi ha scattato. Si accetta solo se e' un profilo plausibile (intestazione coerente, firma "acsp", al massimo MAX_ICC_BYTES);
 * altrimenti si scarta e basta.
 *
 * Dopo la riscrittura il risultato deve superare di nuovo ImageInspector con lo stesso formato e le stesse dimensioni: se non e' cosi'
 * l'upload e' rifiutato (400) e non si pubblica nulla.
 */
final class ImageMetadataSanitizer {

	/** Dimensione massima di un profilo ICC conservato. Quelli reali (sRGB, Display P3, Adobe RGB) pesano da 0,5 a pochi KB. */
	static final int MAX_ICC_BYTES = 256 * 1024;

	private static final int TIFF_TAG_ORIENTATION = 0x0112;
	private static final int TIFF_TYPE_SHORT = 3;
	private static final Set<String> PNG_KEPT_CHUNKS = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "cICP");

	private ImageMetadataSanitizer() {
	}

	/**
	 * @param data  i byte gia' validati da ImageInspector
	 * @param info  il risultato di quella validazione (formato e dimensioni attesi)
	 * @throws UploadException malformed() se la struttura e' incoerente o il risultato non supera il controllo finale
	 */
	static byte[] sanitize(byte[] data, ImageInfo info) {
		byte[] clean = switch (info.type()) {
			case JPEG -> sanitizeJpeg(data);
			case PNG -> sanitizePng(data);
			case WEBP -> sanitizeWebp(data);
		};
		ImageInfo check = ImageInspector.inspect(clean);
		if (check.type() != info.type() || check.width() != info.width() || check.height() != info.height()) {
			throw UploadException.malformed();
		}
		return clean;
	}

	// ---------- JPEG ----------

	private static byte[] sanitizeJpeg(byte[] d) {
		ByteArrayOutputStream body = new ByteArrayOutputStream(d.length);   // tabelle, frame e scansioni, nell'ordine originale
		List<IccSegment> icc = new ArrayList<>();
		byte[] adobe = null;
		int orientation = 0;
		boolean sawFrame = false;
		boolean sawScan = false;
		int pos = 2;                                                         // dopo SOI

		while (true) {
			while (pos + 1 < d.length && u8(d, pos) == 0xFF && u8(d, pos + 1) == 0xFF) {
				pos++;                                                       // 0xFF di riempimento
			}
			if (pos + 2 > d.length || u8(d, pos) != 0xFF) {
				throw UploadException.malformed();
			}
			int marker = u8(d, pos + 1);
			if (marker == 0xD9) {                                            // EOI: il resto del file (MPF, dati in coda) non si copia
				if (!sawScan) {
					throw UploadException.malformed();
				}
				body.write(0xFF);
				body.write(0xD9);
				break;
			}
			if (marker == 0x00 || marker == 0x01 || marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7)) {
				throw UploadException.malformed();                           // SOI/RST/TEM fuori posto
			}
			if (pos + 4 > d.length) {
				throw UploadException.malformed();
			}
			int length = u16be(d, pos + 2);
			int end = pos + 2 + length;
			if (length < 2 || end > d.length) {
				throw UploadException.malformed();
			}
			int payload = pos + 4;
			int payloadLength = length - 2;

			if (marker >= 0xE0 && marker <= 0xEF) {                          // APPn: si leggono solo tre cose, mai si copiano i metadati
				if (marker == 0xE1 && orientation == 0 && hasPrefix(d, payload, payloadLength, "Exif\0\0")) {
					orientation = exifOrientation(d, payload + 6, payloadLength - 6);
				}
				else if (marker == 0xE2 && hasPrefix(d, payload, payloadLength, "ICC_PROFILE\0") && payloadLength >= 14) {
					icc.add(new IccSegment(u8(d, payload + 12), u8(d, payload + 13), Arrays.copyOfRange(d, pos, end), payloadLength - 14));
				}
				else if (marker == 0xEE && payloadLength == 12 && hasPrefix(d, payload, payloadLength, "Adobe") && adobe == null) {
					adobe = Arrays.copyOfRange(d, pos, end);
				}
			}
			else if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {   // SOF baseline / esteso / progressive
				if (sawFrame) {
					throw UploadException.malformed();
				}
				sawFrame = true;
				body.write(d, pos, end - pos);
			}
			else if (marker == 0xC4 || marker == 0xDB || marker == 0xDD || marker == 0xDC) {   // DHT, DQT, DRI, DNL
				body.write(d, pos, end - pos);
			}
			else if (marker == 0xDA) {                                       // SOS: intestazione e poi dati compressi fino al marker successivo
				if (!sawFrame) {
					throw UploadException.malformed();
				}
				int scanEnd = scanDataEnd(d, end);
				body.write(d, pos, scanEnd - pos);
				sawScan = true;
				pos = scanEnd;
				continue;
			}
			else if (marker == 0xC3 || (marker >= 0xC5 && marker <= 0xCF && marker != 0xC8 && marker != 0xCC)) {
				throw UploadException.malformed();                           // lossless/aritmetico: ImageInspector li scarta gia'
			}
			// COM (0xFE), JPGn, DAC e ogni altro segmento: non si copia
			pos = end;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream(body.size() + 128);
		out.write(0xFF);
		out.write(0xD8);
		if (orientation > 1) {
			byte[] tiff = orientationTiff(orientation);
			int exifLength = 6 + tiff.length;
			out.write(0xFF);
			out.write(0xE1);
			out.write((exifLength + 2) >> 8);
			out.write((exifLength + 2) & 0xFF);
			out.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
			out.writeBytes(tiff);
		}
		for (IccSegment segment : validIccSegments(icc)) {
			out.writeBytes(segment.segment());
		}
		if (adobe != null) {
			out.writeBytes(adobe);
		}
		out.writeBytes(body.toByteArray());
		return out.toByteArray();
	}

	/**
	 * Un frammento APP2 "ICC_PROFILE": il profilo puo' essere diviso in piu' segmenti numerati da 1 a "count".
	 * "segment" e' il segmento intero (marker, lunghezza, intestazione, dati); gli ultimi "dataLength" byte sono i dati del profilo.
	 */
	private record IccSegment(int sequence, int count, byte[] segment, int dataLength) {
	}

	/** I segmenti ICC di un profilo completo e plausibile, altrimenti nessuno. */
	private static List<IccSegment> validIccSegments(List<IccSegment> segments) {
		if (segments.isEmpty()) {
			return List.of();
		}
		int count = segments.get(0).count();
		if (count < 1 || segments.size() != count) {
			return List.of();
		}
		ByteArrayOutputStream profile = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) {
			IccSegment segment = segments.get(i);
			if (segment.count() != count || segment.sequence() != i + 1) {
				return List.of();
			}
			profile.write(segment.segment(), segment.segment().length - segment.dataLength(), segment.dataLength());
			if (profile.size() > MAX_ICC_BYTES) {
				return List.of();
			}
		}
		byte[] bytes = profile.toByteArray();
		return isPlausibleIccProfile(bytes, 0, bytes.length) ? segments : List.of();
	}

	/** Indice del 0xFF del prossimo marker vero dopo i dati compressi di una scansione (0xFF 0x00 e RSTn fanno parte dei dati). */
	private static int scanDataEnd(byte[] d, int from) {
		int i = from;
		while (i < d.length - 1) {
			if (u8(d, i) == 0xFF) {
				int next = u8(d, i + 1);
				if (next == 0x00 || (next >= 0xD0 && next <= 0xD7)) {
					i += 2;
				}
				else if (next == 0xFF) {
					i++;                                                     // riempimento prima di un marker
				}
				else {
					return i;
				}
			}
			else {
				i++;
			}
		}
		throw UploadException.malformed();                                   // il file finisce dentro i dati senza un marker
	}

	// ---------- PNG ----------

	private static byte[] sanitizePng(byte[] d) {
		int orientation = 0;
		for (int pos = 8; pos + 12 <= d.length; ) {                          // prima passata: solo l'orientamento, ovunque sia l'eXIf
			long length = u32be(d, pos);
			if (length > d.length - pos - 12L) {
				throw UploadException.malformed();
			}
			if (orientation == 0 && fourCc(d, pos + 4, "eXIf")) {
				orientation = exifOrientation(d, pos + 8, (int) length);
			}
			pos += 12 + (int) length;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
		out.write(d, 0, 8);
		boolean exifWritten = orientation <= 1;
		for (int pos = 8; pos + 12 <= d.length; ) {
			int length = (int) u32be(d, pos);
			int end = pos + 12 + length;
			String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
			if (!exifWritten && type.equals("IDAT")) {
				out.writeBytes(pngChunk("eXIf", orientationTiff(orientation)));   // prima dei dati, mai in mezzo agli IDAT
				exifWritten = true;
			}
			boolean keep = PNG_KEPT_CHUNKS.contains(type) || (type.equals("iCCP") && length <= MAX_ICC_BYTES);
			if (keep) {
				out.write(d, pos, end - pos);                                // chunk intero, CRC compreso (gia' verificato)
			}
			pos = end;
		}
		return out.toByteArray();
	}

	private static byte[] pngChunk(String type, byte[] data) {
		byte[] chunk = new byte[12 + data.length];
		putInt(chunk, 0, data.length);
		System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, chunk, 4, 4);
		System.arraycopy(data, 0, chunk, 8, data.length);
		CRC32 crc = new CRC32();
		crc.update(chunk, 4, 4 + data.length);
		putInt(chunk, 8 + data.length, crc.getValue());
		return chunk;
	}

	// ---------- WebP ----------

	private static byte[] sanitizeWebp(byte[] d) {
		byte[] vp8x = null;                                                  // intestazione del formato esteso (10 byte), se c'e'
		byte[] iccp = null;
		byte[] alph = null;
		byte[] image = null;                                                 // chunk VP8 o VP8L, intero
		int orientation = 0;

		for (int pos = 12; pos < d.length; ) {
			if (pos + 8 > d.length) {
				throw UploadException.malformed();
			}
			long size = u32le(d, pos + 4);
			long padded = size + (size & 1);
			if (padded > d.length - pos - 8L) {
				throw UploadException.malformed();
			}
			int payload = pos + 8;
			int chunkEnd = (int) (payload + padded);
			if (fourCc(d, pos, "VP8X")) {
				if (pos != 12 || size < 10 || vp8x != null) {
					throw UploadException.malformed();
				}
				vp8x = Arrays.copyOfRange(d, payload, payload + 10);
			}
			else if (fourCc(d, pos, "ICCP")) {
				if (iccp == null && size <= MAX_ICC_BYTES && isPlausibleIccProfile(d, payload, (int) size)) {
					iccp = Arrays.copyOfRange(d, pos, chunkEnd);
				}
			}
			else if (fourCc(d, pos, "ALPH")) {
				if (alph == null) {
					alph = Arrays.copyOfRange(d, pos, chunkEnd);
				}
			}
			else if (fourCc(d, pos, "VP8 ") || fourCc(d, pos, "VP8L")) {
				if (image != null) {
					throw UploadException.malformed();
				}
				image = Arrays.copyOfRange(d, pos, chunkEnd);
			}
			else if (fourCc(d, pos, "EXIF") && orientation == 0) {
				// secondo la specifica e' un TIFF; alcuni software lo fanno precedere da "Exif\0\0"
				int skip = hasPrefix(d, payload, (int) size, "Exif\0\0") ? 6 : 0;
				orientation = exifOrientation(d, payload + skip, (int) size - skip);
			}
			pos = chunkEnd;
		}
		if (image == null) {
			throw UploadException.malformed();
		}

		ByteArrayOutputStream chunks = new ByteArrayOutputStream(d.length);
		int flags = 0;
		if (vp8x != null) {                                                  // i metadati hanno senso solo nel formato esteso
			flags = vp8x[0] & 0x10;                                          // si conserva solo "ha trasparenza"; animazione gia' esclusa
			byte[] header = Arrays.copyOf(vp8x, 10);
			if (iccp != null) {
				flags |= 0x20;
			}
			byte[] exif = null;
			if (orientation > 1) {
				exif = webpChunk("EXIF", orientationTiff(orientation));
				flags |= 0x08;
			}
			header[0] = (byte) flags;
			header[1] = 0;
			header[2] = 0;
			header[3] = 0;                                                   // bit riservati a zero
			chunks.writeBytes(webpChunk("VP8X", header));
			if (iccp != null) {
				chunks.writeBytes(iccp);
			}
			if (alph != null) {
				chunks.writeBytes(alph);
			}
			chunks.writeBytes(image);
			if (exif != null) {
				chunks.writeBytes(exif);
			}
		}
		else {
			chunks.writeBytes(image);                                        // formato semplice: solo l'immagine, nessun contenitore per metadati
		}

		byte[] body = chunks.toByteArray();
		byte[] out = new byte[12 + body.length];
		System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
		putIntLe(out, 4, 4 + body.length);                                   // "WEBP" + chunk
		System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
		System.arraycopy(body, 0, out, 12, body.length);
		return out;
	}

	private static byte[] webpChunk(String type, byte[] data) {
		int padded = data.length + (data.length & 1);
		byte[] chunk = new byte[8 + padded];
		System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, chunk, 0, 4);
		putIntLe(chunk, 4, data.length);
		System.arraycopy(data, 0, chunk, 8, data.length);
		return chunk;
	}

	// ---------- EXIF: solo l'orientamento ----------

	/**
	 * Orientamento (1-8) dalla voce Orientation del primo IFD di un blocco TIFF/EXIF, oppure 0 se manca o il blocco non e' coerente.
	 * Ogni lettura e' controllata contro i limiti del blocco: un EXIF danneggiato o ostile non produce errori, solo "nessun orientamento".
	 */
	static int exifOrientation(byte[] d, int start, int length) {
		if (start < 0 || length < 8 || start + length > d.length) {
			return 0;
		}
		boolean little;
		if (d[start] == 'I' && d[start + 1] == 'I') {
			little = true;
		}
		else if (d[start] == 'M' && d[start + 1] == 'M') {
			little = false;
		}
		else {
			return 0;
		}
		if (tiff16(d, start + 2, little) != 42) {
			return 0;
		}
		long ifd = tiff32(d, start + 4, little);
		if (ifd < 8 || ifd > length - 2L) {
			return 0;
		}
		int ifdStart = start + (int) ifd;
		int entries = tiff16(d, ifdStart, little);
		for (int i = 0; i < entries; i++) {
			int entry = ifdStart + 2 + i * 12;
			if (entry + 12 > start + length) {
				return 0;
			}
			if (tiff16(d, entry, little) == TIFF_TAG_ORIENTATION) {
				if (tiff16(d, entry + 2, little) != TIFF_TYPE_SHORT || tiff32(d, entry + 4, little) != 1) {
					return 0;
				}
				int value = tiff16(d, entry + 8, little);
				return value >= 1 && value <= 8 ? value : 0;
			}
		}
		return 0;
	}

	/** TIFF minimo con la sola voce Orientation (big-endian, un solo IFD): e' tutto l'EXIF che rimane. */
	static byte[] orientationTiff(int orientation) {
		return new byte[] {
			'M', 'M', 0, 42, 0, 0, 0, 8,                                     // intestazione; il primo IFD e' all'offset 8
			0, 1,                                                            // una sola voce
			0x01, 0x12, 0, TIFF_TYPE_SHORT, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,   // Orientation, SHORT, un valore
			0, 0, 0, 0 };                                                    // nessun IFD successivo (niente miniatura, niente GPS)
	}

	// ---------- ICC ----------

	/** Intestazione ICC coerente: dimensione dichiarata uguale ai byte, firma "acsp" a offset 36, entro MAX_ICC_BYTES. */
	private static boolean isPlausibleIccProfile(byte[] d, int start, int length) {
		return length >= 128 && length <= MAX_ICC_BYTES && u32be(d, start) == length
				&& d[start + 36] == 'a' && d[start + 37] == 'c' && d[start + 38] == 's' && d[start + 39] == 'p';
	}

	// ---------- lettura/scrittura dei byte ----------

	private static boolean hasPrefix(byte[] d, int start, int length, String prefix) {
		if (length < prefix.length()) {
			return false;
		}
		for (int i = 0; i < prefix.length(); i++) {
			if (d[start + i] != prefix.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}

	private static int u16be(byte[] d, int i) {
		return (u8(d, i) << 8) | u8(d, i + 1);
	}

	private static long u32be(byte[] d, int i) {
		return ((long) u8(d, i) << 24) | ((long) u8(d, i + 1) << 16) | ((long) u8(d, i + 2) << 8) | u8(d, i + 3);
	}

	private static long u32le(byte[] d, int i) {
		return u8(d, i) | ((long) u8(d, i + 1) << 8) | ((long) u8(d, i + 2) << 16) | ((long) u8(d, i + 3) << 24);
	}

	private static int tiff16(byte[] d, int i, boolean little) {
		return little ? u8(d, i) | (u8(d, i + 1) << 8) : (u8(d, i) << 8) | u8(d, i + 1);
	}

	private static long tiff32(byte[] d, int i, boolean little) {
		return little ? u32le(d, i) : u32be(d, i);
	}

	private static boolean fourCc(byte[] d, int i, String tag) {
		return d[i] == tag.charAt(0) && d[i + 1] == tag.charAt(1) && d[i + 2] == tag.charAt(2) && d[i + 3] == tag.charAt(3);
	}

	private static void putInt(byte[] target, int offset, long value) {
		target[offset] = (byte) (value >> 24);
		target[offset + 1] = (byte) (value >> 16);
		target[offset + 2] = (byte) (value >> 8);
		target[offset + 3] = (byte) value;
	}

	private static void putIntLe(byte[] target, int offset, long value) {
		target[offset] = (byte) value;
		target[offset + 1] = (byte) (value >> 8);
		target[offset + 2] = (byte) (value >> 16);
		target[offset + 3] = (byte) (value >> 24);
	}

}
