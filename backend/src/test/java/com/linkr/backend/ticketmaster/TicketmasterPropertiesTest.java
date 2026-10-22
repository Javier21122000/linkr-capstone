package com.linkr.backend.ticketmaster;

import static com.linkr.backend.ticketmaster.TicketmasterTestData.KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.linkr.backend.ticketmaster.TicketmasterProperties.LocationFilter;

class TicketmasterPropertiesTest {

	private static TicketmasterProperties bind(Map<String, Object> values) {
		return new Binder(new MapConfigurationPropertySource(values)).bind("linkr.ticketmaster", TicketmasterProperties.class).get();
	}

	@Test
	void everythingIsOffAndSafeByDefault() {
		TicketmasterProperties p = bind(Map.of("linkr.ticketmaster.enabled", "false"));

		assertThat(p.enabled()).isFalse();
		assertThat(p.hasApiKey()).isFalse();
		assertThat(p.windowDays()).isEqualTo(30);
		assertThat(p.maxCandidates()).isEqualTo(200);
		assertThat(p.importEnabled()).as("l'importazione e' spenta di base").isFalse();
		assertThat(p.importDefaultCandidates()).isEqualTo(20);
		assertThat(p.importMaxCandidates()).isEqualTo(50);
		assertThat(p.pageSize()).isEqualTo(50);
		assertThat(p.maxPages()).isEqualTo(8);
		assertThat(p.maxCallsPerRun()).isEqualTo(15);
		assertThat(p.maxRetries()).isEqualTo(2);
		assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(p.minCallInterval()).isEqualTo(Duration.ofMillis(500));
		assertThat(p.locale()).isEqualTo("it-it,it,*");
		assertThat(p.locationFilter()).isEqualTo(LocationFilter.GEOPOINT);
		assertThat(p.baseUrl()).isEqualTo("https://app.ticketmaster.com/discovery/v2");
	}

	@Test
	void theApplicationPropertiesPlaceholdersDefaultToDisabledAndNoKey() {
		// Un valore vuoto (variabile non impostata: "${LINKR_TICKETMASTER_API_KEY:}") non deve contare come chiave.
		TicketmasterProperties p = bind(Map.of("linkr.ticketmaster.enabled", "false", "linkr.ticketmaster.api-key", ""));

		assertThat(p.enabled()).isFalse();
		assertThat(p.hasApiKey()).isFalse();
	}

	@Test
	void defaultAreaIsTheMilanBoxOfTheFrontend() {
		TicketmasterProperties.Area area = bind(Map.of("linkr.ticketmaster.enabled", "false")).area();

		assertThat(area.minLatitude()).isEqualTo(45.35);
		assertThat(area.maxLatitude()).isEqualTo(45.60);
		assertThat(area.minLongitude()).isEqualTo(8.95);
		assertThat(area.maxLongitude()).isEqualTo(9.45);
		assertThat(area.contains(45.4642, 9.19)).isTrue();
		assertThat(area.contains(41.9, 12.5)).isFalse();
	}

	@Test
	void coveringRadiusIncludesEveryCornerOfTheBox() {
		TicketmasterProperties.Area area = bind(Map.of("linkr.ticketmaster.enabled", "false")).area();
		int radius = area.coveringRadiusKm();

		assertThat(radius).isBetween(23, 26);
		double[][] corners = { { area.minLatitude(), area.minLongitude() }, { area.minLatitude(), area.maxLongitude() },
				{ area.maxLatitude(), area.minLongitude() }, { area.maxLatitude(), area.maxLongitude() } };
		for (double[] corner : corners) {
			assertThat(TicketmasterProperties.distanceKm(area.centerLatitude(), area.centerLongitude(), corner[0], corner[1]))
					.isLessThanOrEqualTo(radius);
		}
	}

	@Test
	void anInvalidAreaIsRejected() {
		assertThatThrownBy(() -> new TicketmasterProperties.Area(45.6, 45.35, 8.95, 9.45)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TicketmasterProperties.Area(45.35, 95, 8.95, 9.45)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theKeyNeverAppearsInToString() {
		TicketmasterProperties p = TicketmasterTestData.properties();

		assertThat(p.hasApiKey()).isTrue();
		assertThat(p.toString()).doesNotContain(KEY).contains("apiKey=***");
		assertThat(bind(Map.of("linkr.ticketmaster.enabled", "false")).toString()).contains("<not set>");
	}

	@Test
	void theKeyIsTrimmed() {
		assertThat(bind(Map.of("linkr.ticketmaster.enabled", "true", "linkr.ticketmaster.api-key", "  abc  ")).apiKey()).isEqualTo("abc");
		assertThat(bind(Map.of("linkr.ticketmaster.enabled", "true", "linkr.ticketmaster.api-key", "   ")).hasApiKey()).isFalse();
	}

	@ParameterizedTest
	@CsvSource({ "window-days,0", "window-days,91", "max-candidates,0", "max-candidates,501", "page-size,0", "page-size,201", "max-pages,0",
			"max-pages,21", "import-max-candidates,0", "import-max-candidates,101", "import-default-candidates,0", "import-default-candidates,51", "max-calls-per-run,0", "max-calls-per-run,61", "max-retries,-1", "max-retries,6", "connect-timeout,0s",
			"read-timeout,0s", "retry-backoff,-1s", "min-call-interval,-1s" })
	void invalidTuningIsRejectedWhenTheIntegrationIsEnabled(String name, String value) {
		assertThatThrownBy(() -> bind(Map.of("linkr.ticketmaster.enabled", "true", "linkr.ticketmaster." + name, value)))
				.isInstanceOf(BindException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidTuningDoesNotPreventTheDisabledApplicationFromStarting() {
		assertThatCode(() -> bind(Map.of("linkr.ticketmaster.enabled", "false", "linkr.ticketmaster.window-days", "0",
				"linkr.ticketmaster.base-url", "ftp://nonsense"))).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@CsvSource({ "https://app.ticketmaster.com/discovery/v2,true", "http://localhost:8089/discovery/v2,true", "http://127.0.0.1:9/x,true",
			"http://app.ticketmaster.com/discovery/v2,false", "http://evil.example.com,false", "ftp://app.ticketmaster.com,false",
			"https://user:pass@app.ticketmaster.com,false", "https://app.ticketmaster.com/v2?apikey=x,false", "not a url,false" })
	void baseUrlMustBeHttpsUnlessItIsLocalhost(String url, boolean accepted) {
		Map<String, Object> values = Map.of("linkr.ticketmaster.enabled", "true", "linkr.ticketmaster.base-url", url);
		if (accepted) {
			assertThat(bind(values).baseUrl()).isEqualTo(url);
		}
		else {
			assertThatThrownBy(() -> bind(values)).isInstanceOf(BindException.class);
		}
	}

}
