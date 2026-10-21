package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.upload.ImageInspector.ImageInfo;

/** Il riconoscimento del formato guarda solo i byte: nessun file e' fidato per nome o MIME. */
class ImageInspectorTest {

	// ======================= file validi =======================

	@Test
	void recognizesJpegPngAndWebpFromTheirBytes() {
		assertInfo(ImageFixtures.jpeg(), ImageType.JPEG);
		assertInfo(ImageFixtures.png(), ImageType.PNG);
		assertInfo(ImageFixtures.webpLossy(), ImageType.WEBP);
		assertInfo(ImageFixtures.webpLossless(), ImageType.WEBP);
		assertInfo(ImageFixtures.webpWithExif(), ImageType.WEBP);
	}

	@Test
	void readsTheRealDimensionsWithoutDecodingPixels() {
		ImageInfo big = ImageInspector.inspect(ImageFixtures.png(1200, 800));
		assertThat(big.width()).isEqualTo(1200);
		assertThat(big.height()).isEqualTo(800);
		ImageInfo jpeg = ImageInspector.inspect(ImageFixtures.jpeg(640, 480));
		assertThat(jpeg.width()).isEqualTo(640);
		assertThat(jpeg.height()).isEqualTo(480);
	}

	@Test
	void acceptsTheLargestAllowedPicture() {
		ImageInfo info = ImageInspector.inspect(ImageFixtures.pngClaiming(8_000, 5_000));   // 40 megapixel esatti
		assertThat(info.width()).isEqualTo(8_000);
	}

	// ======================= formati NON consentiti =======================

