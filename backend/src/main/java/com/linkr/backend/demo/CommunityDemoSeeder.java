package com.linkr.backend.demo;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.demo.DemoCatalog.DemoEvent;
import com.linkr.backend.demo.DemoCatalog.DemoLink;
import com.linkr.backend.participation.ParticipationStatus;

/**
 * Community dimostrativa: 15 personaggi, 36 uscite, partecipazioni, Link accettati e qualche messaggio, tutti inventati.
 * Esiste SOLO con il profilo "dev" o "demo" E con linkr.demo.community.enabled=true (spento di default: il profilo di produzione standard non lo attiva).
 *
 * Isolamento (tutto in una transazione, mai DELETE):
 *  - gli account demo hanno id fissi, username "demo_*" ed email "@demo.linkr.invalid" (dominio riservato, non esiste): un utente reale
 *    non puo' essere confuso con loro e loro non possono accedere (password_hash non e' un hash valido). Ruolo sempre USER;
 *  - prima di scrivere si controlla che nessuna riga NON demo occupi id, username, email o id evento che servono: se succede
 *    il seed si ferma senza scrivere nulla;
 *  - gli UPDATE toccano solo righe demo riconosciute (email demo / demo=true e creatore demo): mai account reali, mai eventi Ticketmaster
 *    o di altri utenti, mai partecipazioni o Link non creati qui;
 *  - eventi con demo=true e event_type OFFICIAL (il vincolo event_community_not_demo vieta demo + COMMUNITY), creati_by = personaggio;
 *  - idempotente: id fissi + ON CONFLICT. Un evento demo gia' terminato viene spostato nel futuro (le partecipazioni restano).
 */
@Component
@Profile({"dev", "demo"})
@ConditionalOnProperty(name = "linkr.demo.community.enabled", havingValue = "true")
public class CommunityDemoSeeder {

	/** Quanto e' cambiato in questa esecuzione (0 ovunque = tutto gia' presente). */
	public record Report(boolean aborted, String reason, int users, int events, int participations, int links, int messages) {

		static Report abort(String reason) {
			return new Report(true, reason, 0, 0, 0, 0, 0);
		}

	}

	private static final Logger log = LoggerFactory.getLogger(CommunityDemoSeeder.class);
	private static final Duration MIN_LEAD_TIME = Duration.ofHours(1);

	private final JdbcClient jdbc;
	private final Clock clock;

