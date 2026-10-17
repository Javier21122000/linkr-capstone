package com.linkr.backend.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.linkr.backend.IntegrationTest;

/**
 * Partecipazioni agli eventi con database di test (linkr_test_db).
 * Ogni test e' @Transactional: utenti, eventi e partecipazioni creati qui vengono annullati dal rollback finale.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class ParticipationIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	// ---------- PUT ----------

	@Test
	void putInterestedCreatesParticipationWithExplicitVisibility() throws Exception {
		String token = register("anna");
		UUID event = createEvent("PUBLISHED", 2);

		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("INTERESTED", false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(event.toString()))
				.andExpect(jsonPath("$.status").value("INTERESTED"))
				.andExpect(jsonPath("$.visibleToOthers").value(false))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.userId").doesNotExist());
		assertThat(count("anna", event)).isEqualTo(1);
	}

	@Test
	void putGoingWithVisibilityTrue() throws Exception {
		String token = register("luca");
		UUID event = createEvent("PUBLISHED", 2);

		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("GOING", true)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("GOING"))
				.andExpect(jsonPath("$.visibleToOthers").value(true));
	}

	@Test
	void statusAndVisibilityAreIndependentAndCanBeChanged() throws Exception {
		String token = register("mario");
		UUID event = createEvent("PUBLISHED", 2);

		declare(token, event, "INTERESTED", false);
		declare(token, event, "GOING", false);      // cambia solo lo stato
		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.status").value("GOING"))
				.andExpect(jsonPath("$.visibleToOthers").value(false));

		declare(token, event, "GOING", true);       // cambia solo la visibilita'
		declare(token, event, "INTERESTED", true);  // e di nuovo lo stato
		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.status").value("INTERESTED"))
				.andExpect(jsonPath("$.visibleToOthers").value(true));
		assertThat(count("mario", event)).isEqualTo(1);
	}

	@Test
	void repeatedPutIsIdempotentAndCreatesNoDuplicates() throws Exception {
		String token = register("idem");
		UUID event = createEvent("PUBLISHED", 2);

		String first = declare(token, event, "GOING", true);
		String second = declare(token, event, "GOING", true);

		assertThat(JsonPath.<String>read(second, "$.createdAt")).isEqualTo(JsonPath.<String>read(first, "$.createdAt"));
		assertThat(count("idem", event)).isEqualTo(1);
	}

	@Test
	void putIgnoresUserIdInBody() throws Exception {
		String alice = register("alice");
		register("bob");
		UUID event = createEvent("PUBLISHED", 2);

		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(alice))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + userId("bob") + "\",\"status\":\"GOING\",\"visibleToOthers\":false}"))
				.andExpect(status().isOk());

		assertThat(count("alice", event)).isEqualTo(1);
		assertThat(count("bob", event)).isZero();
	}

	// ---------- database ----------

	@Test
	void visibilityDefaultsToFalseInTheDatabase() {
		register("dbdefault");
		UUID event = createEvent("PUBLISHED", 2);

		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status) VALUES (:u, :e, 'GOING')")
				.param("u", userId("dbdefault")).param("e", event).update();

		assertThat(jdbc.sql("SELECT visible_to_others FROM event_participation WHERE user_id = :u")
				.param("u", userId("dbdefault")).query(Boolean.class).single()).isFalse();
	}

	@Test
	void databaseRejectsDuplicatesAndInvalidStatus() {
		register("constraint");
		UUID event = createEvent("PUBLISHED", 2);
		String insert = "INSERT INTO event_participation (user_id, event_id, status) VALUES (:u, :e, :s)";
		jdbc.sql(insert).param("u", userId("constraint")).param("e", event).param("s", "GOING").update();

		// UNIQUE(user_id, event_id) a livello database (e' la protezione anche contro richieste concorrenti)
		assertThatThrownBy(() -> jdbc.sql(insert).param("u", userId("constraint")).param("e", event)
				.param("s", "INTERESTED").update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsInvalidStatusValue() {
		register("badstatus");
		UUID event = createEvent("PUBLISHED", 2);

		assertThatThrownBy(() -> jdbc.sql("INSERT INTO event_participation (user_id, event_id, status) VALUES (:u, :e, 'MAYBE')")
				.param("u", userId("badstatus")).param("e", event).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---------- eventi terminati ----------

	@Test
	void putOnFutureEventIsAllowedWithAndWithoutEndsAt() throws Exception {
		String token = register("future");

		declare(token, createEventAt(2, 4L), "GOING", false);
		declare(token, createEventAt(2, null), "INTERESTED", false);
	}

	@Test
	void putOnEventInProgressWithEndsAtIsAllowed() throws Exception {
		String token = register("inprogress");

		declare(token, createEventAt(-1, 1L), "GOING", true);
	}

	@Test
	void putOnEndedEventWithEndsAtReturns409AndStoresNothing() throws Exception {
		String token = register("late1");
		UUID event = createEventAt(-5, -1L);

		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value("Event has ended"))
				.andExpect(jsonPath("$.trace").doesNotExist());
		assertThat(count("late1", event)).isZero();
	}

	@Test
	void putOnStartedEventWithoutEndsAtIsAllowedUntilThreeHoursAfterStart() throws Exception {
		String token = register("grace");

		// iniziato da 1 ora, senza endsAt: per Discover e' ancora in corso (3 ore), quindi si puo' dichiarare
		declare(token, createEventAt(-1, null), "GOING", false);
	}

	@Test
	void putOnEventWithoutEndsAtStartedMoreThanThreeHoursAgoReturns409() throws Exception {
		String token = register("late2");
		UUID event = createEventAt(-4, null); // 4 ore fa + 3 ore di durata presunta = finito da 1 ora

		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("INTERESTED", false)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Event has ended"));
		assertThat(count("late2", event)).isZero();
	}

	@Test
	void participationRuleMatchesDiscoverForEventsWithoutEndsAt() throws Exception {
		String token = register("aligned");
		UUID stillOpen = createEventAt(-2, null);   // 2 ore fa: in Discover E nelle partecipazioni
		UUID finished = createEventAt(-4, null);    // 4 ore fa: non in Discover E rifiutato nelle partecipazioni

		String discover = mockMvc.perform(get("/api/events")).andReturn().getResponse().getContentAsString();
		assertThat(discover).contains(stillOpen.toString()).doesNotContain(finished.toString());
		declare(token, stillOpen, "GOING", false);
		mockMvc.perform(put(path(finished)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
				.andExpect(status().isConflict());
	}

	@Test
	void historicalParticipationCanBeReadListedAndDeletedButNotChanged() throws Exception {
		String token = register("history");
		UUID event = createEventAt(1, 3L);
		declare(token, event, "GOING", true);
		makeEventEnded(event, true); // l'evento finisce dopo la dichiarazione

		// modificare stato o visibilita' non e' piu' possibile
		mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("INTERESTED", false)))
				.andExpect(status().isConflict());
		// la lettura dello storico e' consentita, e il valore e' rimasto quello vecchio
		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("GOING"))
				.andExpect(jsonPath("$.visibleToOthers").value(true));
		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.participations.length()").value(1));
		// la rimozione dello storico e' consentita
		mockMvc.perform(delete(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNoContent());
		assertThat(count("history", event)).isZero();
	}

	@Test
	void historicalParticipationWithoutEndsAtIsReadableAndDeletable() throws Exception {
		String token = register("history2");
		UUID event = createEventAt(1, null);
		declare(token, event, "INTERESTED", false);
		makeEventEnded(event, false);

		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("INTERESTED"));
		mockMvc.perform(delete(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNoContent());
		assertThat(count("history2", event)).isZero();
	}

	// ---------- GET ----------

	@Test
	void getReturnsExistingParticipation() throws Exception {
		String token = register("getter");
		UUID event = createEvent("PUBLISHED", 2);
		declare(token, event, "GOING", true);

		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("GOING"))
				.andExpect(jsonPath("$.visibleToOthers").value(true));
	}

	@Test
	void getReturns404ProblemDetailWhenNoParticipation() throws Exception {
		String token = register("nopart");
		UUID event = createEvent("PUBLISHED", 2);

		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Participation not found"));
	}

	// ---------- DELETE ----------

	@Test
	void deleteRemovesParticipationAndRepeatedDeleteIsHarmless() throws Exception {
		String token = register("deleter");
		UUID event = createEvent("PUBLISHED", 2);
		declare(token, event, "GOING", false);

		mockMvc.perform(delete(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNoContent());
		assertThat(count("deleter", event)).isZero();
		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNotFound());

		// secondo DELETE (e DELETE su evento sconosciuto): stesso esito, nessun errore
		mockMvc.perform(delete(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete(path(UUID.randomUUID())).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isNoContent());
	}

	// ---------- lista ----------

	@Test
	void listReturnsOwnParticipationsWithEventDataOrderedByStart() throws Exception {
		String token = register("lister");
		UUID later = createEvent("PUBLISHED", 10);
		UUID sooner = createEvent("PUBLISHED", 3);
		UUID notSaved = createEvent("PUBLISHED", 5);
		declare(token, later, "INTERESTED", false);
		declare(token, sooner, "GOING", true);

		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.participations.length()").value(2))
				.andExpect(jsonPath("$.participations[*].event.id", contains(sooner.toString(), later.toString())))
				.andExpect(jsonPath("$.participations[0].status").value("GOING"))
				.andExpect(jsonPath("$.participations[0].visibleToOthers").value(true))
				.andExpect(jsonPath("$.participations[0].event.title").value("PTEST event"))
				.andExpect(jsonPath("$.participations[0].event.startsAt").isNotEmpty())
				.andExpect(jsonPath("$.participations[0].event.description").doesNotExist())
				.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(notSaved.toString()))));
	}

	@Test
	void listIsEmptyForNewUserAndHidesNonPublishedEvents() throws Exception {
		String token = register("emptylist");
		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.participations.length()").value(0));

		UUID event = createEvent("PUBLISHED", 2);
		declare(token, event, "GOING", false);
		jdbc.sql("UPDATE event SET status = 'CANCELLED' WHERE id = :id").param("id", event).update();

		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(jsonPath("$.participations.length()").value(0));
	}

	// ---------- sicurezza ----------

	@Test
	void allParticipationEndpointsRequireAJwt() throws Exception {
		UUID event = createEvent("PUBLISHED", 2);

		mockMvc.perform(put(path(event)).contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get(path(event))).andExpect(status().isUnauthorized());
		mockMvc.perform(delete(path(event))).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/users/me/participations")).andExpect(status().isUnauthorized());
	}

	@Test
	void invalidAndExpiredJwtAreRejected() throws Exception {
		UUID event = createEvent("PUBLISHED", 2);
		register("expired");
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
				JwtClaimsSet.builder().issuer("linkr").subject(userId("expired").toString()).claim("roles", List.of("USER"))
						.issuedAt(Instant.now().minusSeconds(7200)).expiresAt(Instant.now().minusSeconds(3600)).build()))
				.getTokenValue();

		for (String token : List.of("not.a.jwt", expired)) {
			mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
							.contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
					.andExpect(status().isUnauthorized());
			mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void usersCannotSeeChangeOrDeleteEachOthersParticipation() throws Exception {
		String alice = register("alice2");
		String bob = register("bob2");
		UUID event = createEvent("PUBLISHED", 2);
		declare(alice, event, "GOING", true);

		// Bob non vede quella di Alice: per lui non esiste
		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(bob))).andExpect(status().isNotFound());
		mockMvc.perform(get("/api/users/me/participations").header(HttpHeaders.AUTHORIZATION, bearer(bob)))
				.andExpect(jsonPath("$.participations.length()").value(0));

		// il DELETE e il PUT di Bob toccano solo la riga di Bob
		mockMvc.perform(delete(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(bob))).andExpect(status().isNoContent());
		declare(bob, event, "INTERESTED", false);

		mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
				.andExpect(jsonPath("$.status").value("GOING"))
				.andExpect(jsonPath("$.visibleToOthers").value(true));
		assertThat(count("alice2", event)).isEqualTo(1);
		assertThat(count("bob2", event)).isEqualTo(1);
	}

	@Test
	void noPublicEndpointExposesParticipants() throws Exception {
		mockMvc.perform(get("/api/events/" + createEvent("PUBLISHED", 2) + "/participants"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/participations")).andExpect(status().isUnauthorized());
	}

	// ---------- validazione ----------

	@Test
	void unknownOrNonPublishedEventReturns404() throws Exception {
		String token = register("noevent");
		UUID draft = createEvent("DRAFT", 2);
		UUID cancelled = createEvent("CANCELLED", 2);

		for (UUID event : List.of(UUID.randomUUID(), draft, cancelled)) {
			mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
							.contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.title").value("Event not found"));
			mockMvc.perform(get(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.title").value("Event not found"));
		}
		assertThat(jdbc.sql("SELECT count(*) FROM event_participation").query(Long.class).single()).isZero();
	}

	@Test
	void invalidPayloadsReturn400AndStoreNothing() throws Exception {
		String token = register("badbody");
		UUID event = createEvent("PUBLISHED", 2);

		for (String payload : List.of(
				"{\"status\":\"MAYBE\",\"visibleToOthers\":false}",   // status non valido
				"{\"status\":\"going\",\"visibleToOthers\":false}",   // case sbagliato
				"{\"visibleToOthers\":false}",                        // status mancante
				"{\"status\":null,\"visibleToOthers\":false}",
				"{\"status\":\"GOING\"}",                             // visibleToOthers mancante: niente default implicito
				"{\"status\":\"GOING\",\"visibleToOthers\":null}",
				"{\"status\":\"GOING\",\"visibleToOthers\":\"maybe\"}",
				"{}", "{not json", "")) {
			mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
							.contentType(MediaType.APPLICATION_JSON).content(payload))
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(count("badbody", event)).isZero();
	}

	@Test
	void malformedEventIdReturns400() throws Exception {
		String token = register("badid");
		mockMvc.perform(put("/api/events/not-a-uuid/participation").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body("GOING", false)))
				.andExpect(status().isBadRequest());
	}

	// ---------- regressione ----------

	@Test
	void publicEndpointsStillWorkWithoutJwt() throws Exception {
		UUID event = createEvent("PUBLISHED", 2);
		mockMvc.perform(get("/api/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events")).andExpect(status().isOk());
		mockMvc.perform(get("/api/events/" + event)).andExpect(status().isOk());
		mockMvc.perform(get("/api/interests")).andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"nobody@example.com\",\"password\":\"password123\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void deletingAnEventOrUserCascadesToParticipations() throws Exception {
		String token = register("cascade");
		UUID event = createEvent("PUBLISHED", 2);
		declare(token, event, "GOING", false);

		jdbc.sql("DELETE FROM event WHERE id = :id").param("id", event).update(); // annullato dal rollback
		assertThat(jdbc.sql("SELECT count(*) FROM event_participation").query(Long.class).single()).isZero();
	}

	// ---------- helper ----------

	private String path(UUID event) {
		return "/api/events/" + event + "/participation";
	}

	private String body(String status, boolean visible) {
		return "{\"status\":\"" + status + "\",\"visibleToOthers\":" + visible + "}";
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}

	/** PUT che deve riuscire; restituisce il corpo della risposta. */
	private String declare(String token, UUID event, String status, boolean visible) throws Exception {
		return mockMvc.perform(put(path(event)).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(body(status, visible)))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	/** Registra l'utente (email = username@example.com) e restituisce il suo token. */
	private String register(String username) {
		try {
			return JsonPath.read(mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
							.content("{\"username\":\"" + username + "\",\"email\":\"" + username
									+ "@example.com\",\"password\":\"password123\",\"displayName\":\"Test\"}"))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString(), "$.accessToken");
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private UUID userId(String username) {
		return jdbc.sql("SELECT id FROM app_user WHERE username = :u").param("u", username).query(UUID.class).single();
	}

	private long count(String username, UUID event) {
		return jdbc.sql("SELECT count(*) FROM event_participation WHERE user_id = :u AND event_id = :e")
				.param("u", userId(username)).param("e", event).query(Long.class).single();
	}

	/** Crea un evento di prova (annullato dal rollback) che inizia tra "days" giorni e dura 2 ore. */
	private UUID createEvent(String status, int days) {
		Instant start = Instant.now().plus(days, ChronoUnit.DAYS);
		return insertEvent(status, start, start.plus(2, ChronoUnit.HOURS));
	}

	/** Evento PUBLISHED con inizio e fine (anche nulla) relativi a ora, in ore (negativo = nel passato). */
	private UUID createEventAt(long startHoursFromNow, Long endHoursFromNow) {
		Instant now = Instant.now();
		return insertEvent("PUBLISHED", now.plus(startHoursFromNow, ChronoUnit.HOURS),
				endHoursFromNow == null ? null : now.plus(endHoursFromNow, ChronoUnit.HOURS));
	}

	private UUID insertEvent(String status, Instant start, Instant end) {
		return jdbc.sql("""
				INSERT INTO event (title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, demo)
				VALUES ('PTEST event', 'Created by a test', 'MUSIC', :status, :start, :end, 'Test venue', 'Test address',
				        'Test', 45.46, 9.19, true) RETURNING id
				""")
				.param("status", status)
				.param("start", java.sql.Timestamp.from(start))
				.param("end", end == null ? null : java.sql.Timestamp.from(end), Types.TIMESTAMP)
				.query(UUID.class).single();
	}

	/** Sposta l'evento nel passato (gia' terminato) dopo che l'utente aveva dichiarato la partecipazione. */
	private void makeEventEnded(UUID event, boolean withEnd) {
		Instant now = Instant.now();
		jdbc.sql("UPDATE event SET starts_at = :start, ends_at = :end WHERE id = :id")
				.param("start", java.sql.Timestamp.from(now.minus(5, ChronoUnit.HOURS)))
				.param("end", withEnd ? java.sql.Timestamp.from(now.minus(3, ChronoUnit.HOURS)) : null, Types.TIMESTAMP)
				.param("id", event).update();
	}

}
