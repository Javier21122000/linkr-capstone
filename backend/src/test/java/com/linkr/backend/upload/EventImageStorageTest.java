package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** Storage su cartelle temporanee isolate (@TempDir): non tocca mai la cartella di sviluppo. */
class EventImageStorageTest {

	@TempDir
	Path root;

	@Test
	void storesUnderARandomNameWithTheCanonicalExtensionInsideTheBaseFolder() throws IOException {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		byte[] content = "contenuto".getBytes(StandardCharsets.UTF_8);

		String png = storage.store(content, ImageType.PNG);
		String jpg = storage.store(content, ImageType.JPEG);
		String webp = storage.store(content, ImageType.WEBP);

		assertThat(png).matches(EventImageStorage.FILENAME_REGEX).endsWith(".png");
		assertThat(jpg).matches(EventImageStorage.FILENAME_REGEX).endsWith(".jpg");
		assertThat(webp).matches(EventImageStorage.FILENAME_REGEX).endsWith(".webp");
		assertThat(Files.readAllBytes(root.resolve("event-images").resolve(png))).isEqualTo(content);
		assertThat(names(root.resolve("event-images"))).containsExactlyInAnyOrder(png, jpg, webp);   // niente file temporanei residui
	}

	@Test
	void createsThePrivateFolderOnFirstUseOnly() throws IOException {
		EventImageStorage storage = new EventImageStorage(root.resolve("nested/uploads"), UUID::randomUUID);
		assertThat(root.resolve("nested")).doesNotExist();                // nessun effetto collaterale alla creazione

		storage.store(new byte[] { 1 }, ImageType.PNG);

		Path folder = root.resolve("nested/uploads/event-images");
		assertThat(folder).isDirectory();
		if (Files.getFileStore(folder).supportsFileAttributeView("posix")) {
			assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
		}
	}

	@Test
	void neverOverwritesAnExistingFile() throws IOException {
		UUID fixed = UUID.randomUUID();
		EventImageStorage storage = new EventImageStorage(root, () -> fixed);
		String first = storage.store("originale".getBytes(), ImageType.PNG);

		assertThatThrownBy(() -> storage.store("sostituto".getBytes(), ImageType.PNG))
				.isInstanceOf(UploadException.class).hasFieldOrPropertyWithValue("status", HttpStatus.INTERNAL_SERVER_ERROR);

		assertThat(Files.readString(root.resolve("event-images").resolve(first))).isEqualTo("originale");
		assertThat(names(root.resolve("event-images"))).containsExactly(first);      // e il file temporaneo e' stato rimosso
	}

	@Test
	void retriesWithAFreshNameOnACollision() throws IOException {
		UUID taken = UUID.randomUUID();
		UUID free = UUID.randomUUID();
		Deque<UUID> sequence = new ArrayDeque<>(List.of(taken, taken, free));
		EventImageStorage storage = new EventImageStorage(root, sequence::pop);
		storage.store("uno".getBytes(), ImageType.PNG);

		String second = storage.store("due".getBytes(), ImageType.PNG);

		assertThat(second).isEqualTo(free + ".png");
		assertThat(Files.readString(root.resolve("event-images").resolve(taken + ".png"))).isEqualTo("uno");
	}

	@Test
	void findsOnlyRegularFilesWithAGeneratedName() throws IOException {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		String stored = storage.store("x".getBytes(), ImageType.PNG);

		assertThat(storage.find(stored)).isPresent();
		assertThat(storage.exists(stored)).isTrue();
		assertThat(storage.exists(UUID.randomUUID() + ".png")).isFalse();     // formato giusto, file inesistente
	}

	@Test
	void rejectsEveryNameThatIsNotExactlyAGeneratedOne() throws IOException {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		String stored = storage.store("x".getBytes(), ImageType.PNG);
		Files.writeString(root.resolve("secret.txt"), "segreto");
		Files.writeString(root.resolve("event-images").resolve("secret.txt"), "segreto");
		String uuid = stored.substring(0, 36);

		for (String name : new String[] { null, "", "secret.txt", "../secret.txt", "..", ".", "/etc/passwd", "..%2fsecret.txt",
				"%2e%2e/secret.txt", "event-images/" + stored, "./" + stored, stored + "/", stored + "\0", stored + ".txt",
				stored.toUpperCase(), uuid + ".gif", uuid + ".svg", uuid + ".PNG", uuid, uuid + ".png ", " " + stored,
				stored + "\n", "x".repeat(5000) }) {
			assertThat(storage.find(name)).as("name: %s", name).isEmpty();
			assertThat(storage.exists(name)).isFalse();
		}
		assertThatCode(() -> EventImageStorage.isValidFilename(null)).doesNotThrowAnyException();
	}

	@Test
	void doesNotFollowSymbolicLinksOutsideTheFolder() throws IOException {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		storage.store("x".getBytes(), ImageType.PNG);
		Path secret = root.resolve("secret.txt");
		Files.writeString(secret, "segreto");
		Path link = root.resolve("event-images").resolve(UUID.randomUUID() + ".png");
		Files.createSymbolicLink(link, secret);

		assertThat(storage.find(link.getFileName().toString())).isEmpty();
	}

	@Test
	void aFolderWithAValidNameIsNotAFile() throws IOException {
		EventImageStorage storage = new EventImageStorage(root, UUID::randomUUID);
		storage.store("x".getBytes(), ImageType.PNG);
		Path folder = root.resolve("event-images").resolve(UUID.randomUUID() + ".png");
		Files.createDirectory(folder);

		assertThat(storage.find(folder.getFileName().toString())).isEmpty();
	}

	@Test
	void extractsTheFilenameOfAnInternalUrlOnly() {
		String name = UUID.randomUUID() + ".png";

		assertThat(EventImageStorage.filenameOf("/api/uploads/event-images/" + name)).contains(name);
		assertThat(EventImageStorage.filenameOf("https://example.com/a.png")).isEmpty();
		assertThat(EventImageStorage.filenameOf("/api/uploads/other/" + name)).isEmpty();
		assertThat(EventImageStorage.filenameOf(null)).isEmpty();
	}

	@Test
	void refusesAFolderInsideTheGitRepository() throws IOException {
		Path repository = Files.createDirectory(root.resolve("work-copy"));
		Files.createDirectory(repository.resolve(".git"));
		Path backend = Files.createDirectory(repository.resolve("backend"));

		assertThatThrownBy(() -> EventImageStorage.rejectDirectoryInsideTheRepository(repository.resolve("uploads"), backend))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("OUTSIDE the Git repository");
		assertThatThrownBy(() -> EventImageStorage.rejectDirectoryInsideTheRepository(backend.resolve("src/main/resources/static"), backend))
				.isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> EventImageStorage.rejectDirectoryInsideTheRepository(root.resolve("elsewhere"), backend))
				.doesNotThrowAnyException();
	}

	private List<String> names(Path folder) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map(path -> path.getFileName().toString()).toList();
		}
	}

}
