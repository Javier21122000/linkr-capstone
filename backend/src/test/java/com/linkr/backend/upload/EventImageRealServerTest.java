package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.support.TempUploads;

/**
 * Upload attraverso un server VERO (Tomcat su porta casuale) e client HTTP vero: e' l'unico modo di provare i limiti del parser
 * multipart, le richieste malformate e le intestazioni effettive (MockMvc non le applica).
 *
 * Non usa @IntegrationTest perche' quello non apre una porta; URL e nome del database di test sono gli stessi. Non e' @Transactional
 * (il server risponde su altri thread): a fine test cancella SOLO l'utente creato e la sua cartella temporanea di upload.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/linkr_test_db",
		"linkr.database.expected-name=linkr_test_db" })
@ActiveProfiles("test")
class EventImageRealServerTest {

	private static final Path STORAGE = TempUploads.create();
	private static final Path IMAGES = STORAGE.resolve("event-images");
	private static final String BOUNDARY = "----linkrTestBoundary7MA4YWxkTrZu0gW";

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("linkr.uploads.dir", STORAGE::toString);
	}

	@AfterAll
	static void deleteStorage() {
		TempUploads.delete(STORAGE);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final List<UUID> users = new ArrayList<>();
	private String token;

	@BeforeEach
	void setUp() {
		TempUploads.clear(STORAGE);
		String name = "rs" + UUID.randomUUID().toString().substring(0, 8);
		UUID id = jdbc.sql("INSERT INTO app_user (username, email, password_hash, display_name) VALUES (:u, :e, 'not-a-hash', :u) RETURNING id")
				.param("u", name).param("e", name + "@example.com").query(UUID.class).single();
		users.add(id);
		token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(id.toString()).claim("roles", List.of("USER"))
						.issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build())).getTokenValue();
	}

	@AfterEach
	void cleanUp() {
		if (!users.isEmpty()) {
			jdbc.sql("DELETE FROM app_user WHERE id IN (:ids)").param("ids", users).update();
		}
	}

	// ======================= upload e lettura =======================

	@Test
	void anImageIsUploadedAndReadBackWithTheSecurityHeadersOverRealHttp() throws Exception {
		byte[] png = ImageFixtures.png(200, 100);

		HttpResponse<byte[]> uploaded = post(multipart("foto.png", "image/png", png));
		assertThat(uploaded.statusCode()).isEqualTo(201);
		assertThat(uploaded.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
		String body = new String(uploaded.body(), StandardCharsets.UTF_8);
		String imageUrl = JsonPath.read(body, "$.imageUrl");
		assertThat((Integer) JsonPath.read(body, "$.size")).isEqualTo(png.length);

		// lettura PUBBLICA (nessun token)
		HttpResponse<byte[]> read = get(imageUrl, false);
		assertThat(read.statusCode()).isEqualTo(200);
		assertThat(read.body()).isEqualTo(png);
		assertThat(read.headers().firstValue("Content-Type")).contains("image/png");
		assertThat(read.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
		assertThat(read.headers().firstValue("Content-Security-Policy")).contains("default-src 'none'; sandbox");
		assertThat(read.headers().firstValue("Cross-Origin-Resource-Policy")).contains("cross-origin");
		assertThat(read.headers().firstValue("Content-Disposition")).contains("inline");
		String cacheControl = read.headers().firstValue("Cache-Control").orElse("");
		assertThat(cacheControl).contains("max-age=31536000").contains("public").contains("immutable").doesNotContain("no-store");
		assertThat(read.headers().firstValue("Set-Cookie")).isEmpty();            // nessuna sessione e nessun cookie per un'immagine
		assertThat(read.headers().firstValue("X-Frame-Options")).isPresent();     // gli header di Spring Security restano
	}

	@Test
	void thePathOfTheOriginalFileNameNeverReachesTheDisk() throws Exception {
		for (String evil : List.of("../../../../tmp/evil.png", "..\\..\\evil.png", "%2e%2e%2fevil.png", "/etc/passwd")) {
			HttpResponse<byte[]> response = post(multipart(evil, "image/png", ImageFixtures.png()));
			assertThat(response.statusCode()).as(evil).isEqualTo(201);
			assertThat(new String(response.body(), StandardCharsets.UTF_8)).doesNotContain("evil").doesNotContain("passwd");
		}
		try (Stream<Path> all = Files.walk(STORAGE)) {
			assertThat(all.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).toList())
					.hasSize(4).allMatch(name -> name.matches(EventImageStorage.FILENAME_REGEX));
		}
		assertThat(Files.exists(Path.of("/tmp/evil.png"))).isFalse();
	}

	@Test
	void arbitraryFilesAndTraversalCannotBeReadOverRealHttp() throws Exception {
		Files.createDirectories(IMAGES);
		Files.writeString(STORAGE.resolve("secret.txt"), "SEGRETO-FUORI");
		Files.writeString(IMAGES.resolve("secret.txt"), "SEGRETO-DENTRO");
		for (String path : List.of("/api/uploads/event-images/secret.txt", "/api/uploads/event-images/..%2fsecret.txt",
				"/api/uploads/event-images/%2e%2e/secret.txt", "/api/uploads/event-images/..%5csecret.txt",
				"/api/uploads/event-images/%252e%252e%252fsecret.txt", "/api/uploads/event-images/", "/api/uploads/event-images",
				"/api/uploads/event-images/.", "/api/uploads/")) {
			HttpResponse<byte[]> response = get(path, false);
			assertThat(response.statusCode()).as(path).isNotEqualTo(200);
			assertThat(new String(response.body(), StandardCharsets.UTF_8)).as(path).doesNotContain("SEGRETO");
		}
	}

	// ======================= limiti e richieste malformate =======================

	@Test
	void aMultipartRequestOverTheLimitIsRejectedWith413AndTheServerKeepsWorking() throws Exception {
		// foto "troppo grandi" realistiche: appena sopra il limite di richiesta (6 MB), 7 MB e 12 MB
		for (int megabytes : new int[] { 6, 7, 12 }) {
			byte[] big = ImageFixtures.pngPaddedTo(megabytes * 1024 * 1024 + 1024);

			HttpResponse<byte[]> response = post(multipart("grande.png", "image/png", big));

			assertThat(response.statusCode()).as("%d MB", megabytes).isEqualTo(413);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
			assertThat(JsonPath.<String>read(new String(response.body(), StandardCharsets.UTF_8), "$.title")).isEqualTo("Upload too large");
			assertThat(storedFiles()).isZero();
			assertThat(post(multipart("piccola.png", "image/png", ImageFixtures.png())).statusCode()).isEqualTo(201);   // il server e' vivo
			TempUploads.clear(STORAGE);
		}
	}

	@Test
	void aHugeRequestIsRefusedWithoutStoringAnythingAndWithoutBreakingTheServer() throws Exception {
		byte[] huge = new byte[40 * 1024 * 1024];
		String outcome;
		try {
			outcome = "HTTP " + post(multipart("enorme.png", "image/png", huge)).statusCode();
		}
		catch (IOException e) {
			outcome = "connection closed by the server (" + e.getClass().getSimpleName() + ")";   // anche questo e' un rifiuto valido
		}
		System.out.println("[EventImageRealServerTest] 40 MB request outcome: " + outcome);

		assertThat(outcome).satisfiesAnyOf(value -> assertThat(value).isEqualTo("HTTP 413"), value -> assertThat(value).startsWith("connection closed"));
		assertThat(storedFiles()).isZero();
		assertThat(post(multipart("piccola.png", "image/png", ImageFixtures.png())).statusCode()).isEqualTo(201);
	}

	@Test
	void malformedMultipartBodiesAreClientErrorsNotServerErrors() throws Exception {
		String contentType = "multipart/form-data; boundary=" + BOUNDARY;
		byte[] truncated = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\nContent-Type: image/png\r\n\r\n")
				.concat("not closed").getBytes(StandardCharsets.ISO_8859_1);
		byte[] wrongBoundary = multipart("a.png", "image/png", ImageFixtures.png());

		HttpResponse<byte[]> cut = postRaw(contentType, truncated);
		HttpResponse<byte[]> noBoundary = postRaw("multipart/form-data", wrongBoundary);
		HttpResponse<byte[]> otherBoundary = postRaw("multipart/form-data; boundary=zzz", wrongBoundary);
		HttpResponse<byte[]> garbage = postRaw(contentType, "garbage\r\nnot multipart at all".getBytes(StandardCharsets.ISO_8859_1));

		for (HttpResponse<byte[]> response : List.of(cut, noBoundary, otherBoundary, garbage)) {
			assertThat(response.statusCode()).isBetween(400, 499);
			assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
		}
		assertThat(storedFiles()).isZero();
	}

	@Test
	void uploadWithoutATokenIsRejectedOverRealHttpAndNothingIsWritten() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/uploads/event-images"))
				.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
				.POST(HttpRequest.BodyPublishers.ofByteArray(multipart("a.png", "image/png", ImageFixtures.png()))).build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(storedFiles()).isZero();
	}

	// ======================= helper =======================

	private HttpResponse<byte[]> post(byte[] body) throws Exception {
		return postRaw("multipart/form-data; boundary=" + BOUNDARY, body);
	}

	private HttpResponse<byte[]> postRaw(String contentType, byte[] body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/uploads/event-images"))
				.header("Authorization", "Bearer " + token).header("Content-Type", contentType).timeout(Duration.ofSeconds(60))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private HttpResponse<byte[]> get(String path, boolean authenticated) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
		if (authenticated) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	/** Corpo multipart/form-data con un solo campo "file". */
	private byte[] multipart(String filename, String partType, byte[] data) {
		byte[] head = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: "
				+ partType + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
		return ImageFixtures.concat(head, data, tail);
	}

	private long storedFiles() throws IOException {
		if (!Files.isDirectory(STORAGE)) {
			return 0;
		}
		try (Stream<Path> all = Files.walk(STORAGE)) {
			return all.filter(Files::isRegularFile).count();
		}
	}

}
