package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.support.TempUploads;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/**
 * Upload e lettura pubblica delle immagini, con il database di test (linkr_test_db) e una CARTELLA TEMPORANEA ISOLATA:
 * non si scrive nulla nel database ne' nella cartella di sviluppo. Gli utenti vivono nella transazione del test (rollback).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class EventImageUploadIntegrationTest {

	private static final Path STORAGE = TempUploads.create();
	private static final Path IMAGES = STORAGE.resolve("event-images");
	private static final String ENDPOINT = "/api/uploads/event-images";

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		registry.add("linkr.uploads.dir", STORAGE::toString);
	}

	@AfterAll
	static void deleteStorage() {
		TempUploads.delete(STORAGE);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	private TestUsers users;
	private TestUser anna;

	@BeforeEach
	void setUp() {
		TempUploads.clear(STORAGE);
		users = new TestUsers(mockMvc, jdbc, null);
		anna = users.user("up_anna");
	}

	// ======================= upload validi =======================

	@Test
	void validJpegIsStoredUnderARandomNameAndDescribedByTheResponse() throws Exception {
		byte[] jpeg = ImageFixtures.jpeg(300, 200);

		String body = upload(anna, "foto.jpg", "image/jpeg", jpeg).andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.contentType").value("image/jpeg"))
				.andExpect(jsonPath("$.size").value(jpeg.length))
				.andReturn().getResponse().getContentAsString();

		assertStored(body, ".jpg", jpeg);
	}

	@Test
	void validPngIsStored() throws Exception {
		byte[] png = ImageFixtures.png(300, 200);

		String body = upload(anna, "foto.png", "image/png", png).andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/png")).andExpect(jsonPath("$.size").value(png.length))
				.andReturn().getResponse().getContentAsString();

		assertStored(body, ".png", png);
	}

	@Test
	void validWebpLossyAndLosslessAreStored() throws Exception {
		for (byte[] webp : List.of(ImageFixtures.webpLossy(), ImageFixtures.webpLossless(), ImageFixtures.webpWithExif())) {
			String body = upload(anna, "foto.webp", "image/webp", webp).andExpect(status().isCreated())
					.andExpect(jsonPath("$.contentType").value("image/webp")).andExpect(jsonPath("$.size").value(webp.length))
					.andReturn().getResponse().getContentAsString();
			assertStored(body, ".webp", webp);
		}
	}

	@Test
	void responseHasExactlyTheDocumentedFieldsAndARelativeInternalUrl() throws Exception {
		String body = upload(anna, "a.png", "image/png", ImageFixtures.png()).andReturn().getResponse().getContentAsString();

		Map<String, Object> root = JsonPath.read(body, "$");
		assertThat(root.keySet()).containsExactlyInAnyOrder("imageUrl", "filename", "contentType", "size");
		String filename = JsonPath.read(body, "$.filename");
		assertThat(filename).matches(EventImageStorage.FILENAME_REGEX);
		assertThat((String) JsonPath.read(body, "$.imageUrl")).isEqualTo("/api/uploads/event-images/" + filename)
				.matches("^/api/uploads/event-images/" + EventImageStorage.FILENAME_REGEX + "$");
		assertThat(body).doesNotContain("a.png").doesNotContain(STORAGE.toString());   // ne' il nome originale ne' percorsi del server
	}

	@Test
	void everyUploadGetsANewNameEvenForTheSameFile() throws Exception {
		byte[] png = ImageFixtures.png();
		String first = JsonPath.read(upload(anna, "same.png", "image/png", png).andReturn().getResponse().getContentAsString(), "$.filename");
		String second = JsonPath.read(upload(anna, "same.png", "image/png", png).andReturn().getResponse().getContentAsString(), "$.filename");

		assertThat(first).isNotEqualTo(second);
		assertThat(storedNames()).containsExactlyInAnyOrder(first, second);
	}

	@Test
	void theDeclaredFileNameAndExtensionAreIgnoredAndTheRealFormatDecides() throws Exception {
		byte[] png = ImageFixtures.png();

		String body = upload(anna, "foto.jpg", "image/png", png).andExpect(status().isCreated())
				.andExpect(jsonPath("$.contentType").value("image/png")).andReturn().getResponse().getContentAsString();

		assertStored(body, ".png", png);       // il nome diceva .jpg: viene salvato come PNG, che e' cio' che e'
	}

	@Test
	void aMissingDeclaredTypeIsAcceptedBecauseTheContentDecides() throws Exception {
		upload(anna, "foto", null, ImageFixtures.png()).andExpect(status().isCreated()).andExpect(jsonPath("$.contentType").value("image/png"));
		upload(anna, "foto", "image/png; charset=binary", ImageFixtures.png()).andExpect(status().isCreated());   // parametri ignorati
		upload(anna, "foto", "IMAGE/PNG", ImageFixtures.png()).andExpect(status().isCreated());                   // maiuscole ignorate
	}

	@Test
	void exactlyFiveMegabytesIsAcceptedAndOneByteMoreIsRejected() throws Exception {
		int max = EventImageService.MAX_SIZE_BYTES;

		upload(anna, "limite.png", "image/png", ImageFixtures.pngPaddedTo(max)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.size").value(max));
		upload(anna, "troppo.png", "image/png", ImageFixtures.pngPaddedTo(max + 1)).andExpect(status().isContentTooLarge())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Upload too large"));
		assertThat(storedNames()).hasSize(1);
	}

	// ======================= rifiuti =======================

	@Test
	void emptyOrMissingFilesAreRejected() throws Exception {
		upload(anna, "vuoto.png", "image/png", new byte[0]).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid upload"));
		mockMvc.perform(multipart(ENDPOINT).header(HttpHeaders.AUTHORIZATION, anna.bearer())).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(multipart(ENDPOINT).file(new MockMultipartFile("other", "a.png", "image/png", ImageFixtures.png()))
				.header(HttpHeaders.AUTHORIZATION, anna.bearer())).andExpect(status().isBadRequest());   // campo con un altro nome
		assertThat(storedNames()).isEmpty();
	}

	@Test
	void aRequestThatIsNotMultipartIsAClientErrorNotAServerError() throws Exception {
		mockMvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, anna.bearer()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"file\":\"x\"}")).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, anna.bearer())).andExpect(status().is4xxClientError());
		assertThat(storedNames()).isEmpty();
	}

	@Test
	void aFalseMimeTypeIsRejected() throws Exception {
		// il tipo dichiarato dice una cosa, i byte un'altra
		assertUnsupported("a.png", "image/png", ImageFixtures.text());        // testo che si finge PNG
		assertUnsupported("a.jpg", "image/jpeg", ImageFixtures.html());       // HTML che si finge JPEG
		assertUnsupported("a.png", "image/png", ImageFixtures.svg());         // SVG che si finge PNG
		assertUnsupported("a.png", "image/png", ImageFixtures.pdf());
		assertUnsupported("a.png", "image/png", ImageFixtures.executable());
		assertUnsupported("a.jpg", "image/jpeg", ImageFixtures.png());        // PNG vero ma dichiarato JPEG
		assertUnsupported("a.png", "image/png", ImageFixtures.jpeg());
		assertUnsupported("a.png", "image/png", ImageFixtures.webpLossy());
		assertUnsupported("a.webp", "image/webp", ImageFixtures.png());
		assertUnsupported("a.png", "application/octet-stream", ImageFixtures.png());
		assertUnsupported("a.png", "text/html", ImageFixtures.png());
		assertThat(storedNames()).isEmpty();
	}

	@Test
	void svgGifAndOtherFormatsAreRejectedWhateverTheyDeclare() throws Exception {
		for (String declared : new String[] { "image/svg+xml", "image/png", "image/jpeg", "image/webp", "image/gif", "application/octet-stream" }) {
			assertUnsupported("a.svg", declared, ImageFixtures.svg());
			assertUnsupported("a.gif", declared, ImageFixtures.gif());
		}
		assertUnsupported("a.bmp", "image/bmp", ImageFixtures.bmp());
		assertUnsupported("a.zip", "application/zip", ImageFixtures.zip());
		assertUnsupported("a.html", "text/html", ImageFixtures.html());
		assertUnsupported("a.pdf", "application/pdf", ImageFixtures.pdf());
		assertThat(storedNames()).isEmpty();
	}

	@Test
	void malformedImagesAreRejectedAndNothingIsStored() throws Exception {
		byte[] png = ImageFixtures.png();
		byte[] jpeg = ImageFixtures.jpeg();
		byte[] webp = ImageFixtures.webpLossy();
		byte[] corruptedPng = png.clone();
		corruptedPng[png.length / 2] ^= 0x55;

		assertInvalid("a.png", "image/png", ImageFixtures.truncate(png, png.length / 2));
		assertInvalid("a.png", "image/png", corruptedPng);
		assertInvalid("a.png", "image/png", ImageFixtures.concat(png, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));  // codice dopo IEND
		assertInvalid("a.jpg", "image/jpeg", ImageFixtures.truncate(jpeg, jpeg.length - 2));
		assertInvalid("a.jpg", "image/jpeg", ImageFixtures.truncate(jpeg, 40));
		assertInvalid("a.webp", "image/webp", ImageFixtures.truncate(webp, webp.length - 3));
		assertInvalid("a.webp", "image/webp", ImageFixtures.webpAnimated());
		assertInvalid("a.png", "image/png", ImageFixtures.pngClaiming(100_000, 100_000));   // dichiara 10 miliardi di pixel
		assertInvalid("a.png", "image/png", java.util.Arrays.copyOf(png, 8));                           // solo la firma
		assertThat(storedNames()).isEmpty();
	}

	@Test
	void errorResponsesNeverEchoTheFileNameOrContent() throws Exception {
		String secretName = "segretissimo-nome-file.png";
		String body = upload(anna, secretName, "image/png", ImageFixtures.text()).andExpect(status().isUnsupportedMediaType())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("segretissimo").doesNotContain("non e' un'immagine").doesNotContain(STORAGE.toString());
	}

	// ======================= sicurezza dell'accesso =======================

	@Test
	void uploadRequiresAValidJwt() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", ImageFixtures.png());
		mockMvc.perform(multipart(ENDPOINT).file(file)).andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(multipart(ENDPOINT).file(file).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
		mockMvc.perform(multipart(ENDPOINT).file(file).header(HttpHeaders.AUTHORIZATION, "Basic abc")).andExpect(status().isUnauthorized());
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(anna.id().toString()).claim("roles", List.of("USER"))
						.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build())).getTokenValue();
		mockMvc.perform(multipart(ENDPOINT).file(file).header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)).andExpect(status().isUnauthorized());
		assertThat(storedNames()).isEmpty();            // nemmeno un byte e' stato scritto
	}

	@Test
	void pathTraversalInTheUploadedFileNameNeverReachesThePath() throws Exception {
		for (String evil : List.of("../../../../tmp/evil.png", "..\\..\\..\\evil.png", "%2e%2e%2f%2e%2e%2fevil.png", "/etc/passwd.png",
				"....//....//evil.png", "evil.png\0.jpg", "C:\\Windows\\evil.png", "~/evil.png", "a/b/c/../../../evil.png")) {
			String body = upload(anna, evil, "image/png", ImageFixtures.png()).andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString();
			String filename = JsonPath.read(body, "$.filename");
			assertThat(filename).matches(EventImageStorage.FILENAME_REGEX);
			assertThat(IMAGES.resolve(filename)).isRegularFile();
		}
		// tutto e solo dentro la cartella delle immagini, con nomi generati; niente altrove nella cartella base
		assertThat(storedNames()).hasSize(9).allMatch(name -> name.matches(EventImageStorage.FILENAME_REGEX));
		try (Stream<Path> all = Files.walk(STORAGE)) {
			assertThat(all.filter(path -> !path.equals(STORAGE) && !path.equals(IMAGES) && !path.getParent().equals(IMAGES)).toList()).isEmpty();
			assertThat(Files.walk(STORAGE).filter(path -> path.getFileName().toString().contains("evil")).toList()).isEmpty();
		}
		assertThat(Files.exists(Path.of("/tmp/evil.png"))).isFalse();
	}

	// ======================= lettura pubblica =======================

	@Test
	void uploadedImagesAreReadablePubliclyWithSafeHeaders() throws Exception {
		byte[] png = ImageFixtures.png(120, 80);
		String url = JsonPath.read(upload(anna, "a.png", "image/png", png).andReturn().getResponse().getContentAsString(), "$.imageUrl");

		// senza token
		mockMvc.perform(get(url)).andExpect(status().isOk())
				.andExpect(content().contentType("image/png"))
				.andExpect(content().bytes(png))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
				.andExpect(header().string("Cross-Origin-Resource-Policy", "cross-origin"))
				.andExpect(header().string("Content-Disposition", "inline"))
				.andExpect(header().string("Content-Length", String.valueOf(png.length)))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("max-age=31536000"),
						org.hamcrest.Matchers.containsString("public"), org.hamcrest.Matchers.containsString("immutable"))));
		// HEAD pubblico
		mockMvc.perform(head(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
	}

	@Test
	void eachFormatIsServedWithItsOwnFixedContentType() throws Exception {
		for (Object[] sample : new Object[][] { { "image/jpeg", ImageFixtures.jpeg() }, { "image/png", ImageFixtures.png() },
				{ "image/webp", ImageFixtures.webpLossless() } }) {
			byte[] bytes = (byte[]) sample[1];
			String url = JsonPath.read(upload(anna, "x", (String) sample[0], bytes).andReturn().getResponse().getContentAsString(), "$.imageUrl");
			mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType((String) sample[0])).andExpect(content().bytes(bytes));
		}
	}

	@Test
	void onlyFilesUploadedThroughTheEndpointCanBeRead() throws Exception {
		Files.createDirectories(IMAGES);
		Files.writeString(STORAGE.resolve("secret.txt"), "SEGRETO-FUORI");
		Files.writeString(IMAGES.resolve("secret.txt"), "SEGRETO-DENTRO");
		String uuid = UUID.randomUUID().toString();
		Files.writeString(IMAGES.resolve(uuid + ".txt"), "SEGRETO-ESTENSIONE");
		Files.writeString(IMAGES.resolve(uuid + ".html"), "<script>alert(1)</script>");
		Files.writeString(IMAGES.resolve(uuid.toUpperCase() + ".png"), "SEGRETO-MAIUSCOLO");
		Files.writeString(IMAGES.resolve(".upload-123.tmp"), "SEGRETO-TEMP");
		Files.createSymbolicLink(IMAGES.resolve(UUID.randomUUID() + ".png"), STORAGE.resolve("secret.txt"));
		Files.createDirectory(IMAGES.resolve(UUID.randomUUID() + ".png"));

		List<String> names = new java.util.ArrayList<>(List.of("secret.txt", uuid + ".txt", uuid + ".html", uuid.toUpperCase() + ".png",
				".upload-123.tmp", UUID.randomUUID() + ".png", "x.png", uuid, uuid + ".gif", uuid + ".svg"));
		try (Stream<Path> files = Files.list(IMAGES)) {
			files.forEach(path -> names.add(path.getFileName().toString()));         // anche il link simbolico e la cartella
		}
		for (String name : names) {
			String body = mockMvc.perform(get(ENDPOINT + "/{name}", name)).andExpect(status().isNotFound())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andReturn().getResponse().getContentAsString();
			assertThat(body).doesNotContain("SEGRETO").doesNotContain("script");
		}
	}

	@Test
	void pathTraversalOnReadingNeverReturnsAnything() throws Exception {
		Files.createDirectories(IMAGES);
		Files.writeString(STORAGE.resolve("secret.txt"), "SEGRETO-FUORI");
		String valid = JsonPath.read(upload(anna, "a.png", "image/png", ImageFixtures.png()).andReturn().getResponse().getContentAsString(), "$.filename");

		for (String attempt : List.of(
				ENDPOINT + "/..%2fsecret.txt", ENDPOINT + "/..%2f..%2fsecret.txt", ENDPOINT + "/%2e%2e%2fsecret.txt",
				ENDPOINT + "/%2e%2e/secret.txt", ENDPOINT + "/..%5csecret.txt", ENDPOINT + "/....//secret.txt",
				ENDPOINT + "/%252e%252e%252fsecret.txt", ENDPOINT + "/" + valid + "%00.png", ENDPOINT + "/%2fetc%2fpasswd",
				ENDPOINT + "/" + valid + "/../../secret.txt", ENDPOINT + "/" + valid + "%2f..%2f..%2fsecret.txt",
				"/api/uploads/event-images/../../../etc/passwd", "/api/uploads/event-images/%2e%2e/%2e%2e/etc/passwd")) {
			String body = mockMvc.perform(get(java.net.URI.create(attempt))).andReturn().getResponse().getContentAsString();
			int status = mockMvc.perform(get(java.net.URI.create(attempt))).andReturn().getResponse().getStatus();
			assertThat(status).as("attempt: %s", attempt).isNotEqualTo(200);
			assertThat(status).as("attempt: %s", attempt).isBetween(400, 499);
			assertThat(body).as("attempt: %s", attempt).doesNotContain("SEGRETO").doesNotContain("root:");
		}
	}

	@Test
	void thereIsNoDirectoryListing() throws Exception {
		upload(anna, "a.png", "image/png", ImageFixtures.png());
		String name = storedNames().get(0);

		for (String path : List.of(ENDPOINT, ENDPOINT + "/", "/api/uploads", "/api/uploads/", "/api/uploads/event-images/.", "/api/uploads/event-images/*")) {
			String anonymous = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString();
			String signedIn = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, anna.bearer())).andReturn().getResponse().getContentAsString();
			int anonymousStatus = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
			int signedInStatus = mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, anna.bearer())).andReturn().getResponse().getStatus();
			assertThat(anonymousStatus).as(path).isNotEqualTo(200);
			assertThat(signedInStatus).as(path).isNotEqualTo(200);
			assertThat(anonymous + signedIn).as(path).doesNotContain(name);
		}
	}

	@Test
	void theWriteEndpointIsNotAvailableToAnonymousReadersOfTheSameFolder() throws Exception {
		String url = JsonPath.read(upload(anna, "a.png", "image/png", ImageFixtures.png()).andReturn().getResponse().getContentAsString(), "$.imageUrl");

		// la lettura e' pubblica, ma nessun altro metodo lo e': PUT/DELETE/POST senza token -> 401
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url).content("x")).andExpect(status().isUnauthorized());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(url)).andExpect(status().isUnauthorized());
		assertThat(storedNames()).hasSize(1);
	}

	// ======================= helper =======================

	private ResultActions upload(TestUser user, String originalName, String contentType, byte[] bytes) throws Exception {
		return mockMvc.perform(multipart(ENDPOINT).file(new MockMultipartFile("file", originalName, contentType, bytes))
				.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private void assertUnsupported(String name, String declared, byte[] bytes) throws Exception {
		upload(anna, name, declared, bytes).andExpect(status().isUnsupportedMediaType())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Unsupported image"));
	}

	private void assertInvalid(String name, String declared, byte[] bytes) throws Exception {
		upload(anna, name, declared, bytes).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid image"));
	}

	/** Il file risulta salvato con il nome della risposta, estensione canonica e byte identici. */
	private void assertStored(String responseBody, String extension, byte[] expected) throws IOException {
		String filename = JsonPath.read(responseBody, "$.filename");
		assertThat(filename).matches(EventImageStorage.FILENAME_REGEX).endsWith(extension);
		assertThat(Files.readAllBytes(IMAGES.resolve(filename))).isEqualTo(expected);
		assertThat(storedNames()).contains(filename).noneMatch(name -> name.startsWith(".upload-"));
	}

	private List<String> storedNames() throws IOException {
		if (!Files.isDirectory(IMAGES)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(IMAGES)) {
			return files.map(path -> path.getFileName().toString()).toList();
		}
	}

}
