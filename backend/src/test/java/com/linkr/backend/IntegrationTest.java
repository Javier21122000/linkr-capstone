package com.linkr.backend;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Da usare al posto di @SpringBootTest nei test che aprono il contesto completo (con database).
 * Attiva il profilo "test" e ribadisce con priorita' massima URL e nome atteso del database di test,
 * quindi nemmeno una variabile d'ambiente come SPRING_DATASOURCE_URL puo' dirottarli su linkr_db.
 * Stesso discorso per Ticketmaster: integrazione spenta e senza chiave, anche se nell'ambiente c'e' LINKR_TICKETMASTER_ENABLED=true
 * (le variabili d'ambiente vincono sui file application-*.properties, non su queste proprieta'). Un test che ha bisogno
 * dell'integrazione accesa usa @TestPropertySource (piu' forte) insieme a un client Ticketmaster finto.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/linkr_test_db",
		"linkr.database.expected-name=linkr_test_db",
		"linkr.ticketmaster.enabled=false",
		"linkr.ticketmaster.import-enabled=false",
		"linkr.ticketmaster.api-key=" })
@ActiveProfiles("test")
public @interface IntegrationTest {
}