	@Test
	void rejectsSvgGifAndEveryOtherFormatAsUnsupported() {
		for (byte[] data : List.of(ImageFixtures.svg(), ImageFixtures.gif(), ImageFixtures.html(), ImageFixtures.text(),
				ImageFixtures.pdf(), ImageFixtures.zip(), ImageFixtures.bmp(), ImageFixtures.executable(), new byte[0],
				new byte[] { 1 }, new byte[] { (byte) 0xFF, (byte) 0xD8 }, new byte[100])) {
			assertRejected(data, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		}
	}

	@Test
	void theFileNameOrDeclaredTypeCannotMakeAnImageOutOfScripts() {
		// il riconoscimento non riceve nemmeno nome o MIME: uno script resta uno script
		assertRejected(ImageFixtures.html(), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertRejected(ImageFixtures.svg(), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	// ======================= file danneggiati =======================

	@Test
	void rejectsTruncatedPngJpegAndWebpAtEveryCutPoint() {
		for (byte[] valid : List.of(ImageFixtures.png(), ImageFixtures.jpeg(), ImageFixtures.webpLossy(), ImageFixtures.webpLossless())) {
			for (int length = 8; length < valid.length; length += Math.max(1, valid.length / 40)) {
				byte[] cut = ImageFixtures.truncate(valid, length);
				assertThatThrownBy(() -> ImageInspector.inspect(cut)).as("length %d of %d", cut.length, valid.length)
						.isInstanceOf(UploadException.class);
			}
		}
	}

	@Test
	void rejectsPngWithACorruptedChunk() {
		byte[] png = ImageFixtures.png();
		for (int offset : new int[] { 20, 33, png.length / 2, png.length - 20 }) {
			byte[] broken = png.clone();
			broken[offset] ^= 0x55;                              // un byte alterato: il CRC del chunk non torna piu'
			assertRejected(broken, HttpStatus.BAD_REQUEST);
		}
	}

	@Test
	void rejectsDataHiddenAfterTheEndOfTheImage() {
		assertRejected(ImageFixtures.concat(ImageFixtures.png(), "<script>alert(1)</script>".getBytes()), HttpStatus.BAD_REQUEST);
		assertRejected(ImageFixtures.concat(ImageFixtures.jpeg(), "<script>alert(1)</script>".getBytes()), HttpStatus.BAD_REQUEST);
		assertRejected(ImageFixtures.concat(ImageFixtures.webpLossy(), new byte[] { 0, 0 }), HttpStatus.BAD_REQUEST);
	}

	@Test
	void rejectsPngWithoutPixelDataOrWithAWrongFirstChunk() {
		byte[] signature = Arrays.copyOf(ImageFixtures.png(), 8);
		byte[] ihdr = Arrays.copyOfRange(ImageFixtures.png(), 8, 33);
		assertRejected(ImageFixtures.concat(signature, ihdr, ImageFixtures.chunk("IEND", new byte[0])), HttpStatus.BAD_REQUEST);   // senza IDAT
		assertRejected(ImageFixtures.concat(signature, ImageFixtures.chunk("tEXt", "a\0b".getBytes()), ihdr,
				ImageFixtures.chunk("IEND", new byte[0])), HttpStatus.BAD_REQUEST);                                               // IHDR non e' il primo
		assertRejected(signature, HttpStatus.BAD_REQUEST);                                                                       // solo la firma
	}

	@Test
	void rejectsJpegWithoutEndMarkerOrFrame() {
		byte[] jpeg = ImageFixtures.jpeg();
		assertRejected(ImageFixtures.truncate(jpeg, jpeg.length - 2), HttpStatus.BAD_REQUEST);        // senza EOI
		assertRejected(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 }, HttpStatus.BAD_REQUEST);
		assertRejected(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 4, 0, 0, (byte) 0xFF, (byte) 0xD9 }, HttpStatus.BAD_REQUEST);
	}

	@Test
	void rejectsWebpWithAWrongSizeAnimationOrBrokenHeader() {
		byte[] webp = ImageFixtures.webpLossy();
		byte[] wrongSize = webp.clone();
		wrongSize[4] += 2;                                                         // la dimensione RIFF non coincide con il file
		assertRejected(wrongSize, HttpStatus.BAD_REQUEST);
		assertRejected(ImageFixtures.webpAnimated(), HttpStatus.BAD_REQUEST);      // animazione: non e' una copertina statica
		byte[] noStartCode = webp.clone();
		noStartCode[23] = 0;                                                       // codice di avvio del frame VP8 alterato
		assertRejected(noStartCode, HttpStatus.BAD_REQUEST);
	}

	// ======================= limiti di dimensione =======================

	@Test
	void rejectsPicturesLargerThanTheLimits() {
		assertRejected(ImageFixtures.pngClaiming(8_001, 10), HttpStatus.BAD_REQUEST);          // lato troppo lungo
		assertRejected(ImageFixtures.pngClaiming(10, 8_001), HttpStatus.BAD_REQUEST);
		assertRejected(ImageFixtures.pngClaiming(8_000, 8_000), HttpStatus.BAD_REQUEST);       // 64 MP: troppi pixel
		assertRejected(ImageFixtures.pngClaiming(100_000, 100_000), HttpStatus.BAD_REQUEST);   // "decompression bomb"
		assertRejected(ImageFixtures.pngClaiming(0, 10), HttpStatus.BAD_REQUEST);
		assertRejected(ImageFixtures.pngClaiming(4_294_967_295L, 1), HttpStatus.BAD_REQUEST);  // oltre i 32 bit con segno
	}

	// ======================= helper =======================

	private void assertInfo(byte[] data, ImageType expected) {
		ImageInfo info = ImageInspector.inspect(data);
		assertThat(info.type()).isEqualTo(expected);
		assertThat(info.width()).isEqualTo(ImageFixtures.WIDTH);
		assertThat(info.height()).isEqualTo(ImageFixtures.HEIGHT);
	}

	private void assertRejected(byte[] data, HttpStatus expectedStatus) {
		assertThatThrownBy(() -> ImageInspector.inspect(data)).isInstanceOf(UploadException.class)
				.hasFieldOrPropertyWithValue("status", expectedStatus);
	}

}
