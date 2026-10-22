package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.NOW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.linkr.backend.security.ProblemDetailSecurityHandlers;
import com.linkr.backend.security.SecurityConfig;

/**
 * Chi puo' chiamare il dry-run. A differenza di EventControllerTest, qui i filtri di sicurezza sono ATTIVI (la vera SecurityConfig):
 * il servizio e' finto, quindi un test che fallisse "200" al posto di 401/403 vorrebbe dire che il servizio e' stato raggiunto.
 */
@WebMvcTest(TicketmasterAdminController.class)
@Import({ SecurityConfig.class, ProblemDetailSecurityHandlers.class })
class AdminTicketmasterSecurityTest {

	private static final String URL = "/api/admin/import/ticketmaster/dry-run";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TicketmasterDryRunService service;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private static RequestPostProcessor asUser() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor asAdmin() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private static TicketmasterDryRunReport report() {
		return TicketmasterDryRunReport.notRun(TicketmasterDryRunReport.Status.DISABLED, "disabled",
				OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), 200, 50, 8, 15);
	}

	@Test
	void withoutATokenTheServiceIsNeverReached() throws Exception {
		mockMvc.perform(post(URL))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));

		verifyNoInteractions(service);
	}

	@Test
	void anInvalidTokenIsRejected() throws Exception {
		when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("bad"));

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(service);
	}

	@Test
	void aNormalUserGets403AndTheServiceIsNeverReached() throws Exception {
		mockMvc.perform(post(URL).with(asUser()))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(403));
		mockMvc.perform(post(URL).param("days", "7").param("maxCandidates", "5").with(asUser()))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	@Test
	void anAdminGetsTheReport() throws Exception {
		when(service.run(null, null)).thenReturn(report());

		mockMvc.perform(post(URL).with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.status").value("DISABLED"))
				.andExpect(jsonPath("$.dryRun").value(true))
				.andExpect(jsonPath("$.counts.received").value(0))
				.andExpect(jsonPath("$.api.stoppedBy").value("NOT_RUN"));

		verify(service).run(null, null);
	}

	@Test
	void optionalParametersAreForwardedToTheService() throws Exception {
		when(service.run(7, 50)).thenReturn(report());

		mockMvc.perform(post(URL).param("days", "7").param("maxCandidates", "50").with(asAdmin()))
				.andExpect(status().isOk());

		verify(service).run(7, 50);
	}

	@Test
	void parametersThatAreNotNumbersAreA400AndNeverReachTheService() throws Exception {
		mockMvc.perform(post(URL).param("days", "abc").with(asAdmin()))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		verify(service, never()).run(any(), any());
	}

	@Test
	void outOfRangeParametersAreA400ProblemDetail() throws Exception {
		when(service.run(999, null)).thenThrow(new InvalidImportParameterException("days must be between 1 and 30"));

		mockMvc.perform(post(URL).param("days", "999").with(asAdmin()))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid parameters"))
				.andExpect(jsonPath("$.detail").value("days must be between 1 and 30"));
	}

	@Test
	void aRunAlreadyInProgressIsA409() throws Exception {
		when(service.run(null, null)).thenThrow(new ImportAlreadyRunningException());

		mockMvc.perform(post(URL).with(asAdmin()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Import already running"));
	}

	@Test
	void anUnexpectedFailureNeverLeaksDetails() throws Exception {
		when(service.run(null, null)).thenThrow(new IllegalStateException("secret internal detail with apikey=abc"));

		mockMvc.perform(post(URL).with(asAdmin()))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
	}

	@Test
	void otherMethodsAndPathsUnderTheAdminAreaAreAlsoProtected() throws Exception {
		for (RequestPostProcessor user : new RequestPostProcessor[] { asUser() }) {
			mockMvc.perform(get(URL).with(user)).andExpect(status().isForbidden());
			mockMvc.perform(put(URL).with(user)).andExpect(status().isForbidden());
			mockMvc.perform(delete(URL).with(user)).andExpect(status().isForbidden());
			mockMvc.perform(post("/api/admin/import/ticketmaster/anything").with(user)).andExpect(status().isForbidden());
			mockMvc.perform(post("/api/admin/import/ticketmaster/dry-run/").with(user)).andExpect(status().isForbidden());
		}
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());

		verifyNoInteractions(service);
	}

	@Test
	void anAdminCannotUseTheWrongMethod() throws Exception {
		mockMvc.perform(get(URL).with(asAdmin())).andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(service);
	}

}
