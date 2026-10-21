package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
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
 * Compatibilita' tra l'URL restituito dall'upload e il campo imageUrl degli eventi (POST/PUT /api/events), con linkr_test_db
 * e una cartella temporanea isolata. La validazione HTTPS degli URL ESTERNI non cambia.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class EventImageEventIntegrationTest {

	private static final Path STORAGE = TempUploads.create();

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

	private TestUser anna;

	@BeforeEach
	void setUp() {
		TempUploads.clear(STORAGE);
		anna = new TestUsers(mockMvc, jdbc, null).user("ei_anna");
	}

	@Test
	void anUploadedImageCanBeUsedAsTheCoverOfAnEventAndIsShownPublicly() throws Exception {
		String url = uploadPng();

		String id = JsonPath.read(createEvent(payload(url, "Tavolo apparecchiato")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.imageUrl").value(url)).andExpect(jsonPath("$.imageAlt").value("Tavolo apparecchiato"))
				.andReturn().getResponse().getContentAsString(), "$.id");

		// dettaglio e lista pubblici riportano lo stesso URL, e l'immagine si legge senza token
		mockMvc.perform(get("/api/events/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(url));
		mockMvc.perform(get("/api/events")).andExpect(jsonPath("$[?(@.id=='" + id + "')].imageUrl").value(url));
		mockMvc.perform(get(url)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
		assertThat(jdbc.sql("SELECT image_url FROM event WHERE id = :id").param("id", UUID.fromString(id)).query(String.class).single()).isEqualTo(url);
	}

	@Test
	void externalHttpsUrlsStillWorkAndStillRequireHttps() throws Exception {
		createEvent(payload("https://example.com/cover.jpg", "Una foto")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.imageUrl").value("https://example.com/cover.jpg"));

		for (String bad : List.of("http://example.com/cover.jpg", "ftp://example.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
				"file:///etc/passwd", "//evil.example/a.png", "example.com/a.png", "https://", "https://exa mple.com/a.png")) {
			createEvent(payload(bad, "Una foto")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.imageUrl").exists());
		}
	}

	@Test
	void onlyTheExactInternalFormThatExistsIsAccepted() throws Exception {
		String url = uploadPng();
		String name = url.substring(url.lastIndexOf('/') + 1);
		String missing = "/api/uploads/event-images/" + UUID.randomUUID() + ".png";

		// forma giusta ma file inesistente: la validazione di formato passa, quella di esistenza no
		createEvent(payload(missing, "Alt")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.imageUrl").exists());

		for (String bad : List.of(
				"/api/uploads/event-images/../../etc/passwd", "/api/uploads/event-images/" + name + "/", "/api/uploads/event-images/" + name + "?x=1",
				"/api/uploads/event-images/" + name + "#frag", "/api/uploads/event-images/" + name.toUpperCase(),
				"/api/uploads/event-images/" + name.replace(".png", ".gif"), "/api/uploads/event-images/" + name.replace(".png", ".svg"),
				"/api/uploads/event-images/" + name + ".txt", "/api/uploads/event-images/%2e%2e/" + name, "/api/uploads/other/" + name,
				"/api/uploads/" + name, "/uploads/event-images/" + name, "api/uploads/event-images/" + name,
				"http://localhost:8080" + url, "/api/uploads/event-images/", "/api/uploads/event-images",
				"/etc/passwd", "/api/users/me", " " + url, url + " ", url + "\n")) {
			int code = createEvent(payload(bad, "Alt")).andReturn().getResponse().getStatus();
			assertThat(code).as("imageUrl: '%s'", bad).isEqualTo(400);
		}
		createEvent(payload(url, "Alt")).andExpect(status().isCreated());                      // e quella giusta passa
		assertThat(jdbc.sql("SELECT count(*) FROM event WHERE image_url LIKE '/api/uploads/%'").query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void anInternalImageStillNeedsItsAlternativeText() throws Exception {
		createEvent(payload(uploadPng(), null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.imageAlt").exists());
	}

	@Test
	void anEventCanBeUpdatedKeepingItsImageOrSwitchingToAnotherSource() throws Exception {
		String first = uploadPng();
		String second = uploadPng();
		String id = JsonPath.read(createEvent(payload(first, "Alt")).andReturn().getResponse().getContentAsString(), "$.id");

		update(id, payload(first, "Alt aggiornato")).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(first));
		update(id, payload(second, "Alt")).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(second));
		update(id, payload("https://example.com/x.png", "Alt")).andExpect(status().isOk());
		update(id, payload("/api/uploads/event-images/" + UUID.randomUUID() + ".png", "Alt")).andExpect(status().isBadRequest());
		update(id, payload(null, null)).andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").doesNotExist());
	}

	// ======================= helper =======================

	private String uploadPng() throws Exception {
		String body = mockMvc.perform(multipart("/api/uploads/event-images")
						.file(new MockMultipartFile("file", "a.png", "image/png", ImageFixtures.png()))
						.header(HttpHeaders.AUTHORIZATION, anna.bearer())).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.imageUrl");
	}

	private ResultActions createEvent(Map<String, Object> payload) throws Exception {
		return mockMvc.perform(post("/api/events").header(HttpHeaders.AUTHORIZATION, anna.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json(payload)));
	}

	private ResultActions update(String id, Map<String, Object> payload) throws Exception {
		return mockMvc.perform(put("/api/events/" + id).header(HttpHeaders.AUTHORIZATION, anna.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json(payload)));
	}

	private Map<String, Object> payload(String imageUrl, String imageAlt) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("title", "Aperitivo con copertina");
		p.put("description", "Un aperitivo tra persone con interessi simili.");
		p.put("category", "SOCIAL");
		p.put("startsAt", Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
		p.put("venueName", "Darsena");
		p.put("address", "Piazza XXIV Maggio, Milano");
		p.put("neighbourhood", "Navigli");
		p.put("latitude", 45.4527);
		p.put("longitude", 9.1757);
		if (imageUrl != null) {
			p.put("imageUrl", imageUrl);
		}
		if (imageAlt != null) {
			p.put("imageAlt", imageAlt);
		}
		return p;
	}

	private String json(Map<String, Object> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Object> e : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(e.getKey()).append("\":");
			Object v = e.getValue();
			if (v instanceof Number) {
				sb.append(v);
			}
			else {
				sb.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"');
			}
		}
		return sb.append('}').toString();
	}

}
