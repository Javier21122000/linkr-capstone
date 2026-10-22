package com.linkr.backend.ticketmaster;

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
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Params;
import com.linkr.backend.ticketmaster.TicketmasterImportReport.Status;

/** Chi puo' lanciare l'importazione (POST /api/admin/import/ticketmaster): filtri di sicurezza veri, servizio finto. */
@WebMvcTest(TicketmasterImportController.class)
@Import({ SecurityConfig.class, ProblemDetailSecurityHandlers.class })
class TicketmasterImportSecurityTest {

	private static final String URL = "/api/admin/import/ticketmaster";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TicketmasterImportService service;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private static RequestPostProcessor asUser() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor asAdmin() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private static TicketmasterImportReport report() {
		return TicketmasterImportReport.notRun(Status.IMPORT_DISABLED, "disabled", OffsetDateTime.of(2026, 10, 22, 10, 0, 0, 0, ZoneOffset.UTC),
				new Params(30, 20));
	}

	@Test
	void withoutATokenTheServiceIsNeverReached() throws Exception {
		mockMvc.perform(post(URL))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		verifyNoInteractions(service);
	}

	@Test
	void anInvalidTokenIsRejected() throws Exception {
		when(jwtDecoder.decode(any())).thenThrow(new BadJwtException("bad"));

		mockMvc.perform(post(URL).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")).andExpect(status().isUnauthorized());

		verifyNoInteractions(service);
	}

	@Test
	void aNormalUserGets403AndNothingIsImported() throws Exception {
		mockMvc.perform(post(URL).with(asUser()))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(post(URL).param("days", "7").param("maxCandidates", "10").with(asUser())).andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	@Test
	void anAdminGetsTheReport() throws Exception {
		when(service.run(null, null)).thenReturn(report());

		mockMvc.perform(post(URL).with(asAdmin()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
				.andExpect(jsonPath("$.status").value("IMPORT_DISABLED"))
				.andExpect(jsonPath("$.dryRun").value(false))
				.andExpect(jsonPath("$.result.created").value(0))
				.andExpect(jsonPath("$.params.maxCandidates").value(20));

		verify(service).run(null, null);
	}

	@Test
	void optionalParametersAreForwarded() throws Exception {
		when(service.run(7, 10)).thenReturn(report());

		mockMvc.perform(post(URL).param("days", "7").param("maxCandidates", "10").with(asAdmin())).andExpect(status().isOk());

		verify(service).run(7, 10);
	}

	@Test
	void badParametersAreA400AndNeverReachTheService() throws Exception {
		mockMvc.perform(post(URL).param("maxCandidates", "many").with(asAdmin()))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		verify(service, never()).run(any(), any());
	}

	@Test
	void outOfRangeParametersAreA400ProblemDetail() throws Exception {
		when(service.run(null, 999)).thenThrow(new InvalidImportParameterException("maxCandidates must be between 1 and 50"));

		mockMvc.perform(post(URL).param("maxCandidates", "999").with(asAdmin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid parameters"))
				.andExpect(jsonPath("$.detail").value("maxCandidates must be between 1 and 50"));
	}

	@Test
	void anImportAlreadyRunningIsA409() throws Exception {
		when(service.run(null, null)).thenThrow(new ImportAlreadyRunningException());

		mockMvc.perform(post(URL).with(asAdmin())).andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Import already running"));
	}

	@Test
	void onlyPostIsAllowedAndTheAreaIsProtectedForEveryMethod() throws Exception {
		mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
		mockMvc.perform(put(URL).with(asUser())).andExpect(status().isForbidden());
		mockMvc.perform(delete(URL).with(asUser())).andExpect(status().isForbidden());
		mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(URL).with(asAdmin())).andExpect(status().isMethodNotAllowed());
		mockMvc.perform(delete(URL).with(asAdmin())).andExpect(status().isMethodNotAllowed());

		verifyNoInteractions(service);
	}

}
