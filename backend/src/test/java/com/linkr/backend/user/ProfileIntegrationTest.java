package com.linkr.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
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

/** Modifica del profilo e profilo pubblico, con linkr_test_db (rollback a fine test) e cartella upload temporanea. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class ProfileIntegrationTest {

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
	private TestUser bruno;

	@BeforeEach
	void setUp() {
		TestUsers users = new TestUsers(mockMvc, jdbc, null);
		anna = users.user("pf_anna");
		bruno = users.user("pf_bruno");
	}

	@Test
	void updatesNameBioAndAvatarOfTheCallerOnly() throws Exception {
		String avatar = uploadPng(anna);
		saveProfile(anna, "{\"displayName\":\"  Anna   Rossi \",\"bio\":\"Amo la techno.\",\"avatarUrl\":\"" + avatar + "\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Anna Rossi"))
				.andExpect(jsonPath("$.bio").value("Amo la techno."))
				.andExpect(jsonPath("$.avatarUrl").value(avatar));
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, anna.bearer()))
				.andExpect(jsonPath("$.displayName").value("Anna Rossi"));
		assertThat(jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", bruno.id()).query(String.class).single())
				.isEqualTo("pf_bruno");
	}

	@Test
	void massAssignmentOfRoleIdEmailUsernameAndDiscoverableIsIgnored() throws Exception {
		UUID other = UUID.randomUUID();
		saveProfile(anna, "{\"displayName\":\"Anna\",\"role\":\"ADMIN\",\"id\":\"" + other + "\",\"email\":\"hack@example.com\","
				+ "\"username\":\"hacked\",\"discoverable\":true,\"passwordHash\":\"x\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.id").value(anna.id().toString()))
				.andExpect(jsonPath("$.email").value("pf_anna@example.com"))
				.andExpect(jsonPath("$.username").value("pf_anna"))
				.andExpect(jsonPath("$.discoverable").value(false));
	}

	@Test
	void rejectsInvalidDataWithoutWriting() throws Exception {
		saveProfile(anna, "{\"displayName\":\"A\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.displayName").exists());
		saveProfile(anna, "{\"displayName\":\"Anna\",\"bio\":\"" + "x".repeat(301) + "\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.bio").exists());
		saveProfile(anna, "{\"bio\":\"ciao\"}").andExpect(status().isBadRequest());
		saveProfile(anna, "{\"displayName\":\"Anna\",\"avatarUrl\":\"https://evil.example/x.png\"}").andExpect(status().isBadRequest());
		saveProfile(anna, "{\"displayName\":\"Anna\",\"avatarUrl\":\"/api/uploads/event-images/" + UUID.randomUUID() + ".png\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.avatarUrl").exists());
		assertThat(jdbc.sql("SELECT display_name FROM app_user WHERE id = :id").param("id", anna.id()).query(String.class).single())
				.isEqualTo("pf_anna");
	}

	@Test
	void requiresAuthentication() throws Exception {
		mockMvc.perform(put("/api/users/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Anna\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/users/" + anna.id() + "/profile")).andExpect(status().isUnauthorized());
	}

	@Test
	void avatarCanBeRemovedWithNull() throws Exception {
		String avatar = uploadPng(anna);
		saveProfile(anna, "{\"displayName\":\"Anna\",\"avatarUrl\":\"" + avatar + "\"}").andExpect(status().isOk());
		saveProfile(anna, "{\"displayName\":\"Anna\",\"avatarUrl\":null}").andExpect(status().isOk()).andExpect(jsonPath("$.avatarUrl").doesNotExist());
	}

	@Test
	void anAccountWithAnExistingExternalAvatarKeepsItWhenUnchangedOnlyThroughNull() throws Exception {
		jdbc.sql("UPDATE app_user SET avatar_url = 'https://legacy.example/a.png' WHERE id = :id").param("id", anna.id()).update();
		// l'avatar esterno preesistente non e' piu' accettabile come nuovo valore, ma il profilo resta modificabile (avatar null)
		saveProfile(anna, "{\"displayName\":\"Anna B\",\"avatarUrl\":null}").andExpect(status().isOk());
	}

	@Test
	void publicProfileIsHiddenUntilARuleAllowsIt() throws Exception {
		profile(anna, bruno).andExpect(status().isNotFound());                  // nessuna regola
		profile(anna, UUID.randomUUID()).andExpect(status().isNotFound());      // inesistente: stessa risposta

		discoverable(bruno, true);
		profile(anna, bruno).andExpect(status().isNotFound());                  // reciprocita': anna non e' visibile

		discoverable(anna, true);
		saveProfile(bruno, "{\"displayName\":\"Bruno Bianchi\",\"bio\":\"Ciao\"}").andExpect(status().isOk());
		profile(anna, bruno).andExpect(status().isOk())
				.andExpect(jsonPath("$.displayName").value("Bruno Bianchi"))
				.andExpect(jsonPath("$.bio").value("Ciao"))
				.andExpect(jsonPath("$.linkStatus").value("NONE"))
				.andExpect(jsonPath("$.email").doesNotExist())
				.andExpect(jsonPath("$.role").doesNotExist())
				.andExpect(jsonPath("$.discoverable").doesNotExist());

		discoverable(bruno, false);
		profile(anna, bruno).andExpect(status().isNotFound());                  // si e' nascosto: sparisce subito
	}

	@Test
	void acceptedLinkAllowsTheProfileEvenWhenPrivate() throws Exception {
		jdbc.sql("INSERT INTO link_request (requester_id, recipient_id, status, responded_at) VALUES (:a, :b, 'ACCEPTED', now())")
				.param("a", anna.id()).param("b", bruno.id()).update();
		profile(anna, bruno).andExpect(status().isOk()).andExpect(jsonPath("$.linkStatus").value("ACCEPTED"));
		profile(bruno, anna).andExpect(status().isOk());
	}

	@Test
	void ownProfileIsAlwaysVisible() throws Exception {
		profile(anna, anna).andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(anna.id().toString()));
	}

	private ResultActions saveProfile(TestUser user, String json) throws Exception {
		return mockMvc.perform(put("/api/users/me/profile").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Passa dall'API (non da JDBC): l'entita' JPA gia' in cache nella transazione del test sovrascriverebbe un UPDATE diretto. */
	private void discoverable(TestUser user, boolean value) throws Exception {
		mockMvc.perform(put("/api/users/me/discovery").header(HttpHeaders.AUTHORIZATION, user.bearer())
				.contentType(MediaType.APPLICATION_JSON).content("{\"discoverable\":" + value + "}")).andExpect(status().isOk());
	}

	private ResultActions profile(TestUser viewer, TestUser target) throws Exception {
		return profile(viewer, target.id());
	}

	private ResultActions profile(TestUser viewer, UUID target) throws Exception {
		return mockMvc.perform(get("/api/users/" + target + "/profile").header(HttpHeaders.AUTHORIZATION, viewer.bearer()));
	}

	private String uploadPng(TestUser user) throws Exception {
		String body = mockMvc.perform(multipart("/api/uploads/event-images")
						.file(new MockMultipartFile("file", "a.png", "image/png", ImageFixtures.png()))
						.header(HttpHeaders.AUTHORIZATION, user.bearer()))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.imageUrl");
	}

}
