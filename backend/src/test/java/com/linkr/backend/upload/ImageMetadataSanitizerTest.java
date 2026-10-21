package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.upload.ImageInspector.ImageInfo;

/**
 * Rimozione dei metadati incorporati. Nessun database ne' Spring: solo byte. Per ogni formato si prova che
 *  - i segnaposto messi nei metadati (GPS, Make, XMP, commenti, IPTC, seconda immagine, chunk privati) non compaiono nel risultato;
 *  - rimane SOLO la struttura ammessa (elenco esatto di segmenti/chunk);
 *  - l'orientamento si conserva e nient'altro dell'EXIF;
 *  - i pixel decodificati sono identici (il flusso compresso non viene toccato).
 */
class ImageMetadataSanitizerTest {

	// ======================= JPEG =======================

	@ParameterizedTest
	@ValueSource(ints = { 2, 3, 5, 6, 7, 8 })
	void jpegLosesEveryMetadataSourceButKeepsTheOrientation(int orientation) throws IOException {
		byte[] original = ImageFixtures.jpegWithMetadata(orientation, orientation % 2 == 0);
		assertThat(containedSecrets(original)).containsAll(ImageFixtures.SECRETS.stream().filter(s -> !s.equals("SECRET-TEXT") && !s.equals("SECRET-PRIVATE")).toList());

		byte[] clean = sanitize(original);

		assertThat(containedSecrets(clean)).isEmpty();
		assertThat(jpegHeaderMarkers(clean)).startsWith(0xE1).containsOnly(0xE1, 0xDB, 0xC0, 0xC4, 0xDA);   // EXIF minimo, poi solo tabelle, frame, scansione
		assertThat(countEoi(clean)).isEqualTo(1);                                                // la seconda immagine (MPF) e' sparita
		assertThat(exifOrientationOf(clean)).isEqualTo(orientation);
		assertThat(exifBlockLength(clean)).isEqualTo(26);                                        // il TIFF minimo costruito da noi: niente GPS, niente Make
		assertSamePixels(original, clean);
	}

	@Test
	void jpegWithTheNeutralOrientationOrNoExifGetsNoExifAtAll() throws IOException {
		byte[] neutral = sanitize(ImageFixtures.jpegWithMetadata(1, false));
		byte[] plain = sanitize(ImageFixtures.jpeg());

		for (byte[] clean : List.of(neutral, plain)) {
			assertThat(jpegHeaderMarkers(clean)).containsOnly(0xDB, 0xC0, 0xC4, 0xDA);
			assertThat(containedSecrets(clean)).isEmpty();
		}
		assertSamePixels(ImageFixtures.jpeg(), plain);
	}

	@Test
	void aJpegWithNoMetadataLosesOnlyTheJfifHeader() throws IOException {
		byte[] original = ImageFixtures.jpeg(300, 200);

		byte[] clean = sanitize(original);

		assertThat(jpegHeaderMarkers(original)).startsWith(0xE0);                                // il JDK scrive JFIF (APP0)
		assertThat(clean.length).isLessThan(original.length);
		assertSamePixels(original, clean);
	}

	@Test
	void progressiveJpegKeepsEveryScanAndLosesTheMetadataBetweenThem() throws IOException {
		byte[] progressive = ImageFixtures.jpegProgressive(64, 48);
		// un commento anche "in mezzo": dopo la prima scansione, come lo scrivono alcuni software
		byte[] noisy = withCommentAfterFirstScan(ImageFixtures.withJpegSegment(progressive, 0xE1,
				concat(ImageFixtures.ascii("Exif\0\0"), ImageFixtures.exifTiff(true, 6))), "SECRET-COMMENT");
		assertThat(containedSecrets(noisy)).contains("SECRET-COMMENT", "SECRET-MAKE");

		byte[] clean = sanitize(noisy);

		assertThat(containedSecrets(clean)).isEmpty();
		assertThat(countOf(clean, 0xDA)).isGreaterThan(1);                                       // le scansioni restano tutte
		assertThat(exifOrientationOf(clean)).isEqualTo(6);
		assertSamePixels(progressive, clean);
	}

