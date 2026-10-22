package com.linkr.backend.ticketmaster;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;

/**
 * Garanzie "nessuno scheduler" e "nessuna chiamata esterna all'avvio", verificate in modo strutturale: nel package ticketmaster
 * non esiste nessun punto che parta da solo, e in tutta l'applicazione non c'e' nessuno scheduler. L'unico modo di far partire una
 * richiesta a Ticketmaster e' la chiamata esplicita all'endpoint admin (che a sua volta richiede integrazione attiva e API Key).
 *
 * Se in futuro serve davvero uno scheduler, questo test va aggiornato consapevolmente: e' una scelta di prodotto (sprint C2: nessuno scheduler).
 */
class TicketmasterNoStartupActivityTest {

	private static List<Class<?>> mainClassesIn(String basePackage) throws ClassNotFoundException {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				return true; // anche interfacce, record e classi annidate
			}
		};
		scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*")));
		List<Class<?>> classes = new ArrayList<>();
		for (BeanDefinition definition : scanner.findCandidateComponents(basePackage)) {
			String name = definition.getBeanClassName();
			if (!name.contains("Test")) { // si guardano le classi dell'applicazione, non quelle dei test
				classes.add(Class.forName(name, false, TicketmasterNoStartupActivityTest.class.getClassLoader()));
			}
		}
		return classes;
	}

	@Test
	void theTicketmasterPackageHasNoRunnerSchedulerOrStartupHook() throws ClassNotFoundException {
		List<Class<?>> classes = mainClassesIn("com.linkr.backend.ticketmaster");
		assertThat(classes).as("la scansione deve trovare le classi principali del package").hasSizeGreaterThan(10);

		for (Class<?> type : classes) {
			assertThat(ApplicationRunner.class.isAssignableFrom(type)).as(type + " is an ApplicationRunner").isFalse();
			assertThat(CommandLineRunner.class.isAssignableFrom(type)).as(type + " is a CommandLineRunner").isFalse();
			assertThat(SmartLifecycle.class.isAssignableFrom(type)).as(type + " is a SmartLifecycle").isFalse();
			assertThat(ApplicationListener.class.isAssignableFrom(type)).as(type + " is an ApplicationListener").isFalse();
			for (Method method : type.getDeclaredMethods()) {
				assertThat(method.isAnnotationPresent(PostConstruct.class)).as(method + " is @PostConstruct").isFalse();
				assertThat(method.isAnnotationPresent(EventListener.class)).as(method + " is an @EventListener").isFalse();
			}
		}
	}

	@Test
	void noSchedulingIsEnabledOrUsedAnywhereInTheApplication() throws ClassNotFoundException {
		List<Class<?>> classes = mainClassesIn("com.linkr.backend");
		assertThat(classes).hasSizeGreaterThan(50);

		for (Class<?> type : classes) {
			assertThat(type.isAnnotationPresent(EnableScheduling.class)).as(type + " enables scheduling").isFalse();
			for (Method method : type.getDeclaredMethods()) {
				assertThat(method.isAnnotationPresent(Scheduled.class)).as(method + " is @Scheduled").isFalse();
			}
		}
	}

}
