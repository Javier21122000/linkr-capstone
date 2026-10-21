package com.linkr.backend.upload;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Cartella delle immagini degli eventi, FUORI dal repository (linkr.uploads.dir), mai nel database.
 *
 * Garanzie:
 *  - il nome del file lo genera SOLO il server: UUID casuale + estensione canonica del formato RILEVATO dal contenuto.
 *    Il nome originale del client non entra mai in un percorso;
 *  - si scrive in un file temporaneo e poi lo si sposta SENZA sovrascrivere: un file esistente non si tocca mai
 *    e nessuno puo' leggere un file scritto a meta';
 *  - in lettura passa solo un nome che rispetta ESATTAMENTE il formato generato (FILENAME_REGEX), risolto sotto la cartella base,
 *    che deve essere un file regolare e non un link simbolico: nessun percorso arbitrario, nessuna lista di cartelle.
 *
 * La cartella si crea al primo upload (permessi 700 dove possibile). All'avvio si rifiuta una cartella dentro la copia di lavoro di Git.
 */
@Component
public class EventImageStorage {

	/** Percorso pubblico (relativo alla radice del sito) da cui si leggono le immagini. */
	public static final String PUBLIC_PATH = "/api/uploads/event-images/";

	/** Unico formato di nome ammesso: UUID minuscolo + estensione canonica. E' una costante per poter stare in una @Pattern. */
	public static final String FILENAME_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:jpg|png|webp)";

	private static final Logger log = LoggerFactory.getLogger(EventImageStorage.class);
	private static final Pattern FILENAME = Pattern.compile(FILENAME_REGEX);
	private static final int MAX_NAME_ATTEMPTS = 3;

	private final Path base;
	private final Supplier<UUID> uuids;

	@Autowired
	public EventImageStorage(UploadProperties properties) {
		this(properties.dir(), UUID::randomUUID);
	}

	/** Costruttore per i test: permette di forzare i nomi (collisioni). */
	EventImageStorage(Path directory, Supplier<UUID> uuids) {
		this.base = directory.toAbsolutePath().normalize().resolve("event-images");
		this.uuids = uuids;
		rejectDirectoryInsideTheRepository(this.base, Path.of("").toAbsolutePath());
	}

	/** Nome del file di un URL interno ("/api/uploads/event-images/<nome>"), vuoto se l'URL non e' interno. */
	public static Optional<String> filenameOf(String imageUrl) {
		if (imageUrl != null && imageUrl.startsWith(PUBLIC_PATH)) {
			return Optional.of(imageUrl.substring(PUBLIC_PATH.length()));
		}
		return Optional.empty();
	}

	public static boolean isValidFilename(String filename) {
		return filename != null && FILENAME.matcher(filename).matches();
	}

	/** Salva i byte (gia' validati) con un nome nuovo e restituisce il nome. Non sovrascrive mai. */
	String store(byte[] content, ImageType type) {
		Path temp = null;
		try {
			createBaseDirectory();
			temp = Files.createTempFile(base, ".upload-", ".tmp");        // permessi 600 su POSIX; nome che non e' mai servibile
			Files.write(temp, content);
			for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
				String filename = uuids.get() + "." + type.extension();
				try {
					Files.move(temp, base.resolve(filename));             // senza REPLACE_EXISTING: se esiste, eccezione
					temp = null;
					return filename;
				}
				catch (FileAlreadyExistsException e) {
					log.warn("Upload name collision, retrying");
				}
			}
			throw new IOException("Could not find a free file name");
		}
		catch (IOException e) {
			log.error("Could not store an uploaded image", e);
			throw UploadException.storageFailure();
		}
		finally {
			if (temp != null) {
				try {
					Files.deleteIfExists(temp);
				}
				catch (IOException ignored) {
					// il file temporaneo non e' servibile: al massimo resta un residuo
				}
			}
		}
	}

	/** Il file di un'immagine caricata, solo se il nome e' valido ed e' un file regolare (non un link, non una cartella). */
	Optional<Path> find(String filename) {
		if (!isValidFilename(filename)) {
			return Optional.empty();
		}
		Path file = base.resolve(filename).normalize();
		if (!file.startsWith(base) || !base.equals(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
			return Optional.empty();
		}
		return Optional.of(file);
	}

	public boolean exists(String filename) {
		return find(filename).isPresent();
	}

	private void createBaseDirectory() throws IOException {
		if (Files.isDirectory(base)) {
			return;
		}
		try {
			Files.createDirectories(base, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		}
		catch (UnsupportedOperationException e) {
			Files.createDirectories(base);                                 // file system senza permessi POSIX
		}
	}

	/** La copia di lavoro di Git piu' vicina alla cartella corrente: li' dentro le immagini finirebbero tra i file tracciabili. */
	static void rejectDirectoryInsideTheRepository(Path directory, Path workingDirectory) {
		for (Path candidate = workingDirectory; candidate != null; candidate = candidate.getParent()) {
			if (Files.exists(candidate.resolve(".git"))) {
				if (directory.startsWith(candidate)) {
					throw new IllegalStateException("linkr.uploads.dir must be OUTSIDE the Git repository (" + candidate
							+ "): set LINKR_UPLOAD_DIR to a folder elsewhere, for example ~/.linkr/uploads");
				}
				return;
			}
		}
	}

}