	@Test
	void anAdobeSegmentIsKeptBecauseColorDecodingNeedsIt() {
		byte[] withAdobe = ImageFixtures.withJpegSegment(ImageFixtures.jpeg(), 0xEE, ImageFixtures.adobePayload());

		assertThat(jpegHeaderMarkers(sanitize(withAdobe))).startsWith(0xEE);
	}

	@Test
	void aPlausibleColorProfileIsKeptAndTheOthersAreDropped() {
		byte[] profile = ImageFixtures.iccProfile(560);
		byte[] jpeg = ImageFixtures.jpeg();

		byte[] kept = sanitize(ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(1, 1, profile)));
		assertThat(jpegHeaderMarkers(kept)).startsWith(0xE2);

		byte[] firstHalf = Arrays.copyOfRange(profile, 0, 300);
		byte[] secondHalf = Arrays.copyOfRange(profile, 300, profile.length);
		byte[] split = ImageFixtures.withJpegSegment(ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(2, 2, secondHalf)),
				0xE2, ImageFixtures.iccSegmentPayload(1, 2, firstHalf));
		assertThat(jpegHeaderMarkers(sanitize(split))).startsWith(0xE2, 0xE2);                   // profilo diviso in due segmenti: resta intero

		byte[] wrongSignature = ImageFixtures.iccProfile(560);
		wrongSignature[36] = 'x';
		byte[] wrongSize = ImageFixtures.iccProfile(560);
		wrongSize[3] = 7;
		byte[] incomplete = ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(1, 2, firstHalf));
		byte[] tooBig = ImageFixtures.iccProfile(ImageMetadataSanitizer.MAX_ICC_BYTES + 1);
		for (byte[] bad : List.of(
				ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(1, 1, wrongSignature)),
				ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(1, 1, wrongSize)),
				incomplete,
				ImageFixtures.withJpegSegment(jpeg, 0xE2, ImageFixtures.iccSegmentPayload(1, 1, Arrays.copyOf(profile, 20))))) {
			assertThat(jpegHeaderMarkers(sanitize(bad))).doesNotContain(0xE2);
		}
		assertThat(tooBig.length).isGreaterThan(ImageMetadataSanitizer.MAX_ICC_BYTES);
	}

	@Test
	void aTrailingSecondImageOrGarbageAfterTheFirstEoiIsNeverPublished() {
		byte[] jpeg = ImageFixtures.jpeg();
		byte[] withTail = ImageFixtures.concat(jpeg, ImageFixtures.ascii("SECRET-SECOND"), new byte[] { (byte) 0xFF, (byte) 0xD9 });

		byte[] clean = sanitize(withTail);

		assertThat(containedSecrets(clean)).isEmpty();
		assertThat(clean).endsWith((byte) 0xFF, (byte) 0xD9);
		assertThat(countEoi(clean)).isEqualTo(1);
	}

	// ======================= PNG =======================

	@Test
	void pngKeepsOnlyThePictureChunksAndTheOrientation() throws IOException {
		byte[] original = ImageFixtures.pngWithMetadata(6);
		assertThat(containedSecrets(original)).contains("SECRET-TEXT", "SECRET-XMP", "SECRET-PRIVATE", "SECRET-MAKE", "SECRET-GPS");

		byte[] clean = sanitize(original);

		assertThat(containedSecrets(clean)).isEmpty();
		assertThat(pngChunkTypes(clean)).startsWith("IHDR", "eXIf", "IDAT").endsWith("IDAT", "IEND").doesNotContain("tEXt", "iTXt", "tIME", "pHYs", "prVt");
		assertThat(pngChunkTypes(clean).stream().filter("eXIf"::equals).count()).isEqualTo(1);
		assertThat(pngExifBlock(clean)).hasSize(26);
		assertThat(ImageMetadataSanitizer.exifOrientation(pngExifBlock(clean), 0, 26)).isEqualTo(6);
		assertSamePixels(original, clean);
	}

	@Test
	void pngWithNeutralOrientationGetsNoExifAndAPlainPngIsLeftAsIs() {
		assertThat(pngChunkTypes(sanitize(ImageFixtures.pngWithMetadata(1)))).doesNotContain("eXIf");
		byte[] plain = ImageFixtures.png(120, 80);
		assertThat(pngChunkTypes(plain)).allMatch(type -> List.of("IHDR", "IDAT", "IEND").contains(type));
		assertThat(sanitize(plain)).isEqualTo(plain);
	}

	@Test
	void pngKeepsPaletteTransparencyAndColorChunksButDropsAnimationAndOversizedProfiles() {
		byte[] base = ImageFixtures.png();
		int afterIhdr = 8 + 12 + 13;
		byte[] profile = ImageFixtures.concat(ImageFixtures.ascii("p\0\0"), new byte[100]);
		byte[] decorated = ImageFixtures.concat(Arrays.copyOfRange(base, 0, afterIhdr),
				ImageFixtures.chunk("gAMA", new byte[] { 0, 1, (byte) 0x86, (byte) 0xA0 }),
				ImageFixtures.chunk("sRGB", new byte[] { 0 }),
				ImageFixtures.chunk("iCCP", profile),
				ImageFixtures.chunk("acTL", new byte[8]),
				ImageFixtures.chunk("fcTL", new byte[26]),
				Arrays.copyOfRange(base, afterIhdr, base.length - 12),
				ImageFixtures.chunk("fdAT", new byte[10]),
				Arrays.copyOfRange(base, base.length - 12, base.length));

		assertThat(pngChunkTypes(sanitize(decorated))).containsExactly("IHDR", "gAMA", "sRGB", "iCCP", "IDAT", "IEND");

		byte[] huge = ImageFixtures.concat(Arrays.copyOfRange(base, 0, afterIhdr), ImageFixtures.chunk("iCCP", new byte[ImageMetadataSanitizer.MAX_ICC_BYTES + 1]),
				Arrays.copyOfRange(base, afterIhdr, base.length));
		assertThat(pngChunkTypes(sanitize(huge))).doesNotContain("iCCP");
	}

	@Test
	void indexedPngWithTransparencyKeepsItsPaletteAndAlpha() throws IOException {
		BufferedImage indexed = indexedImageWithTransparency();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		ImageIO.write(indexed, "png", out);
		byte[] original = out.toByteArray();
		assertThat(pngChunkTypes(original)).contains("PLTE", "tRNS");

		byte[] clean = sanitize(original);

		assertThat(pngChunkTypes(clean)).contains("PLTE", "tRNS");
		assertSamePixels(original, clean);
	}

	// ======================= WebP =======================

	@Test
	void webpKeepsOnlyTheBitstreamAndTheOrientation() {
		byte[] original = ImageFixtures.webpWithMetadata(8, null);
		assertThat(webpChunkTypes(original)).containsExactly("VP8X", "VP8L", "EXIF", "XMP ", "JUNK");

		byte[] clean = sanitize(original);

		assertThat(containedSecrets(clean)).isEmpty();
		assertThat(webpChunkTypes(clean)).containsExactly("VP8X", "VP8L", "EXIF");
		assertThat(webpExifLength(clean)).isEqualTo(26);
		assertThat(clean[20] & 0xFF).isEqualTo(0x08);                                           // flag del VP8X: solo "ha EXIF"
		assertThat(ImageMetadataSanitizer.exifOrientation(webpExif(clean), 0, 26)).isEqualTo(8);
	}

	@Test
	void webpKeepsAPlausibleColorProfileAndDropsAnImplausibleOne() {
		byte[] good = sanitize(ImageFixtures.webpWithMetadata(1, ImageFixtures.iccProfile(560)));
		assertThat(webpChunkTypes(good)).containsExactly("VP8X", "ICCP", "VP8L");
		assertThat(good[20] & 0xFF).isEqualTo(0x20);

		byte[] bad = ImageFixtures.iccProfile(560);
		bad[36] = 'x';
		byte[] dropped = sanitize(ImageFixtures.webpWithMetadata(1, bad));
		assertThat(webpChunkTypes(dropped)).containsExactly("VP8X", "VP8L");
		assertThat(dropped[20] & 0xFF).isZero();
	}

	@Test
	void simpleWebpFilesAreLeftByteForByte() {
		assertThat(sanitize(ImageFixtures.webpLossy())).isEqualTo(ImageFixtures.webpLossy());
		assertThat(sanitize(ImageFixtures.webpLossless())).isEqualTo(ImageFixtures.webpLossless());
	}

	@Test
	void theOldWebpWithAnExifPayloadThatIsNotTiffLosesItWithoutAnyOrientation() {
		byte[] clean = sanitize(ImageFixtures.webpWithExif());

		assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("GPS-FAKE-DATA");
		assertThat(webpChunkTypes(clean)).containsExactly("VP8X", "VP8L");
	}

	// ======================= EXIF ostile =======================

	@Test
	void orientationIsReadFromBothByteOrdersAndOnlyWhenValid() {
		for (boolean little : new boolean[] { true, false }) {
			for (int value = 1; value <= 8; value++) {
				byte[] tiff = ImageFixtures.exifTiff(little, value);
				assertThat(ImageMetadataSanitizer.exifOrientation(tiff, 0, tiff.length)).isEqualTo(value);
			}
			for (int invalid : new int[] { 0, 9, 255 }) {
				byte[] tiff = ImageFixtures.exifTiff(little, invalid);
				assertThat(ImageMetadataSanitizer.exifOrientation(tiff, 0, tiff.length)).isZero();
			}
		}
	}

	@Test
	void malformedOrHostileExifNeverThrowsAndYieldsNoOrientation() {
		byte[] valid = ImageFixtures.exifTiff(false, 6);
		List<byte[]> hostile = new ArrayList<>();
		for (int cut = 0; cut < valid.length; cut++) {                                          // troncato in ogni punto: mai un'eccezione
			int orientation = ImageMetadataSanitizer.exifOrientation(Arrays.copyOf(valid, cut), 0, cut);
			assertThat(orientation).as("cut at %d", cut).isIn(0, 6);
			if (cut < 34) {
				assertThat(orientation).as("la voce Orientation (byte 22-34) non e' ancora completa a %d", cut).isZero();
			}
		}
		byte[] hugeOffset = valid.clone();
		hugeOffset[4] = (byte) 0xFF;
		hugeOffset[5] = (byte) 0xFF;
		hugeOffset[6] = (byte) 0xFF;
		hugeOffset[7] = (byte) 0xFF;                                                            // primo IFD oltre la fine
		byte[] hugeCountNoOrientation = valid.clone();
		hugeCountNoOrientation[8] = (byte) 0xFF;
		hugeCountNoOrientation[9] = (byte) 0xFF;                                                // 65535 voci dichiarate in un blocco da poche decine di byte
		hugeCountNoOrientation[23] = 0x13;                                                      // e nessuna e' Orientation: la lettura arriva al limite del blocco
		byte[] badMagic = valid.clone();
		badMagic[3] = 43;
		byte[] notTiff = ImageFixtures.ascii("not a tiff block at all, just text");
		hostile.addAll(List.of(hugeOffset, hugeCountNoOrientation, badMagic, notTiff, new byte[0]));

		for (byte[] block : hostile) {
			assertThat(ImageMetadataSanitizer.exifOrientation(block, 0, block.length)).as("block of %d bytes", block.length).isZero();
		}
		byte[] hugeCountWithOrientation = valid.clone();
		hugeCountWithOrientation[8] = (byte) 0xFF;
		hugeCountWithOrientation[9] = (byte) 0xFF;
		assertThat(ImageMetadataSanitizer.exifOrientation(hugeCountWithOrientation, 0, valid.length)).isEqualTo(6);   // trovata prima di uscire dai limiti
		assertThat(ImageMetadataSanitizer.exifOrientation(valid, 4, valid.length)).isZero();     // intervallo oltre il buffer
		assertThat(ImageMetadataSanitizer.exifOrientation(valid, -1, 20)).isZero();
	}

	@Test
	void aJpegWhoseExifIsBrokenIsStillAcceptedJustWithoutOrientation() {
		byte[] broken = ImageFixtures.withJpegSegment(ImageFixtures.jpeg(), 0xE1,
				ImageFixtures.concat(ImageFixtures.ascii("Exif\0\0"), Arrays.copyOf(ImageFixtures.exifTiff(true, 6), 14)));

		byte[] clean = sanitize(broken);

		assertThat(jpegHeaderMarkers(clean)).containsOnly(0xDB, 0xC0, 0xC4, 0xDA);
		assertThat(containedSecrets(clean)).isEmpty();
	}

	// ======================= struttura incoerente =======================

	@Test
	void aJpegWhoseScanNeverReachesAMarkerIsRejectedNotPublished() {
		byte[] jpeg = ImageFixtures.jpeg();
		// senza l'EOI finale i dati compressi non hanno fine: ImageInspector lo scarta gia', qui si prova la difesa in profondita'
		byte[] unterminated = ImageFixtures.concat(Arrays.copyOf(jpeg, jpeg.length - 2), new byte[] { 1, 2, 3, 4 });
		ImageInfo info = new ImageInfo(ImageType.JPEG, ImageFixtures.WIDTH, ImageFixtures.HEIGHT);

		assertThatThrownBy(() -> ImageMetadataSanitizer.sanitize(unterminated, info)).isInstanceOf(UploadException.class);
	}

	@Test
	void theSanitizedResultMustMatchTheDimensionsOfTheOriginal() {
		byte[] png = ImageFixtures.png(40, 30);
		ImageInfo lying = new ImageInfo(ImageType.PNG, 41, 30);

		assertThatThrownBy(() -> ImageMetadataSanitizer.sanitize(png, lying)).isInstanceOf(UploadException.class);
	}

	// ======================= helper =======================

	private static byte[] sanitize(byte[] data) {
		return ImageMetadataSanitizer.sanitize(data, ImageInspector.inspect(data));
	}

	private static byte[] concat(byte[]... parts) {
		return ImageFixtures.concat(parts);
	}

	/** I segnaposto presenti nei byte (cercati come testo ISO-8859-1, quindi anche dentro i blocchi binari). */
	private static List<String> containedSecrets(byte[] data) {
		String text = new String(data, StandardCharsets.ISO_8859_1);
		return ImageFixtures.SECRETS.stream().filter(text::contains).toList();
	}

	private static void assertSamePixels(byte[] original, byte[] clean) throws IOException {
		BufferedImage a = ImageIO.read(new ByteArrayInputStream(original));
		BufferedImage b = ImageIO.read(new ByteArrayInputStream(clean));
		assertThat(a).isNotNull();
		assertThat(b).isNotNull();
		assertThat(b.getWidth()).isEqualTo(a.getWidth());
		assertThat(b.getHeight()).isEqualTo(a.getHeight());
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					throw new AssertionError("Pixel (" + x + "," + y + ") differs after sanitizing");
				}
			}
		}
	}

	private static BufferedImage indexedImageWithTransparency() {
		byte[] red = { (byte) 255, 0, 0, 100 };
		byte[] green = { 0, (byte) 255, 0, 100 };
		byte[] blue = { 0, 0, (byte) 255, 100 };
		byte[] alpha = { (byte) 255, (byte) 128, 0, (byte) 255 };
		java.awt.image.IndexColorModel model = new java.awt.image.IndexColorModel(2, 4, red, green, blue, alpha);
		BufferedImage image = new BufferedImage(16, 12, BufferedImage.TYPE_BYTE_BINARY, model);
		for (int x = 0; x < 16; x++) {
			for (int y = 0; y < 12; y++) {
				image.getRaster().setSample(x, y, 0, (x + y) % 4);
			}
		}
		return image;
	}

	/** Marker dei segmenti dall'inizio fino alla prima scansione (SOS incluso). */
	private static List<Integer> jpegHeaderMarkers(byte[] d) {
		List<Integer> markers = new ArrayList<>();
		int pos = 2;
		while (pos + 4 <= d.length) {
			int marker = d[pos + 1] & 0xFF;
			markers.add(marker);
			if (marker == 0xDA) {
				break;
			}
			pos += 2 + (((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF));
		}
		return markers;
	}

	/** Quante volte compare "0xFF marker" (in testa ai segmenti e tra le scansioni; nei dati compressi 0xFF e' sempre seguito da 0x00 o RSTn). */
	private static int countOf(byte[] d, int marker) {
		int count = 0;
		for (int i = 0; i + 1 < d.length; i++) {
			if ((d[i] & 0xFF) == 0xFF && (d[i + 1] & 0xFF) == marker) {
				count++;
			}
		}
		return count;
	}

	private static int countEoi(byte[] d) {
		return countOf(d, 0xD9);
	}

	private static int exifOrientationOf(byte[] jpeg) {
		int pos = 2;
		while (pos + 4 <= jpeg.length && (jpeg[pos + 1] & 0xFF) != 0xDA) {
			int length = ((jpeg[pos + 2] & 0xFF) << 8) | (jpeg[pos + 3] & 0xFF);
			if ((jpeg[pos + 1] & 0xFF) == 0xE1) {
				return ImageMetadataSanitizer.exifOrientation(jpeg, pos + 4 + 6, length - 2 - 6);
			}
			pos += 2 + length;
		}
		return 0;
	}

	private static int exifBlockLength(byte[] jpeg) {
		return (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF)) - 2 - 6;
	}

	/** Inserisce un commento subito dopo la prima scansione di un JPEG progressivo (prima della successiva). */
	private static byte[] withCommentAfterFirstScan(byte[] jpeg, String comment) {
		int sos = -1;
		for (int i = 2; i + 1 < jpeg.length; ) {
			int marker = jpeg[i + 1] & 0xFF;
			if (marker == 0xDA) {
				sos = i;
				break;
			}
			i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
		}
		int pos = sos + 2 + (((jpeg[sos + 2] & 0xFF) << 8) | (jpeg[sos + 3] & 0xFF));
		while (pos + 1 < jpeg.length) {                                                         // fine dei dati compressi della prima scansione
			int next = jpeg[pos + 1] & 0xFF;
			if ((jpeg[pos] & 0xFF) == 0xFF && next != 0x00 && !(next >= 0xD0 && next <= 0xD7) && next != 0xFF) {
				break;
			}
			pos++;
		}
		return ImageFixtures.concat(Arrays.copyOfRange(jpeg, 0, pos), ImageFixtures.segment(0xFE, ImageFixtures.ascii(comment)),
				Arrays.copyOfRange(jpeg, pos, jpeg.length));
	}

	private static List<String> pngChunkTypes(byte[] png) {
		List<String> types = new ArrayList<>();
		for (int pos = 8; pos + 12 <= png.length; ) {
			long length = ((png[pos] & 0xFFL) << 24) | ((png[pos + 1] & 0xFFL) << 16) | ((png[pos + 2] & 0xFFL) << 8) | (png[pos + 3] & 0xFFL);
			types.add(new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1));
			pos += 12 + (int) length;
		}
		return types;
	}

	private static byte[] pngExifBlock(byte[] png) {
		for (int pos = 8; pos + 12 <= png.length; ) {
			int length = (int) (((png[pos] & 0xFFL) << 24) | ((png[pos + 1] & 0xFFL) << 16) | ((png[pos + 2] & 0xFFL) << 8) | (png[pos + 3] & 0xFFL));
			if (new String(png, pos + 4, 4, StandardCharsets.ISO_8859_1).equals("eXIf")) {
				return Arrays.copyOfRange(png, pos + 8, pos + 8 + length);
			}
			pos += 12 + length;
		}
		return new byte[0];
	}

	private static List<String> webpChunkTypes(byte[] webp) {
		List<String> types = new ArrayList<>();
		for (int pos = 12; pos + 8 <= webp.length; ) {
			long size = (webp[pos + 4] & 0xFFL) | ((webp[pos + 5] & 0xFFL) << 8) | ((webp[pos + 6] & 0xFFL) << 16) | ((webp[pos + 7] & 0xFFL) << 24);
			types.add(new String(webp, pos, 4, StandardCharsets.ISO_8859_1));
			pos += 8 + (int) (size + (size & 1));
		}
		return types;
	}

	private static byte[] webpExif(byte[] webp) {
		for (int pos = 12; pos + 8 <= webp.length; ) {
			int size = (webp[pos + 4] & 0xFF) | ((webp[pos + 5] & 0xFF) << 8) | ((webp[pos + 6] & 0xFF) << 16) | ((webp[pos + 7] & 0xFF) << 24);
			if (new String(webp, pos, 4, StandardCharsets.ISO_8859_1).equals("EXIF")) {
				return Arrays.copyOfRange(webp, pos + 8, pos + 8 + size);
			}
			pos += 8 + size + (size & 1);
		}
		return new byte[0];
	}

	private static int webpExifLength(byte[] webp) {
		return webpExif(webp).length;
	}

}
