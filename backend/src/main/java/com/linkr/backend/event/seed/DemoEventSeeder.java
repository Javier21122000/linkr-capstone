package com.linkr.backend.event.seed;

import java.sql.Types;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.demo.DemoArt;
import com.linkr.backend.event.EventCategory;

/**
 * Inserisce eventi DIMOSTRATIVI a Milano. Attivo SOLO con i profili "dev" e "demo" (il profilo di produzione standard non lo attiva).
 *
 * Come funziona (idempotente):
 *  - ogni evento ha un UUID fisso, quindi riavviare l'app non crea duplicati;
 *  - se l'evento non esiste viene inserito con date future rispetto a ADESSO;
 *  - se esiste ed e' ancora attuale, non viene toccato;
 *  - se esiste, e' demo=true ed e' ormai terminato, viene "spostato" a una data futura;
 *  - gli eventi con demo=false non vengono mai modificati (la clausola WHERE sotto lo impedisce);
 *  - le copertine sono illustrazioni locali (DemoArt, frontend/public/demo/covers), non URL esterni: l'UPDATE dedicato le applica anche agli eventi gia' presenti.
 *
 * Gli eventi sono inventati: usano luoghi pubblici di Milano o locali fittizi ("demo venue"),
 * non hanno organizzatori e la descrizione dice esplicitamente che non sono reali.
 */
