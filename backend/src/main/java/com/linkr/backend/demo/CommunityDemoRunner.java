package com.linkr.backend.demo;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Avvia il seed della community demo all'avvio. Stesse condizioni del seeder: profilo "dev" o "demo" + linkr.demo.community.enabled=true. */
@Component
@Profile({"dev", "demo"})
@ConditionalOnProperty(name = "linkr.demo.community.enabled", havingValue = "true")
public class CommunityDemoRunner implements ApplicationRunner {

	private final CommunityDemoSeeder seeder;

	public CommunityDemoRunner(CommunityDemoSeeder seeder) {
		this.seeder = seeder;
	}

	@Override
	public void run(ApplicationArguments args) {
		seeder.seed();
	}

}
