package com.linkr.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.linkr.backend.health.HealthController;

// Origini fissate qui, cosi' il test non dipende dalla variabile d'ambiente CORS_ALLOWED_ORIGINS.
// Questi test provano solo il controller/CORS: la sicurezza e' provata in AuthIntegrationTest.
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(value = HealthController.class,
		properties = "app.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173")
@Import(CorsConfig.class)
class CorsConfigTest {

	private static final String ALLOWED = "http://localhost:5173";
	private static final String ALLOWED_IP = "http://127.0.0.1:5173";
	private static final String FORBIDDEN = "http://evil.example.com";

	@Autowired
	private MockMvc mockMvc;

	@Test
	void allowedOriginsReceiveCorsHeader() throws Exception {
		for (String origin : new String[] { ALLOWED, ALLOWED_IP }) {
			mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, origin))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
					.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
		}
	}

	@Test
	void preflightIsAcceptedForAllowedOrigin() throws Exception {
		mockMvc.perform(options("/api/health")
						.header(HttpHeaders.ORIGIN, ALLOWED)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
				.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
				.andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
	}

	@Test
	void unknownOriginGetsNoCorsHeader() throws Exception {
		mockMvc.perform(get("/api/health").header(HttpHeaders.ORIGIN, FORBIDDEN))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));

		mockMvc.perform(options("/api/health")
						.header(HttpHeaders.ORIGIN, FORBIDDEN)
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

}
