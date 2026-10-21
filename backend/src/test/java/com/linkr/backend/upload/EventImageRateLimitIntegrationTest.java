package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.ImageFixtures;
import com.linkr.backend.support.TempUploads;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/** Limite di upload per utente, con una soglia bassa (2 all'ora) per poterla raggiungere. Cartella temporanea isolata. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = { "linkr.uploads.max-per-hour=2", "linkr.uploads.max-per-day=100" })
class EventImageRateLimitIntegrationTest {

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

	@Autowired
	private UploadRateLimiter limiter;

	private TestUsers users;

	@BeforeEach
	void setUp() {
		TempUploads.clear(STORAGE);
		users = new TestUsers(mockMvc, jdbc, null);
	}

	@Test
	void anUserOverTheLimitGets429AndNothingIsStoredForThatAttempt() throws Exception {
		TestUser anna = users.user("rl_anna_" + System.nanoTime() % 100000);

		upload(anna, ImageFixtures.png()).andExpect(status().isCreated());
		upload(anna, ImageFixtures.jpeg()).andExpect(status().isCreated());
		upload(anna, ImageFixtures.png()).andExpect(status().isTooManyRequests())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Too many uploads"));
		assertThat(storedCount()).isEqualTo(2);
	}

	@Test
	void invalidAttemptsCountToo() throws Exception {
		TestUser bea = users.user("rl_bea_" + System.nanoTime() % 100000);

		upload(bea, ImageFixtures.html()).andExpect(status().isUnsupportedMediaType());
		upload(bea, ImageFixtures.text()).andExpect(status().isUnsupportedMediaType());
		upload(bea, ImageFixtures.png()).andExpect(status().isTooManyRequests());      // il file valido non passa: budget esaurito
		assertThat(storedCount()).isZero();
	}

	@Test
	void theLimitIsPerUserAndEmptyRequestsDoNotConsumeIt() throws Exception {
		TestUser carl = users.user("rl_carl_" + System.nanoTime() % 100000);
		TestUser dan = users.user("rl_dan_" + System.nanoTime() % 100000);

		upload(carl, new byte[0]).andExpect(status().isBadRequest());                   // un file vuoto non costa nulla
		upload(carl, ImageFixtures.png()).andExpect(status().isCreated());
		upload(carl, ImageFixtures.png()).andExpect(status().isCreated());
		upload(carl, ImageFixtures.png()).andExpect(status().isTooManyRequests());
		upload(dan, ImageFixtures.png()).andExpect(status().isCreated());              // un altro utente non e' toccato
		assertThat(limiter).isNotNull();
	}

	private ResultActions upload(TestUser user, byte[] bytes) throws Exception {
		return mockMvc.perform(multipart("/api/uploads/event-images").file(new MockMultipartFile("file", "a.bin", null, bytes))
				.header(HttpHeaders.AUTHORIZATION, user.bearer()));
	}

	private long storedCount() throws IOException {
		Path images = STORAGE.resolve("event-images");
		if (!Files.isDirectory(images)) {
			return 0;
		}
		try (Stream<Path> files = Files.list(images)) {
			return files.count();
		}
	}

}
