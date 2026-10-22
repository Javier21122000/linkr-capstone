package com.linkr.backend.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.IntegrationTest;
import com.linkr.backend.event.seed.DemoEventSeeder;
import com.linkr.backend.demo.CommunityDemoSeeder.Report;
import com.linkr.backend.support.TestUsers;
import com.linkr.backend.support.TestUsers.TestUser;

/** Seed della community demo su linkr_test_db (rollback a fine test). Il seeder si crea a mano: nel contesto di test e' spento. */
@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class CommunityDemoSeederTest {

	@Autowired
	private JdbcClient jdbc;
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private Clock clock;
	@Autowired
	private ApplicationContext context;

	private CommunityDemoSeeder seeder;

	@BeforeEach
	void setUp() {
		seeder = new CommunityDemoSeeder(jdbc, clock);
	}

	@Test
	void isOffByDefault() {
		assertThat(context.getBeanNamesForType(CommunityDemoSeeder.class)).isEmpty();
		assertThat(context.getBeanNamesForType(CommunityDemoRunner.class)).isEmpty();
	}

	@Test
	void createsTheExpectedCommunity() {
		Report report = seeder.seed();
		assertThat(report.aborted()).isFalse();
		assertThat(report.users()).isEqualTo(15);
		assertThat(report.events()).isEqualTo(36);
		assertThat(report.links()).isEqualTo(8);
		assertThat(report.messages()).isEqualTo(8);
		assertThat(report.participations()).isBetween(36 * 4, 36 * 8);

		assertThat(count("SELECT count(*) FROM app_user WHERE email LIKE '%@demo.linkr.invalid'")).isEqualTo(15);
		assertThat(count("SELECT count(*) FROM app_user WHERE email LIKE '%@demo.linkr.invalid' AND (role <> 'USER' OR username NOT LIKE 'demo\\_%' OR avatar_url NOT LIKE '/demo/avatars/%.webp')")).isZero();
		assertThat(count("SELECT count(DISTINCT user_id) FROM user_interest ui JOIN app_user u ON u.id = ui.user_id WHERE u.email LIKE '%@demo.linkr.invalid'")).isEqualTo(15);
		assertThat(count("SELECT count(*) FROM user_interest ui JOIN app_user u ON u.id = ui.user_id WHERE u.email LIKE '%@demo.linkr.invalid'")).isEqualTo(60);

		// eventi: 2-3 per personaggio, tra 1 e 15 giorni da adesso, demo, mai Ticketmaster, creati da un account demo
		assertThat(count("SELECT count(*) FROM event e JOIN app_user u ON u.id = e.created_by WHERE e.demo AND e.event_type = 'OFFICIAL' AND u.email LIKE '%@demo.linkr.invalid'")).isEqualTo(36);
		assertThat(count("SELECT count(*) FROM (SELECT created_by FROM event WHERE demo AND created_by IS NOT NULL GROUP BY created_by HAVING count(*) NOT BETWEEN 2 AND 3) t")).isZero();
		assertThat(count("SELECT count(*) FROM event WHERE demo AND created_by IS NOT NULL AND (starts_at < now() + interval '1 hour' OR starts_at > now() + interval '15 days')")).isZero();
		assertThat(count("SELECT count(*) FROM event_source es JOIN event e ON e.id = es.event_id WHERE e.created_by IS NOT NULL AND e.demo")).isZero();
		assertThat(count("SELECT count(*) FROM event WHERE demo AND created_by IS NOT NULL AND description NOT LIKE '%non è un evento reale.'")).isZero();
		assertThat(count("SELECT count(DISTINCT category) FROM event WHERE demo AND created_by IS NOT NULL")).isEqualTo(7);

		// partecipazioni: solo account demo, entrambi gli stati, ogni evento con l'organizzatore e almeno un altro
		assertThat(count("SELECT count(*) FROM event_participation p JOIN event e ON e.id = p.event_id WHERE e.created_by IS NOT NULL AND e.demo AND p.user_id NOT IN (SELECT id FROM app_user WHERE email LIKE '%@demo.linkr.invalid')")).isZero();
		assertThat(count("SELECT count(DISTINCT p.status) FROM event_participation p JOIN event e ON e.id = p.event_id WHERE e.created_by IS NOT NULL AND e.demo")).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM (SELECT p.event_id FROM event_participation p JOIN event e ON e.id = p.event_id WHERE e.created_by IS NOT NULL AND e.demo GROUP BY p.event_id HAVING count(*) < 2) t")).isZero();

		// Link accettati e chat solo tra account demo
		assertThat(count("SELECT count(*) FROM link_request WHERE status = 'ACCEPTED' AND requester_id IN (SELECT id FROM app_user WHERE email LIKE '%@demo.linkr.invalid') AND recipient_id IN (SELECT id FROM app_user WHERE email LIKE '%@demo.linkr.invalid')")).isEqualTo(8);
	}

	@Test
	void secondRunChangesNothing() {
		seeder.seed();
		List<Long> before = totals();
		Report again = seeder.seed();
		assertThat(again).isEqualTo(new Report(false, null, 0, 0, 0, 0, 0));
		assertThat(totals()).isEqualTo(before);
	}

	@Test
	void realDataIsNeverTouched() throws Exception {
		TestUser real = new TestUsers(mockMvc, jdbc, null).user("real_sd");
		UUID realEvent = UUID.randomUUID();
		UUID tmEvent = UUID.randomUUID();
		for (UUID id : List.of(realEvent, tmEvent)) {
			jdbc.sql("""
					INSERT INTO event (id, title, description, category, starts_at, venue_name, address, neighbourhood, latitude, longitude)
					VALUES (:id, 'Evento reale', 'x', 'MUSIC', now() - interval '5 days', 'v', 'a', 'n', 45.46, 9.19)
					""").param("id", id).update();
		}
		jdbc.sql("INSERT INTO event_source (event_id, source, external_id) VALUES (:e, 'TICKETMASTER', 'tm-demo-isolation')").param("e", tmEvent).update();
		jdbc.sql("INSERT INTO event_participation (user_id, event_id, status) VALUES (:u, :e, 'GOING')").param("u", real.id()).param("e", realEvent).update();
		String snapshot = realSnapshot();

		seeder.seed();
		seeder.seed();

		assertThat(realSnapshot()).isEqualTo(snapshot);
		assertThat(count("SELECT count(*) FROM event_participation WHERE user_id = '" + real.id() + "'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event WHERE id = '" + tmEvent + "' AND NOT demo")).isEqualTo(1);
	}

	@Test
	void stopsWithoutWritingWhenARealAccountUsesADemoUsername() throws Exception {
		new TestUsers(mockMvc, jdbc, null).user("demo_giulia");
		long users = count("SELECT count(*) FROM app_user");
		long events = count("SELECT count(*) FROM event");
		Report report = seeder.seed();
		assertThat(report.aborted()).isTrue();
		assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(users);
		assertThat(count("SELECT count(*) FROM event")).isEqualTo(events);
	}

	@Test
	void demoAccountsCannotLogIn() throws Exception {
		seeder.seed();
		for (String password : List.of("password123", "", "!demo-account-no-login")) {
			mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
					.content("{\"email\":\"demo_giulia@demo.linkr.invalid\",\"password\":\"" + password + "\"}"))
					.andExpect(status().is4xxClientError());
		}
	}

	@Test
	void rescheduleMovesAnEndedDemoEventAndKeepsItsParticipations() {
		seeder.seed();
		UUID id = DemoCatalog.EVENTS.get(0).id();
		long participants = count("SELECT count(*) FROM event_participation WHERE event_id = '" + id + "'");
		jdbc.sql("UPDATE event SET starts_at = now() - interval '2 days', ends_at = now() - interval '2 days' + interval '1 hour' WHERE id = :id").param("id", id).update();
		Report report = seeder.seed();
		assertThat(report.events()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event WHERE id = '" + id + "' AND starts_at > now()")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM event_participation WHERE event_id = '" + id + "'")).isEqualTo(participants);
	}

	@Test
	void everyDemoEventAndAccountHasAnExistingLocalIllustration() {
		seeder.seed();
		assertThat(count("SELECT count(*) FROM event WHERE demo AND created_by IS NOT NULL AND (image_url NOT LIKE '/demo/covers/%.svg' OR image_alt IS NULL OR image_credit IS NULL)")).isZero();
		List<String> urls = jdbc.sql("SELECT image_url FROM event WHERE demo AND created_by IS NOT NULL UNION SELECT avatar_url FROM app_user WHERE email LIKE '%@demo.linkr.invalid'")
				.query(String.class).list();
		assertThat(urls).isNotEmpty();
		for (String url : urls) {
			assertThat(Path.of("../frontend/public" + url)).as(url).isRegularFile();
		}
		for (String theme : DemoArt.themes()) {
			assertThat(Path.of("../frontend/public" + DemoArt.coverUrl(theme))).as(theme).isRegularFile();
		}
	}

	@Test
	void legacyDemoRowsWithoutIllustrationsAreCompletedWithoutDuplicates() {
		seeder.seed();
		jdbc.sql("UPDATE event SET image_url = NULL, image_alt = NULL, image_credit = NULL WHERE id = :id").param("id", DemoCatalog.EVENTS.get(0).id()).update();
		jdbc.sql("UPDATE app_user SET avatar_url = NULL WHERE id = :id").param("id", DemoCatalog.PERSONAS.get(0).id()).update();
		List<Long> before = totals();
		Report report = seeder.seed();
		assertThat(report.users()).isEqualTo(1);
		assertThat(report.events()).isEqualTo(1);
		assertThat(report.participations()).isZero();
		assertThat(count("SELECT count(*) FROM event WHERE id = '" + DemoCatalog.EVENTS.get(0).id() + "' AND image_url LIKE '/demo/covers/%'")).isEqualTo(1);
		assertThat(totals().subList(0, 6)).isEqualTo(before.subList(0, 6));
	}

	@Test
	void theTwelveOriginalDemoEventsGetLocalCoversAndRealEventsKeepTheirImages() {
		UUID real = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO event (id, title, description, category, starts_at, venue_name, address, neighbourhood, latitude, longitude, image_url, image_alt)
				VALUES (:id, 'Evento reale', 'x', 'MUSIC', now() + interval '3 days', 'v', 'a', 'n', 45.46, 9.19, 'https://example.com/real.jpg', 'reale')
				""").param("id", real).update();
		DemoEventSeeder legacy = new DemoEventSeeder(jdbc, clock);
		legacy.run(null);
		jdbc.sql("UPDATE event SET image_url = 'https://images.unsplash.com/x.jpg' WHERE id = '018f0000-0000-7000-8000-000000000001'").update();
		legacy.run(null);
		assertThat(count("SELECT count(*) FROM event WHERE demo AND created_by IS NULL AND id::text LIKE '018f0000-%' AND image_url LIKE '/demo/covers/%.svg' AND image_alt IS NOT NULL")).isEqualTo(12);
		assertThat(count("SELECT count(*) FROM event WHERE id = '" + real + "' AND image_url = 'https://example.com/real.jpg' AND NOT demo")).isEqualTo(1);
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	private List<Long> totals() {
		return List.of(count("SELECT count(*) FROM app_user"), count("SELECT count(*) FROM event"),
				count("SELECT count(*) FROM event_participation"), count("SELECT count(*) FROM user_interest"),
				count("SELECT count(*) FROM link_request"), count("SELECT count(*) FROM chat_message"),
				count("SELECT count(*) FROM app_user WHERE updated_at > now() - interval '0 seconds'"));
	}

	/** Contenuto completo delle righe NON demo (utenti, eventi, partecipazioni, origini): deve restare identico. */
	private String realSnapshot() {
		Map<String, String> parts = Map.of(
				"users", "SELECT coalesce(string_agg(t::text, '|' ORDER BY id), '') FROM app_user t WHERE email NOT LIKE '%@demo.linkr.invalid'",
				"events", "SELECT coalesce(string_agg(t::text, '|' ORDER BY id), '') FROM event t WHERE created_by IS NULL OR created_by NOT IN (SELECT id FROM app_user WHERE email LIKE '%@demo.linkr.invalid')",
				"parts", "SELECT coalesce(string_agg(t::text, '|' ORDER BY id), '') FROM event_participation t WHERE user_id NOT IN (SELECT id FROM app_user WHERE email LIKE '%@demo.linkr.invalid')",
				"sources", "SELECT coalesce(string_agg(t::text, '|' ORDER BY id), '') FROM event_source t");
		return parts.entrySet().stream().sorted(Map.Entry.comparingByKey())
				.map(e -> e.getKey() + "=" + jdbc.sql(e.getValue()).query(String.class).single()).reduce("", (a, b) -> a + "\n" + b);
	}

}
