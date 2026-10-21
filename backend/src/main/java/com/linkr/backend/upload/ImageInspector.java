package com.linkr.backend.upload;

import java.util.zip.CRC32;

/**
 * Riconosce il formato REALE di un file guardandone i byte (mai nome o MIME dichiarati) e ne controlla la struttura,
 * cosi' un file con l'intestazione giusta ma contenuto rotto, troncato o "incollato" dopo la fine viene rifiutato.
 * Legge le dimensioni dalle intestazioni SENZA decodificare i pixel (nessuna "decompression bomb") e le limita.
 *
 *  - PNG: firma, IHDR per primo, tutti i chunk con CRC corretto, almeno un IDAT, IEND come ultimo chunk e fine del file.
 *  - JPEG: SOI, segmenti ben formati fino a SOF (baseline o progressive) e SOS, fine file con EOI.
 *  - WebP: RIFF con dimensione coerente con il file, chunk VP8 / VP8L / VP8X ben formati fino a fine file; WebP animati rifiutati.
 *
 * Non e' un decoder: non garantisce che ogni pixel sia valido, ma scarta SVG, GIF, HTML, script, archivi e file corrotti.
 * Il file viene poi servito con Content-Type fisso, nosniff e CSP sandbox (vedi EventImageController).
 */
final class ImageInspector {

	/** Lato massimo e pixel totali: oltre non e' una copertina ragionevole e puo' far esaurire la memoria di chi la mostra. */
	static final int MAX_SIDE = 8_000;
	static final long MAX_PIXELS = 40_000_000L;

	record ImageInfo(ImageType type, int width, int height) {
	}

	private ImageInspector() {
	}

	/**
	 * @throws UploadException unsupported() se non e' JPEG/PNG/WebP, malformed() se lo e' ma e' danneggiato o fuori limiti
	 */
	static ImageInfo inspect(byte[] data) {
		ImageInfo info;
		if (isPng(data)) {
			info = inspectPng(data);
		}
		else if (isJpeg(data)) {
			info = inspectJpeg(data);
		}
		else if (isWebp(data)) {
			info = inspectWebp(data);
		}
		else {
			throw UploadException.unsupported();
		}
		if (info.width() < 1 || info.height() < 1 || info.width() > MAX_SIDE || info.height() > MAX_SIDE
				|| (long) info.width() * info.height() > MAX_PIXELS) {
			throw UploadException.malformed();
		}
		return info;
	}

	// ---------- firme ----------

	private static boolean isPng(byte[] d) {
		return d.length >= 8 && u8(d, 0) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G'
				&& u8(d, 4) == 0x0D && u8(d, 5) == 0x0A && u8(d, 6) == 0x1A && u8(d, 7) == 0x0A;
	}

	private static boolean isJpeg(byte[] d) {
		return d.length >= 3 && u8(d, 0) == 0xFF && u8(d, 1) == 0xD8 && u8(d, 2) == 0xFF;
	}

	private static boolean isWebp(byte[] d) {
		return d.length >= 12 && fourCc(d, 0, "RIFF") && fourCc(d, 8, "WEBP");
	}

	// ---------- PNG ----------

	private static ImageInfo inspectPng(byte[] d) {
		int pos = 8;
		int width = 0;
		int height = 0;
		boolean first = true;
		boolean sawData = false;
		while (true) {
			if (pos + 12 > d.length) {
				throw UploadException.malformed();                       // chunk senza intestazione/CRC: file troncato
			}
			long length = u32be(d, pos);
			if (length > d.length - pos - 12L) {
				throw UploadException.malformed();
			}
			int dataStart = pos + 8;
			int dataEnd = dataStart + (int) length;
			CRC32 crc = new CRC32();
			crc.update(d, pos + 4, 4 + (int) length);                    // il CRC copre tipo + dati
			if (crc.getValue() != u32be(d, dataEnd)) {
				throw UploadException.malformed();
			}
			if (first) {
				if (!fourCc(d, pos + 4, "IHDR") || length != 13) {
					throw UploadException.malformed();
				}
				width = (int) u32be(d, dataStart);
				height = (int) u32be(d, dataStart + 4);
				int depth = u8(d, dataStart + 8);
				int colorType = u8(d, dataStart + 9);
				if (u32be(d, dataStart) > MAX_SIDE || u32be(d, dataStart + 4) > MAX_SIDE
						|| u8(d, dataStart + 10) != 0 || u8(d, dataStart + 11) != 0 || u8(d, dataStart + 12) > 1
						|| !validPngDepth(depth, colorType)) {
					throw UploadException.malformed();
				}
				first = false;
			}
			else if (fourCc(d, pos + 4, "IDAT")) {
				sawData = true;
			}
			else if (fourCc(d, pos + 4, "IEND")) {
				if (length != 0 || !sawData || dataEnd + 4 != d.length) {
					throw UploadException.malformed();                   // IEND deve chiudere il file: niente dati in coda
				}
				return new ImageInfo(ImageType.PNG, width, height);
			}
			pos = dataEnd + 4;
		}
	}

	private static boolean validPngDepth(int depth, int colorType) {
		return switch (colorType) {
			case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
			case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
			case 2, 4, 6 -> depth == 8 || depth == 16;
			default -> false;
		};
	}

	// ---------- JPEG ----------

