package com.linkr.backend.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Orologio dell'applicazione, con il fuso di Milano.
 * Le date nel database sono in UTC; questo fuso serve per interpretare i filtri (es. "oggi")
 * e per scrivere le date nelle risposte API. Nei test si puo' sostituire con un Clock fisso.
 */
@Configuration
public class TimeConfig {

	public static final ZoneId MILAN_ZONE = ZoneId.of("Europe/Rome");

	@Bean
	public Clock clock() {
		return Clock.system(MILAN_ZONE);
	}

}
