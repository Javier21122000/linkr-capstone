package com.linkr.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

/**
 * Crea utenti VERI tramite l'API di registrazione (password cifrata, JWT firmato dal backend), per i test di integrazione.
 * I dati vivono nella transazione del test e vengono annullati dal rollback.
 */
public class TestUsers {

	public record TestUser(String username, UUID id, String token) {

		public String bearer() {
			return "Bearer " + token;
		}

	}

	private final MockMvc mockMvc;
	private final JdbcClient jdbc;
	private final EntityManager entityManager;

	public TestUsers(MockMvc mockMvc, JdbcClient jdbc, EntityManager entityManager) {
		this.mockMvc = mockMvc;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
	}

	public TestUser user(String username) {
		return user(username, username);
	}

	public TestUser user(String username, String displayName) {
		try {
			String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"" + username + "\",\"email\":\"" + username
									+ "@example.com\",\"password\":\"password123\",\"displayName\":\"" + displayName + "\"}"))
					.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			return new TestUser(username, UUID.fromString(JsonPath.read(body, "$.user.id")), JsonPath.read(body, "$.accessToken"));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Utente ADMIN: il ruolo non si sceglie in registrazione, quindi lo si assegna nel database e poi si rifa' il login. */
	public TestUser admin(String username) {
		TestUser registered = user(username);
		setRole(registered.id(), "ADMIN");
		return login(username);
	}

	/**
	 * Cambia il ruolo direttamente nel database. Il test e il backend condividono la stessa transazione e la stessa cache
	 * di Hibernate: senza svuotarla il backend continuerebbe a vedere il ruolo vecchio, cosa che in produzione non accade.
	 */
	public void setRole(UUID userId, String role) {
		entityManager.flush();
		jdbc.sql("UPDATE app_user SET role = :role WHERE id = :id").param("role", role).param("id", userId).update();
		entityManager.clear();
	}

	public TestUser login(String username) {
		try {
			String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"" + username + "@example.com\",\"password\":\"password123\"}"))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			return new TestUser(username, UUID.fromString(JsonPath.read(body, "$.user.id")), JsonPath.read(body, "$.accessToken"));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
