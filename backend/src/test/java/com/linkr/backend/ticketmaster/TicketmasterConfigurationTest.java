package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.linkr.backend.ticketmaster.TicketmasterDryRunReport.Status;
import com.sun.net.httpserver.HttpServer;

/**
 * La configurazione vera (application.properties) letta in un contesto Spring minimo, SENZA database e con un ambiente simulato:
 * le variabili LINKR_TICKETMASTER_* arrivano da una mappa finta, quindi il test non dipende da cosa c'e' nella shell di chi lo lancia.
 */
class TicketmasterConfigurationTest {

	private static final String SECRET = "SECRET-from-the-environment-0123456789";

	private HttpServer localServer;

	@AfterEach
	void stopLocalServer() {
		if (localServer != null) {
			localServer.stop(0);
		}
	}

	private static ApplicationContextRunner runnerWithEnvironment(Map<String, Object> environment) {
		return new ApplicationContextRunner()
				// l'ambiente vero della macchina viene sostituito PRIMA che application.properties sia letto
				.withInitializer(context -> {
					context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
					context.getEnvironment().getPropertySources()
							.addLast(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment));
				})
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.withUserConfiguration(TicketmasterConfig.class, TicketmasterClient.class);
	}

	private static final class NoEvents implements ExistingEventsSource {

		@Override
		public Set<String> importedExternalIds(Collection<String> externalIds) {
			return Set.of();
		}

		@Override
		public List<ExistingEvent> eventsStartingBetween(java.time.Instant from, java.time.Instant to) {
			return List.of();
		}

	}

	@Test
	void withNothingInTheEnvironmentTheIntegrationIsOffWithSafeDefaults() {
		runnerWithEnvironment(Map.of()).run(context -> {
			assertThat(context).hasNotFailed();
			TicketmasterProperties properties = context.getBean(TicketmasterProperties.class);
			assertThat(properties.enabled()).isFalse();
			assertThat(properties.hasApiKey()).isFalse();
			assertThat(properties.windowDays()).isEqualTo(30);
			assertThat(properties.maxCandidates()).isEqualTo(200);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
			assertThat(context).hasSingleBean(TicketmasterApi.class);
		});
	}

	@Test
	void emptyVariablesAreTreatedAsNotSet() {
		runnerWithEnvironment(Map.of("LINKR_TICKETMASTER_API_KEY", "", "LINKR_TICKETMASTER_ENABLED", "false")).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(TicketmasterProperties.class).hasApiKey()).isFalse();
		});
	}

	@Test
	void theDocumentedEnvironmentVariablesAreHonoured() {
		Map<String, Object> environment = Map.of(
				"LINKR_TICKETMASTER_ENABLED", "true",
				"LINKR_TICKETMASTER_API_KEY", SECRET,
				"LINKR_TICKETMASTER_WINDOW_DAYS", "14",
				"LINKR_TICKETMASTER_MAX_CANDIDATES", "50",
				"LINKR_TICKETMASTER_CONNECT_TIMEOUT", "2s",
				"LINKR_TICKETMASTER_READ_TIMEOUT", "5s");

		runnerWithEnvironment(environment).run(context -> {
			assertThat(context).hasNotFailed();
			TicketmasterProperties properties = context.getBean(TicketmasterProperties.class);
			assertThat(properties.enabled()).isTrue();
			assertThat(properties.apiKey()).isEqualTo(SECRET);
			assertThat(properties.windowDays()).isEqualTo(14);
			assertThat(properties.maxCandidates()).isEqualTo(50);
			assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
			assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.toString()).doesNotContain(SECRET);
		});
	}

	@Test
	void aBrokenSettingDoesNotStopTheApplicationWhileTheIntegrationIsOff() {
		runnerWithEnvironment(Map.of("LINKR_TICKETMASTER_WINDOW_DAYS", "0", "LINKR_TICKETMASTER_MAX_CANDIDATES", "999999"))
				.withPropertyValues("linkr.ticketmaster.base-url=http://[not a url")
				.withBean(Clock.class, Clock::systemUTC)
				.withBean(ExistingEventsSource.class, NoEvents::new)
				.withUserConfiguration(TicketmasterDryRunService.class)
				.run(context -> {
					assertThat(context).hasNotFailed(); // l'app parte comunque
					TicketmasterDryRunReport report = context.getBean(TicketmasterDryRunService.class).run(null, null);
					assertThat(report.status()).isEqualTo(Status.DISABLED);
				});
	}

	@Test
	void aBrokenSettingFailsFastWhenTheIntegrationIsOnAndNeverRevealsTheKey() {
		runnerWithEnvironment(Map.of("LINKR_TICKETMASTER_ENABLED", "true", "LINKR_TICKETMASTER_API_KEY", SECRET,
				"LINKR_TICKETMASTER_WINDOW_DAYS", "0")).run(context -> {
					assertThat(context).hasFailed();
					Throwable failure = context.getStartupFailure();
					for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
						assertThat(String.valueOf(t.getMessage())).doesNotContain(SECRET);
					}
					assertThat(rootMessage(failure)).contains("window-days");
				});
	}

	private static String rootMessage(Throwable failure) {
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return String.valueOf(root.getMessage());
	}

	@Test
	void startingTheApplicationSendsNoRequestEvenWhenFullyEnabled() throws IOException, InterruptedException {
		AtomicInteger hits = new AtomicInteger();
		localServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		localServer.createContext("/", exchange -> {
			hits.incrementAndGet();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		localServer.start();
		String baseUrl = "http://127.0.0.1:" + localServer.getAddress().getPort() + "/discovery/v2";

		runnerWithEnvironment(Map.of("LINKR_TICKETMASTER_ENABLED", "true", "LINKR_TICKETMASTER_API_KEY", SECRET))
				.withPropertyValues("linkr.ticketmaster.base-url=" + baseUrl)
				.withBean(Clock.class, Clock::systemUTC)
				.withBean(ExistingEventsSource.class, NoEvents::new)
				.withUserConfiguration(TicketmasterDryRunService.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(TicketmasterProperties.class).enabled()).isTrue();
					Thread.sleep(300); // se qualcosa partisse all'avvio (anche in un altro thread) lo vedremmo qui
					assertThat(hits).as("richieste ricevute dopo l'avvio").hasValue(0);

					// Solo la chiamata esplicita all'endpoint (qui: al servizio) fa partire una richiesta.
					context.getBean(TicketmasterDryRunService.class).run(null, null);
					assertThat(hits.get()).isGreaterThanOrEqualTo(1);
				});
	}

}
