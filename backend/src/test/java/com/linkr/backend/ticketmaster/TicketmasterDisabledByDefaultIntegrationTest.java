package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

import jakarta.persistence.EntityManager;

/**
 * Configurazione di test "cosi' com'e'": l'integrazione e' SPENTA (application-test.properties la blocca anche se l'ambiente dice
 * altro). Il client vero esiste ma e' sorvegliato da una spia: non deve ricevere nessuna chiamata, e il database non deve essere toccato.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class TicketmasterDisabledByDefaultIntegrationTest {

	private static final String URL = "/api/admin/import/ticketmaster/dry-run";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private TicketmasterProperties properties;

	@MockitoSpyBean
	private TicketmasterApi api;

	@Test
	void theTestProfileKeepsTheIntegrationOffWithoutAKey() {
		assertThat(properties.enabled()).isFalse();
		assertThat(properties.hasApiKey()).isFalse();
		assertThat(properties.toString()).contains("enabled=false").contains("apiKey=<not set>");
	}

	@Test
	void anAdminGetsAControlledDisabledAnswerAndNothingHappens() throws Exception {
		TestUser admin = new TestUsers(mockMvc, jdbc, entityManager).admin("tm_off_admin");
		Map<String, String> contentsBefore = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesBefore = DatabaseSnapshots.writesInThisTransaction(jdbc);

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DISABLED"))
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")))
				.andExpect(jsonPath("$.counts.received").value(0))
				.andExpect(jsonPath("$.api.calls").value(0))
				.andExpect(jsonPath("$.api.stoppedBy").value("NOT_RUN"))
				.andExpect(jsonPath("$.errors").isEmpty());

		verifyNoInteractions(api);
		assertThat(DatabaseSnapshots.contents(jdbc)).isEqualTo(contentsBefore);
		assertThat(DatabaseSnapshots.writesInThisTransaction(jdbc)).isEqualTo(writesBefore);
	}

	@Test
	void theImportEndpointIsDisabledToo() throws Exception {
		TestUser admin = new TestUsers(mockMvc, jdbc, entityManager).admin("tm_off_import");
		Map<String, String> contentsBefore = DatabaseSnapshots.contents(jdbc);
		Map<String, String> writesBefore = DatabaseSnapshots.writesInThisTransaction(jdbc);

		mockMvc.perform(post("/api/admin/import/ticketmaster").header(HttpHeaders.AUTHORIZATION, admin.bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DISABLED"))
				.andExpect(jsonPath("$.dryRun").value(false))
				.andExpect(jsonPath("$.result.created").value(0))
				.andExpect(jsonPath("$.items").isEmpty());

		verifyNoInteractions(api);
		assertThat(DatabaseSnapshots.contents(jdbc)).isEqualTo(contentsBefore);
		assertThat(DatabaseSnapshots.writesInThisTransaction(jdbc)).isEqualTo(writesBefore);
	}

	@Test
	void aNormalUserAndAnonymousCallersAreStillRefusedWhenDisabled() throws Exception {
		TestUser normal = new TestUsers(mockMvc, jdbc, entityManager).user("tm_off_user");

		mockMvc.perform(post(URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, normal.bearer())).andExpect(status().isForbidden());

		verifyNoInteractions(api);
	}

}