	public CommunityDemoSeeder(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional
	public Report seed() {
		List<DemoPersona> personas = DemoCatalog.PERSONAS;
		Map<String, DemoPersona> byKey = personas.stream().collect(Collectors.toMap(DemoPersona::key, Function.identity()));

		String conflict = findConflict(personas);
		if (conflict != null) {
			log.warn("Community demo seed skipped, nothing written: {}", conflict);
			return Report.abort(conflict);
		}

		int users = 0;
		for (DemoPersona p : personas) {
			users += upsertUser(p);
			insertInterests(p);
		}
		Instant now = clock.instant();
		int events = 0;
		for (DemoEvent e : DemoCatalog.EVENTS) {
			events += upsertEvent(e, byKey.get(e.host()), now);
		}
		int participations = 0;
		for (DemoEvent e : DemoCatalog.EVENTS) {
			participations += insertParticipations(e, personas, byKey.get(e.host()));
		}
		int links = 0;
		int messages = 0;
		for (DemoLink link : DemoCatalog.LINKS) {
			DemoPersona a = byKey.get(link.a());
			DemoPersona b = byKey.get(link.b());
			links += insertLink(a, b);
			messages += insertMessages(a, b, link.messages(), now);
		}
		Report report = new Report(false, null, users, events, participations, links, messages);
		log.info("Community demo seed: {}", report);
		return report;
	}

	/** Descrizione del primo conflitto con dati NON demo, oppure null se si puo' procedere. */
	private String findConflict(List<DemoPersona> personas) {
		List<UUID> ids = personas.stream().map(DemoPersona::id).toList();
		List<String> usernames = personas.stream().map(p -> p.username().toLowerCase()).toList();
		List<String> emails = personas.stream().map(DemoPersona::email).toList();
		long foreignUsers = jdbc.sql("""
				SELECT count(*) FROM app_user
				 WHERE (lower(username) IN (:usernames) OR email IN (:emails) OR id IN (:ids))
				   AND NOT (id IN (:ids) AND email IN (:emails) AND lower(username) IN (:usernames))
				""")
				.param("usernames", usernames).param("emails", emails).param("ids", ids).query(Long.class).single();
		if (foreignUsers > 0) {
			return "an existing non-demo account uses a demo id, username or email";
		}
		List<UUID> eventIds = DemoCatalog.EVENTS.stream().map(DemoEvent::id).toList();
		long foreignEvents = jdbc.sql("""
				SELECT count(*) FROM event
				 WHERE id IN (:ids) AND NOT (demo AND created_by IN (:users))
				""")
				.param("ids", eventIds).param("users", ids).query(Long.class).single();
		if (foreignEvents > 0) {
			return "an existing non-demo event uses a demo event id";
		}
		return null;
	}

	private int upsertUser(DemoPersona p) {
		return jdbc.sql("""
				INSERT INTO app_user (id, username, email, password_hash, display_name, bio, avatar_url, role, discoverable)
				VALUES (:id, :username, :email, :hash, :name, :bio, :avatar, 'USER', true)
				ON CONFLICT (id) DO UPDATE
				   SET display_name = EXCLUDED.display_name, bio = EXCLUDED.bio, avatar_url = EXCLUDED.avatar_url,
				       discoverable = true, updated_at = now()
				 WHERE app_user.email = EXCLUDED.email AND app_user.role = 'USER'
				   AND (app_user.display_name, app_user.bio, app_user.avatar_url, app_user.discoverable)
				       IS DISTINCT FROM (EXCLUDED.display_name, EXCLUDED.bio, EXCLUDED.avatar_url, true)
				""")
				.param("id", p.id()).param("username", p.username()).param("email", p.email())
				.param("hash", DemoPersona.UNUSABLE_PASSWORD_HASH).param("name", p.displayName()).param("bio", p.fullBio())
				.param("avatar", p.avatarUrl())
				.update();
	}

	private void insertInterests(DemoPersona p) {
		jdbc.sql("""
				INSERT INTO user_interest (user_id, interest_id)
				SELECT :id, i.id FROM interest i WHERE i.slug IN (:slugs)
				ON CONFLICT DO NOTHING
				""")
				.param("id", p.id()).param("slugs", p.interests()).update();
	}

	private int upsertEvent(DemoEvent e, DemoPersona host, Instant now) {
		ZonedDateTime start = LocalDate.now(clock).plusDays(e.dayOffset()).atTime(e.start()).atZone(clock.getZone());
		if (start.toInstant().isBefore(now.plus(MIN_LEAD_TIME))) {
			start = start.plusDays(1);
		}
		String theme = DemoCatalog.COVER_THEME.get(e.number());
		int changed = jdbc.sql("""
				INSERT INTO event (id, title, description, category, status, starts_at, ends_at, venue_name, address,
				                   neighbourhood, latitude, longitude, image_url, image_alt, image_credit, demo, event_type, created_by)
				VALUES (:id, :title, :description, :category, 'PUBLISHED', :startsAt, :endsAt, :venue, :address,
				        :hood, :lat, :lon, :imageUrl, :imageAlt, :imageCredit, true, 'OFFICIAL', :host)
				ON CONFLICT (id) DO UPDATE
				   SET starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at, updated_at = now()
				 WHERE event.demo AND event.created_by = EXCLUDED.created_by AND event.status = 'PUBLISHED'
				   AND COALESCE(event.ends_at, event.starts_at + interval '3 hours') < now()
				""")
				.param("id", e.id()).param("title", e.title()).param("description", e.description() + DemoCatalog.EVENT_DISCLAIMER)
				.param("category", e.category().name())
				.param("startsAt", utc(start.toInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("endsAt", utc(start.plusMinutes(e.minutes()).toInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("venue", e.venue()).param("address", e.address()).param("hood", e.neighbourhood())
				.param("lat", e.latitude()).param("lon", e.longitude()).param("host", host.id())
				.param("imageUrl", DemoArt.coverUrl(theme)).param("imageAlt", DemoArt.coverAlt(theme)).param("imageCredit", DemoArt.CREDIT)
				.update();
		// Eventi demo gia' presenti (seed precedente, senza copertina): si completa solo la copertina, e solo del NOSTRO evento.
		changed += jdbc.sql("""
				UPDATE event SET image_url = :imageUrl, image_alt = :imageAlt, image_credit = :imageCredit, updated_at = now()
				 WHERE id = :id AND demo AND created_by = :host
				   AND (image_url, image_alt, image_credit) IS DISTINCT FROM (:imageUrl, :imageAlt, :imageCredit)
				""")
				.param("id", e.id()).param("host", host.id())
				.param("imageUrl", DemoArt.coverUrl(theme)).param("imageAlt", DemoArt.coverAlt(theme)).param("imageCredit", DemoArt.CREDIT)
				.update();
		return changed;
	}

	/**
	 * L'organizzatore GOING, poi 3-7 persone compatibili: punteggio 2 per ogni interesse identico a quelli dell'evento, 1 per ogni
	 * interesse della stessa categoria del catalogo (es. musica). Prima chi ha il punteggio piu' alto; una su tre INTERESTED.
	 */
	private int insertParticipations(DemoEvent e, List<DemoPersona> personas, DemoPersona host) {
		record Candidate(DemoPersona persona, long score) {
		}
		Set<String> categories = e.tags().stream().map(DemoCatalog.CATEGORY_OF::get).collect(Collectors.toSet());
		List<Candidate> candidates = personas.stream().filter(p -> p != host)
				.map(p -> new Candidate(p, p.interests().stream()
						.mapToLong(i -> e.tags().contains(i) ? 2 : categories.contains(DemoCatalog.CATEGORY_OF.get(i)) ? 1 : 0).sum()))
				.filter(c -> c.score() > 0)
				.sorted(Comparator.comparingLong(Candidate::score).reversed()
						.thenComparingInt(c -> (c.persona().number() + e.number()) % personas.size()))
				.limit(3 + e.number() % 5).toList();
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[] { host.id(), ParticipationStatus.GOING });
		for (int k = 0; k < candidates.size(); k++) {
			rows.add(new Object[] { candidates.get(k).persona().id(), k % 3 == 2 ? ParticipationStatus.INTERESTED : ParticipationStatus.GOING });
		}
		int inserted = 0;
		for (Object[] row : rows) {
			inserted += jdbc.sql("""
					INSERT INTO event_participation (user_id, event_id, status, visible_to_others)
					VALUES (:user, :event, :status, true)
					ON CONFLICT (user_id, event_id) DO NOTHING
					""")
					.param("user", row[0]).param("event", e.id()).param("status", ((ParticipationStatus) row[1]).name()).update();
		}
		return inserted;
	}

	/** Link ACCEPTED tra due account demo. Se esiste gia' una richiesta tra loro (qualunque stato) non si tocca. */
	private int insertLink(DemoPersona a, DemoPersona b) {
		return jdbc.sql("""
				INSERT INTO link_request (requester_id, recipient_id, status, responded_at)
				VALUES (:a, :b, 'ACCEPTED', now())
				ON CONFLICT DO NOTHING
				""")
				.param("a", a.id()).param("b", b.id()).update();
	}

	/** Messaggi solo se la conversazione e' ancora vuota (e solo su Link tra account demo, qui sempre cosi'). */
	private int insertMessages(DemoPersona a, DemoPersona b, List<String> bodies, Instant now) {
		if (bodies.isEmpty()) {
			return 0;
		}
		UUID link = jdbc.sql("""
				SELECT id FROM link_request
				 WHERE status = 'ACCEPTED'
				   AND ((requester_id = :a AND recipient_id = :b) OR (requester_id = :b AND recipient_id = :a))
				""")
				.param("a", a.id()).param("b", b.id()).query(UUID.class).optional().orElse(null);
		if (link == null || jdbc.sql("SELECT count(*) FROM chat_message WHERE link_request_id = :l").param("l", link).query(Long.class).single() > 0) {
			return 0;
		}
		int inserted = 0;
		for (int i = 0; i < bodies.size(); i++) {
			Instant at = now.minus(Duration.ofMinutes(10L * (bodies.size() - i)));
			boolean last = i == bodies.size() - 1;
			inserted += jdbc.sql("""
					INSERT INTO chat_message (link_request_id, sender_id, body, created_at, read_at)
					VALUES (:l, :sender, :body, :at, :readAt)
					""")
					.param("l", link).param("sender", i % 2 == 0 ? a.id() : b.id()).param("body", bodies.get(i))
					.param("at", utc(at), Types.TIMESTAMP_WITH_TIMEZONE)
					.param("readAt", last ? null : utc(at.plusSeconds(60)), Types.TIMESTAMP_WITH_TIMEZONE)
					.update();
		}
		return inserted;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	/** Gli id degli account demo (per i test e per riconoscerli). */
	public static Set<UUID> demoUserIds() {
		return DemoCatalog.PERSONAS.stream().map(DemoPersona::id).collect(Collectors.toUnmodifiableSet());
	}

}
