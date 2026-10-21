package com.linkr.backend.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UploadRateLimiterTest {

	/** Orologio che si sposta a comando. */
	private static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-10-20T10:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

	private final MutableClock clock = new MutableClock();

	private UploadRateLimiter limiter(int perHour, int perDay) {
		return new UploadRateLimiter(new UploadProperties(Path.of("unused"), perHour, perDay), clock);
	}

	@Test
	void blocksTheAttemptsOverTheHourlyLimitAndLetsTheWindowSlide() {
		UploadRateLimiter limiter = limiter(3, 100);
		UUID user = UUID.randomUUID();

		assertThat(limiter.tryAcquire(user)).isTrue();
		assertThat(limiter.tryAcquire(user)).isTrue();
		assertThat(limiter.tryAcquire(user)).isTrue();
		assertThat(limiter.tryAcquire(user)).isFalse();
		assertThat(limiter.tryAcquire(user)).isFalse();              // i rifiuti non allungano il blocco

		clock.advance(Duration.ofMinutes(59));
		assertThat(limiter.tryAcquire(user)).isFalse();
		clock.advance(Duration.ofMinutes(2));                         // sono passati piu' di 60 minuti dal primo tentativo
		assertThat(limiter.tryAcquire(user)).isTrue();
	}

	@Test
	void appliesTheDailyLimitAcrossHours() {
		UploadRateLimiter limiter = limiter(10, 4);
		UUID user = UUID.randomUUID();

		for (int i = 0; i < 4; i++) {
			assertThat(limiter.tryAcquire(user)).isTrue();
			clock.advance(Duration.ofHours(2));                       // sempre sotto il limite orario
		}
		assertThat(limiter.tryAcquire(user)).isFalse();               // ma 4 nelle ultime 24 ore: basta
		clock.advance(Duration.ofHours(17));
		assertThat(limiter.tryAcquire(user)).isTrue();                // il primo e' uscito dalla finestra di 24 ore
	}

	@Test
	void eachUserHasItsOwnBudget() {
		UploadRateLimiter limiter = limiter(1, 5);
		UUID anna = UUID.randomUUID();
		UUID bea = UUID.randomUUID();

		assertThat(limiter.tryAcquire(anna)).isTrue();
		assertThat(limiter.tryAcquire(anna)).isFalse();
		assertThat(limiter.tryAcquire(bea)).isTrue();
	}

}
