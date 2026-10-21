package com.linkr.backend.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Cartelle temporanee per gli upload dei test: mai la cartella di sviluppo. Ogni classe di test crea la sua e la cancella alla fine. */
public final class TempUploads {

	private TempUploads() {
	}

	public static Path create() {
		try {
			return Files.createTempDirectory("linkr-test-uploads-");
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Cancella la cartella e tutto cio' che contiene, senza seguire i link simbolici. */
	public static void delete(Path directory) {
		if (directory == null || !Files.exists(directory)) {
			return;
		}
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Svuota la cartella (immagini ed eventuali file di prova) tra un test e l'altro, tenendo la cartella base. */
	public static void clear(Path directory) {
		if (!Files.isDirectory(directory)) {
			return;
		}
		try (Stream<Path> children = Files.list(directory)) {
			children.forEach(TempUploads::delete);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
