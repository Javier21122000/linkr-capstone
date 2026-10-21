package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.upload.dto.UploadedImageResponse;

/**
 * Il servizio di upload con lo storage vero su una cartella temporanea (@TempDir): niente Spring, niente database.
 * Prova l'unica cosa che conta per la privacy: su disco, e quindi online, finisce solo la versione ripulita.
 */
class EventImageServiceTest {

	@TempDir
	Path root;

	private final UUID user = UUID.randomUUID();

	private EventImageService service() {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		return new EventImageService(storage, new UploadRateLimiter(new UploadProperties(root, 20, 60), Clock.systemUTC()));
	}

	@Test
	void aPhoneJpegWithGpsIsStoredWithoutAnyMetadataAndTheResponseDescribesTheStoredFile() throws IOException {
		byte[] photo = ImageFixtures.jpegWithMetadata(6, true);

		UploadedImageResponse response = service().upload(user, new MockMultipartFile("file", "IMG_0001.JPG", "image/jpeg", photo));

		byte[] stored = Files.readAllBytes(imagesDir().resolve(response.filename()));
		assertThat(response.size()).isEqualTo(stored.length).isLessThan(photo.length);
		assertThat(response.contentType()).isEqualTo("image/jpeg");
		String text = new String(stored, StandardCharsets.ISO_8859_1);
		for (String secret : ImageFixtures.SECRETS) {
			assertThat(text).doesNotContain(secret);
		}
		assertThat(stored).isEqualTo(ImageMetadataSanitizer.sanitize(photo, ImageInspector.inspect(photo)));
		assertThat(ImageInspector.inspect(stored).type()).isEqualTo(ImageType.JPEG);        // e' ancora un JPEG valido per lo stesso ispettore
	}

	@Test
	void theOriginalIsNeverWrittenNotEvenTemporarily() throws IOException {
		service().upload(user, new MockMultipartFile("file", "a.png", "image/png", ImageFixtures.pngWithMetadata(6)));

		List<byte[]> everyFileOnDisk;
		try (Stream<Path> files = Files.walk(root)) {
			everyFileOnDisk = files.filter(Files::isRegularFile).map(EventImageServiceTest::read).toList();
		}
		assertThat(everyFileOnDisk).hasSize(1);                                              // un solo file: niente copia grezza, niente residui
		assertThat(new String(everyFileOnDisk.get(0), StandardCharsets.ISO_8859_1)).doesNotContain("SECRET");
	}

	@Test
	void everyFormatIsCleanedWhenUploaded() throws IOException {
		List<byte[]> samples = List.of(ImageFixtures.jpegWithMetadata(8, false), ImageFixtures.pngWithMetadata(3),
				ImageFixtures.webpWithMetadata(6, null), ImageFixtures.webpWithExif());
		EventImageService service = service();

		for (byte[] sample : samples) {
			UploadedImageResponse response = service.upload(user, new MockMultipartFile("file", "x", null, sample));
			String stored = new String(Files.readAllBytes(imagesDir().resolve(response.filename())), StandardCharsets.ISO_8859_1);
			assertThat(stored).doesNotContain("SECRET").doesNotContain("GPS-FAKE-DATA");
		}
	}

	@Test
	void exactlyFiveMegabytesIsAcceptedAndStoredCleanedAndOneByteMoreIsRejected() throws IOException {
		int max = EventImageService.MAX_SIZE_BYTES;
		EventImageService service = service();

		UploadedImageResponse response = service.upload(user, new MockMultipartFile("file", "limite.png", "image/png", ImageFixtures.pngPaddedTo(max)));

		assertThat(response.size()).isLessThan(max);                                         // il chunk privato usato per arrivare a 5 MB non c'e' piu'
		assertThat(Files.size(imagesDir().resolve(response.filename()))).isEqualTo(response.size());
		assertThatThrownBy(() -> service.upload(user, new MockMultipartFile("file", "troppo.png", "image/png", ImageFixtures.pngPaddedTo(max + 1))))
				.isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
	}

	@Test
	void rejectedFilesLeaveNothingOnDisk() throws IOException {
		EventImageService service = service();
		byte[] truncated = ImageFixtures.truncate(ImageFixtures.jpegWithMetadata(6, true), 300);

		assertThatThrownBy(() -> service.upload(user, new MockMultipartFile("file", "x.jpg", "image/jpeg", truncated)))
				.isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> service.upload(user, new MockMultipartFile("file", "x.gif", "image/gif", ImageFixtures.gif())))
				.isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));
		assertThatThrownBy(() -> service.upload(user, new MockMultipartFile("file", "x.png", "image/jpeg", ImageFixtures.pngWithMetadata(6))))
				.isInstanceOfSatisfying(UploadException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE));   // MIME falso

		assertThat(Files.exists(imagesDir()) ? Files.list(imagesDir()).count() : 0L).isZero();
	}

	private Path imagesDir() {
		return root.resolve("event-images");
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		}
		catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

}
