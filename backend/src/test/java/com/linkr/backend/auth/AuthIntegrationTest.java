package com.linkr.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;
import com.linkr.backend.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Registrazione, login e protezione degli endpoint, con database di test (linkr_test_db).
 * Ogni test e' @Transactional: a fine test gli utenti creati vengono annullati (rollback).
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

	private static final String ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private UserRepository userRepository;

	// ---------- registrazione ----------

	@Test
	void registerCreatesUserAndReturnsTokenWithoutPasswordHash() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson("mario.rossi", "Mario@Example.com", "password123", null)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").isNumber())
				.andExpect(jsonPath("$.user.username").value("mario.rossi"))
				.andExpect(jsonPath("$.user.email").value("mario@example.com"))
				.andExpect(jsonPath("$.user.role").value("USER"))
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("assword"))));

		String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE username = 'mario.rossi'")
				.query(String.class).single();
		assertThat(hash).startsWith("$2").isNotEqualTo("password123"); // BCrypt, mai in chiaro
	}

	@Test
	void registerIgnoresRoleSentByTheClient() throws Exception {
		String token = tokenFrom(mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson("hacker", "hacker@example.com", "password123", "ADMIN")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.role").value("USER")));

		assertThat(jdbc.sql("SELECT role FROM app_user WHERE username = 'hacker'").query(String.class).single())
				.isEqualTo("USER");
		// ...e con quel token non si entra nelle rotte admin
		mockMvc.perform(get("/api/admin/anything").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void duplicateEmailReturns409EvenWithDifferentCase() throws Exception {
		register("first", "dup@example.com");

		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson("second", "DUP@example.com", "password123", null)))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.field").value("email"));
	}

	@Test
	void duplicateUsernameReturns409EvenWithDifferentCase() throws Exception {
		register("Taken", "one@example.com");

		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson("taken", "two@example.com", "password123", null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.field").value("username"));
	}

	@Test
	void invalidRegistrationReturns400WithFieldErrorsAndNoPasswordEcho() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson("ab", "not-an-email", "short", null)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.username").exists())
				.andExpect(jsonPath("$.errors.email").exists())
				.andExpect(jsonPath("$.errors.password").exists())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("short"))));
	}

	@Test
	void malformedJsonReturns400() throws Exception {
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{not json"))
				.andExpect(status().isBadRequest());
	}

	// ---------- login ----------

	@Test
	void loginWithValidCredentialsReturnsToken() throws Exception {
		register("luca", "luca@example.com");

		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"LUCA@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.user.username").value("luca"));
	}

	@Test
	void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
		register("anna", "anna@example.com");

		String wrongPassword = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"anna@example.com\",\"password\":\"wrong-password\"}"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
		String unknownEmail = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();

		// stessa risposta (a parte "instance" identico qui): non si scopre quali email esistono
		assertThat(JsonPath.<String>read(wrongPassword, "$.detail")).isEqualTo(JsonPath.<String>read(unknownEmail, "$.detail"));
	}

	// ---------- /api/users/me ----------

	@Test
	void meReturnsTheAuthenticatedUserIdentityFromTheJwt() throws Exception {
		String token = tokenFrom(register("giulia", "giulia@example.com"));
		register("other", "other@example.com");

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("giulia"))
				.andExpect(jsonPath("$.email").value("giulia@example.com"))
				.andExpect(jsonPath("$.role").value("USER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void meWithoutTokenReturns401ProblemDetail() throws Exception {
		mockMvc.perform(get("/api/users/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void meWithGarbageTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void meWithTokenSignedByAnotherKeyReturns401() throws Exception {
		String forged = encode(foreignEncoder(), "00000000-0000-0000-0000-000000000001", List.of("ADMIN"),
				Instant.now(), Instant.now().plusSeconds(3600));

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void meWithExpiredTokenReturns401() throws Exception {
		String userId = userIdOf(register("scaduto", "scaduto@example.com"));
		// firmato con la chiave giusta, ma scaduto da un'ora
		String expired = encode(jwtEncoder, userId, List.of("USER"),
				Instant.now().minusSeconds(7200), Instant.now().minusSeconds(3600));

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void validTokenOfDeletedUserReturns401() throws Exception {
		String token = tokenFrom(register("ghost", "ghost@example.com"));
		// Tramite repository (e non SQL) cosi' anche la cache JPA della transazione lo dimentica; il rollback annulla tutto.
		userRepository.deleteAll(userRepository.findByEmail("ghost@example.com").stream().toList());
		userRepository.flush();

		mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	// ---------- accessi pubblici ----------

	@Test
	void eventAndHealthEndpointsStayPublic() throws Exception {
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/00000000-0000-0000-0000-000000000000")).andExpect(status().isNotFound());
	}

	@Test
	void otherRoutesRequireAuthentication() throws Exception {
		mockMvc.perform(get("/api/anything-else")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/events")).andExpect(status().isUnauthorized());
	}

	// ---------- CORS ----------

	@Test
	void preflightIsAllowedForTheFrontendOriginOnPublicAndProtectedRoutes() throws Exception {
		for (String path : List.of("/api/auth/login", "/api/auth/register", "/api/users/me", "/api/events")) {
			mockMvc.perform(options(path)
							.header(HttpHeaders.ORIGIN, ORIGIN)
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, path.startsWith("/api/auth") ? "POST" : "GET")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
							org.hamcrest.Matchers.containsStringIgnoringCase("authorization")));
		}
	}

	@Test
	void preflightFromUnknownOriginIsRejectedWithoutCorsHeaders() throws Exception {
		mockMvc.perform(options("/api/users/me")
						.header(HttpHeaders.ORIGIN, "http://evil.example.com")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void corsHeaderIsPresentOnTheLoginResponse() throws Exception {
		register("cors", "cors@example.com");

		mockMvc.perform(post("/api/auth/login").header(HttpHeaders.ORIGIN, ORIGIN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"cors@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ORIGIN));
	}

	// ---------- helper ----------

	private ResultActions register(String username, String email) throws Exception {
		return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(registerJson(username, email, "password123", null)))
				.andExpect(status().isCreated());
	}

	private String tokenFrom(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.accessToken");
	}

	private String userIdOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.user.id");
	}

	private String registerJson(String username, String email, String password, String role) {
		return "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + password
				+ "\",\"displayName\":\"Test User\"" + (role == null ? "" : ",\"role\":\"" + role + "\"") + "}";
	}

	private JwtEncoder foreignEncoder() {
		byte[] otherKey = "a-completely-different-secret-key-0123456789".getBytes(StandardCharsets.UTF_8);
		return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(otherKey, "HmacSHA256")));
	}

	private String encode(JwtEncoder encoder, String subject, List<String> roles, Instant issuedAt, Instant expiresAt) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("linkr").subject(subject)
				.issuedAt(issuedAt).expiresAt(expiresAt).claim("roles", roles).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

}
