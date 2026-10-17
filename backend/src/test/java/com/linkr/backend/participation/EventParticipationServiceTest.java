package com.linkr.backend.participation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.linkr.backend.config.TimeConfig;
import com.linkr.backend.event.Event;
import com.linkr.backend.event.EventMapper;
import com.linkr.backend.event.EventRepository;
import com.linkr.backend.event.EventService;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.user.UserRepository;

/** Regola "evento terminato" con orologio fisso: si provano i confini esatti, senza database. */
class EventParticipationServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

	private final Clock clock = Clock.fixed(NOW, TimeConfig.MILAN_ZONE);
	private final EventParticipationRepository participations = mock(EventParticipationRepository.class);
	private final EventRepository events = mock(EventRepository.class);
	private final UserRepository users = mock(UserRepository.class);
	private final EventParticipationService service =
			new EventParticipationService(participations, events, users, new EventMapper(clock), clock);

	private final UUID userId = UUID.randomUUID();
	private final UUID eventId = UUID.randomUUID();

	@Test
	void eventEndingExactlyNowIsEnded() {
		givenEvent(NOW.minusSeconds(3600), NOW);

		assertEnded();
	}

	@Test
	void eventEndingOneSecondFromNowIsStillOpen() {
		givenEvent(NOW.minusSeconds(3600), NOW.plusSeconds(1));

		assertOpen();
	}

	@Test
	void withoutEndsAtTheEventLastsTheDefaultDurationOfDiscover() {
		Duration threeHours = EventService.DEFAULT_DURATION; // la stessa costante usata da Discover
		assertThat(threeHours).isEqualTo(Duration.ofHours(3));

		givenEvent(NOW.minus(threeHours), null);                 // fine presunta esattamente adesso: terminato
		assertEnded();

		givenEvent(NOW.minus(threeHours).plusSeconds(1), null);  // fine presunta tra 1 secondo: ancora aperto
		assertOpen();

		givenEvent(NOW, null);                                   // appena iniziato: aperto
		assertOpen();

		givenEvent(NOW.minus(threeHours).minusSeconds(1), null); // finito da 1 secondo
		assertEnded();
	}

	@Test
	void explicitEndsAtWinsOverTheDefaultDuration() {
		givenEvent(NOW.minusSeconds(3600), NOW.plusSeconds(1)); // finisce tra 1 secondo
		assertOpen();

		givenEvent(NOW.minusSeconds(60), NOW);                  // finisce adesso, anche se e' iniziato da pochi secondi
		assertEnded();
	}

	private void givenEvent(Instant startsAt, Instant endsAt) {
		when(users.existsById(userId)).thenReturn(true);
		Event event = mock(Event.class); // il builder di Event e' package-private: basta simulare le due date
		when(event.getStartsAt()).thenReturn(startsAt);
		when(event.getEndsAt()).thenReturn(endsAt);
		when(events.findByIdAndStatus(eventId, EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
	}

	private void assertEnded() {
		reset(participations); // piu' scenari nello stesso test: si riparte da zero a ogni controllo
		assertThatThrownBy(() -> service.declare(userId, eventId, ParticipationStatus.GOING, false))
				.isInstanceOf(EventEndedException.class);
		verify(participations, never()).upsert(any(), any(), anyString(), anyBoolean());
	}

	private void assertOpen() {
		reset(participations);
		// l'upsert e' finto: dopo la scrittura il service rilegge la riga, che qui non c'e' -> 404 di partecipazione.
		// Basta a provare che la regola "terminato" NON ha bloccato la richiesta.
		assertThatCode(() -> {
			try {
				service.declare(userId, eventId, ParticipationStatus.GOING, false);
			}
			catch (ParticipationNotFoundException expected) {
				// atteso con il repository finto
			}
		}).doesNotThrowAnyException();
		verify(participations).upsert(userId, eventId, "GOING", false);
	}

}
