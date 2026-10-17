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
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:postgresql://localhost:5432/linkr_test_db",
		"linkr.database.expected-name=linkr_test_db" })
@ActiveProfiles("test")
public @interface IntegrationTest {
}