	private static ImageInfo inspectJpeg(byte[] d) {
		int pos = 2;
		int width = 0;
		int height = 0;
		boolean sawFrame = false;
		while (true) {
			// ogni segmento inizia con 0xFF; eventuali 0xFF di riempimento si saltano
			while (pos < d.length && u8(d, pos) == 0xFF && pos + 1 < d.length && u8(d, pos + 1) == 0xFF) {
				pos++;
			}
			if (pos + 4 > d.length || u8(d, pos) != 0xFF) {
				throw UploadException.malformed();
			}
			int marker = u8(d, pos + 1);
			if (marker == 0xD9 || marker == 0x00 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0xD8) {
				throw UploadException.malformed();                       // EOI/SOI/RST prima dei dati: struttura incoerente
			}
			int length = u16be(d, pos + 2);
			if (length < 2 || pos + 2 + length > d.length) {
				throw UploadException.malformed();
			}
			if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {    // baseline, esteso, progressive
				if (length < 8 || sawFrame) {
					throw UploadException.malformed();
				}
				height = u16be(d, pos + 5);
				width = u16be(d, pos + 7);
				sawFrame = true;
			}
			else if (marker == 0xC3 || (marker >= 0xC5 && marker <= 0xCF && marker != 0xC8 && marker != 0xCC)) {
				throw UploadException.malformed();                       // lossless/aritmetico: i browser non li mostrano
			}
			else if (marker == 0xDA) {                                   // SOS: da qui in poi dati compressi fino a EOI
				boolean endsWithEoi = u8(d, d.length - 2) == 0xFF && u8(d, d.length - 1) == 0xD9;
				if (!sawFrame || !endsWithEoi || pos + 2 + length > d.length - 2) {
					throw UploadException.malformed();
				}
				return new ImageInfo(ImageType.JPEG, width, height);
			}
			pos += 2 + length;
		}
	}

	// ---------- WebP ----------

	private static ImageInfo inspectWebp(byte[] d) {
		if (d.length < 30 || u32le(d, 4) + 8 != d.length) {
			throw UploadException.malformed();                           // la dimensione RIFF deve coincidere con il file
		}
		int pos = 12;
		int width = 0;
		int height = 0;
		boolean first = true;
		boolean sawImageData = false;
		while (pos < d.length) {
			if (pos + 8 > d.length) {
				throw UploadException.malformed();
			}
			long size = u32le(d, pos + 4);
			long padded = size + (size & 1);
			if (padded > d.length - pos - 8L) {
				throw UploadException.malformed();
			}
			int payload = pos + 8;
			if (first) {
				if (fourCc(d, pos, "VP8 ")) {                            // lossy
					if (size < 10 || u8(d, payload + 3) != 0x9D || u8(d, payload + 4) != 0x01 || u8(d, payload + 5) != 0x2A) {
						throw UploadException.malformed();
					}
					width = u16le(d, payload + 6) & 0x3FFF;
					height = u16le(d, payload + 8) & 0x3FFF;
					sawImageData = true;
				}
				else if (fourCc(d, pos, "VP8L")) {                       // lossless
					if (size < 5 || u8(d, payload) != 0x2F) {
						throw UploadException.malformed();
					}
					long bits = u32le(d, payload + 1);
					width = (int) (bits & 0x3FFF) + 1;
					height = (int) ((bits >> 14) & 0x3FFF) + 1;
					sawImageData = true;
				}
				else if (fourCc(d, pos, "VP8X")) {                       // esteso: puo' contenere metadati o animazione
					if (size < 10 || (u8(d, payload) & 0x02) != 0) {
						throw UploadException.malformed();               // animazione: non e' una copertina statica
					}
					width = 1 + u24le(d, payload + 4);
					height = 1 + u24le(d, payload + 7);
				}
				else {
					throw UploadException.malformed();
				}
				first = false;
			}
			else if (fourCc(d, pos, "VP8 ") || fourCc(d, pos, "VP8L")) {
				sawImageData = true;
			}
			else if (fourCc(d, pos, "ANMF") || fourCc(d, pos, "ANIM")) {
				throw UploadException.malformed();
			}
			pos = (int) (payload + padded);
		}
		if (pos != d.length || !sawImageData) {
			throw UploadException.malformed();
		}
		return new ImageInfo(ImageType.WEBP, width, height);
	}

	// ---------- lettura dei byte ----------

	private static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}

	private static int u16be(byte[] d, int i) {
		return (u8(d, i) << 8) | u8(d, i + 1);
	}

	private static int u16le(byte[] d, int i) {
		return u8(d, i) | (u8(d, i + 1) << 8);
	}

	private static int u24le(byte[] d, int i) {
		return u8(d, i) | (u8(d, i + 1) << 8) | (u8(d, i + 2) << 16);
	}

	private static long u32be(byte[] d, int i) {
		return ((long) u8(d, i) << 24) | ((long) u8(d, i + 1) << 16) | ((long) u8(d, i + 2) << 8) | u8(d, i + 3);
	}

	private static long u32le(byte[] d, int i) {
		return u8(d, i) | ((long) u8(d, i + 1) << 8) | ((long) u8(d, i + 2) << 16) | ((long) u8(d, i + 3) << 24);
	}

	private static boolean fourCc(byte[] d, int i, String tag) {
		return d[i] == tag.charAt(0) && d[i + 1] == tag.charAt(1) && d[i + 2] == tag.charAt(2) && d[i + 3] == tag.charAt(3);
	}

}