@Component
@Profile({"dev", "demo"})
public class DemoEventSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoEventSeeder.class);

	private static final String DISCLAIMER = " Demo event: not a real listing.";
		private static final Duration MIN_LEAD_TIME = Duration.ofHours(1);

	// Stessa regola di EventService.DEFAULT_DURATION: senza endsAt l'evento dura 3 ore.
	private static final String UPSERT = """
			INSERT INTO event (id, title, description, category, status, starts_at, ends_at, venue_name, address,
			                   neighbourhood, latitude, longitude, image_url, image_alt, image_credit, demo)
			VALUES (:id, :title, :description, :category, 'PUBLISHED', :startsAt, :endsAt, :venueName, :address,
			        :neighbourhood, :latitude, :longitude, :imageUrl, :imageAlt, :imageCredit, true)
			ON CONFLICT (id) DO UPDATE
			   SET starts_at = EXCLUDED.starts_at, ends_at = EXCLUDED.ends_at, updated_at = now()
			 WHERE event.demo = true
			   AND event.status = 'PUBLISHED'
			   AND COALESCE(event.ends_at, event.starts_at + interval '3 hours') < now()
			""";

	private final JdbcClient jdbc;
	private final Clock clock;

	public DemoEventSeeder(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** Evento demo: la data e' espressa come "tra N giorni alle HH:mm" rispetto all'avvio. */
	private record DemoEvent(String id, String title, String description, EventCategory category,
			int dayOffset, LocalTime startTime, Integer durationMinutes,
			String venueName, String address, String neighbourhood, double latitude, double longitude) {

		/** Tema della copertina locale (DemoArt), uno per evento. */
		String coverTheme() {
			return COVER_THEMES.get(id);
		}

	}

	private static final java.util.Map<String, String> COVER_THEMES = java.util.Map.ofEntries(
			java.util.Map.entry("018f0000-0000-7000-8000-000000000001", "music-concert"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000002", "food-table"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000003", "culture-gallery"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000004", "outdoors-run"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000005", "nightlife-disco"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000006", "social-languages"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000007", "sport-fitness"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000008", "social-games"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000009", "sport-football"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000010", "outdoors-park"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000011", "music-jazz"),
			java.util.Map.entry("018f0000-0000-7000-8000-000000000012", "food-table"));

	private static final List<DemoEvent> EVENTS = List.of(
			new DemoEvent("018f0000-0000-7000-8000-000000000001", "Courtyard Sessions",
					"An open-air set, local selectors and a late aperitivo in a hidden courtyard.",
					EventCategory.MUSIC, 0, LocalTime.of(19, 30), 210,
					"Cortile 68 (demo venue)", "Demo address, Isola district", "Isola", 45.4878, 9.1890),
			new DemoEvent("018f0000-0000-7000-8000-000000000002", "Table for new friends",
					"A relaxed shared-table dinner designed for good conversation and seasonal plates.",
					EventCategory.FOOD, 0, LocalTime.of(20, 15), 150,
					"Casa Comune (demo venue)", "Demo address, Porta Venezia district", "Porta Venezia", 45.4745, 9.2060),
			new DemoEvent("018f0000-0000-7000-8000-000000000003", "After-hours design walk",
					"A guided evening through independent studios and installations, starting from the square.",
					EventCategory.CULTURE, 1, LocalTime.of(18, 45), 120,
					"Piazza Gae Aulenti", "Piazza Gae Aulenti, Milano", "Porta Nuova", 45.4837, 9.1903),
			new DemoEvent("018f0000-0000-7000-8000-000000000004", "Sunrise city loop",
					"An easy-paced morning run along the canals, followed by coffee for anyone who wants to stay.",
					EventCategory.OUTDOORS, 1, LocalTime.of(7, 0), 75,
					"Darsena", "Darsena, Milano", "Navigli", 45.4535, 9.1745),
			new DemoEvent("018f0000-0000-7000-8000-000000000005", "Silent disco by the Arco",
					"Three channels, one dance floor and everyone wearing headphones, on the lawn near the arch.",
					EventCategory.NIGHTLIFE, 2, LocalTime.of(21, 30), 180,
					"Arco della Pace", "Arco della Pace, Parco Sempione, Milano", "Sempione", 45.4755, 9.1717),
			new DemoEvent("018f0000-0000-7000-8000-000000000006", "Language exchange aperitivo",
					"Practise Italian, English or Spanish over a drink: every table has a mix of levels.",
					EventCategory.SOCIAL, 2, LocalTime.of(19, 0), 120,
					"Bar Tandem (demo venue)", "Demo address, Ticinese district", "Ticinese", 45.4520, 9.1790),
			new DemoEvent("018f0000-0000-7000-8000-000000000007", "Sunrise yoga in the park",
					"A beginner-friendly outdoor yoga class on the grass. Bring a mat or a towel.",
					EventCategory.SPORT, 3, LocalTime.of(8, 30), 60,
					"Parco Sempione", "Parco Sempione, Milano", "Sempione", 45.4723, 9.1737),
			new DemoEvent("018f0000-0000-7000-8000-000000000008", "Board games & strangers",
					"Short games, rotating tables and no need to know anyone: ideal to meet people in the neighbourhood.",
					EventCategory.SOCIAL, 3, LocalTime.of(20, 30), null,
					"Sala Dadi (demo venue)", "Demo address, NoLo district", "NoLo", 45.5030, 9.2160),
			new DemoEvent("018f0000-0000-7000-8000-000000000009", "Casual 5-a-side football",
					"Friendly mixed-level match, teams drawn on the spot. Come alone, leave with a team.",
					EventCategory.SPORT, 4, LocalTime.of(18, 0), 90,
					"Parco Lambro", "Parco Lambro, Milano", "Lambrate", 45.5019, 9.2376),
			new DemoEvent("018f0000-0000-7000-8000-000000000010", "Golden hour walk",
					"A slow walk through the park and around the towers, timed for the best light of the day.",
					EventCategory.OUTDOORS, 4, LocalTime.of(17, 30), 90,
					"Parco CityLife", "Piazza Tre Torri, Milano", "CityLife", 45.4783, 9.1558),
			new DemoEvent("018f0000-0000-7000-8000-000000000011", "Jazz on the canal",
					"An informal acoustic evening along the Naviglio, with a small band and room to chat.",
					EventCategory.MUSIC, 5, LocalTime.of(20, 0), 120,
					"Naviglio Grande", "Alzaia Naviglio Grande, Milano", "Navigli", 45.4510, 9.1700),
			new DemoEvent("018f0000-0000-7000-8000-000000000012", "Street-food picnic",
					"Bring something to share and meet other food lovers on the lawn in the public gardens.",
					EventCategory.FOOD, 6, LocalTime.of(12, 30), 180,
					"Giardini Indro Montanelli", "Giardini Indro Montanelli, Milano", "Porta Venezia", 45.4745, 9.2013));

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		Instant now = clock.instant();
		int changed = 0;
		for (DemoEvent event : EVENTS) {
			changed += upsert(event, now);
		}
		log.info("Demo events: {} inserted or rescheduled, {} already up to date", changed, EVENTS.size() - changed);
	}

	private int upsert(DemoEvent event, Instant now) {
		ZonedDateTime start = LocalDate.now(clock).plusDays(event.dayOffset())
				.atTime(event.startTime()).atZone(clock.getZone());
		if (start.toInstant().isBefore(now.plus(MIN_LEAD_TIME))) {
			start = start.plusDays(1); // l'orario di oggi e' gia' passato: si passa a domani
		}

		int changed = jdbc.sql(UPSERT)
				.param("id", UUID.fromString(event.id()))
				.param("title", event.title())
				.param("description", event.description() + DISCLAIMER)
				.param("category", event.category().name())
				.param("startsAt", utc(start.toInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("endsAt", event.durationMinutes() == null ? null
						: utc(start.plusMinutes(event.durationMinutes()).toInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
				.param("venueName", event.venueName())
				.param("address", event.address())
				.param("neighbourhood", event.neighbourhood())
				.param("latitude", event.latitude())
				.param("longitude", event.longitude())
				.param("imageUrl", DemoArt.coverUrl(event.coverTheme()))
				.param("imageAlt", DemoArt.coverAlt(event.coverTheme()))
				.param("imageCredit", DemoArt.CREDIT)
				.update();
		// Copertina locale al posto dei vecchi URL esterni: solo i NOSTRI eventi demo (id fisso, demo=true, senza creatore).
		changed += jdbc.sql("""
				UPDATE event SET image_url = :imageUrl, image_alt = :imageAlt, image_credit = :imageCredit, updated_at = now()
				 WHERE id = :id AND demo AND created_by IS NULL
				   AND (image_url, image_alt, image_credit) IS DISTINCT FROM (:imageUrl, :imageAlt, :imageCredit)
				""")
				.param("id", UUID.fromString(event.id()))
				.param("imageUrl", DemoArt.coverUrl(event.coverTheme()))
				.param("imageAlt", DemoArt.coverAlt(event.coverTheme()))
				.param("imageCredit", DemoArt.CREDIT)
				.update();
		return changed;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

}
