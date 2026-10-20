package com.linkr.backend.linkrequest;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.common.page.PageResponse;
import com.linkr.backend.event.Event;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.EventRepository;
import com.linkr.backend.event.EventService;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.event.InvalidEventFilterException;
import com.linkr.backend.linkrequest.LinkRequestRepository.Direction;
import com.linkr.backend.linkrequest.LinkRequestRepository.Row;
import com.linkr.backend.linkrequest.dto.LinkRequestResponse;
import com.linkr.backend.linkrequest.dto.LinkRequestUserResponse;
import com.linkr.backend.participation.EventEndedException;
import com.linkr.backend.people.DiscoveryRequiredException;
import com.linkr.backend.people.ParticipationRequiredException;
import com.linkr.backend.user.UserNotFoundException;

/**
 * Richieste Link. Un Link e' una richiesta tra due persone, nata da un evento a cui partecipano entrambe;
 * NON e' un'amicizia automatica ne' una chat.
 *
 * Due modi di inviare. CON eventId: la richiesta nasce da un evento a cui partecipano entrambe le persone. SENZA eventId:
 * nasce da Discover People e valgono la reciprocita' (mittente visibile, altrimenti 403), il destinatario visibile e almeno un
 * interesse in comune (404 uniforme per ogni altro caso). Anti-spam, vincolo "una richiesta per coppia" e privacy del rifiuto
 * sono gli STESSI per entrambi i modi.
 *
 * Regole d'invio da evento (in quest'ordine): destinatario diverso da se stessi (400), evento PUBLISHED (404) e non terminato (409),
 * mittente partecipante (403), destinatario partecipante VISIBILE (404 uniforme, uguale per inesistente / non
 * partecipante / privato: non si puo' sondare chi esiste), limiti anti-spam (429), una sola richiesta per coppia (409).
 *
 * PRIVACY DEL RIFIUTO: se il destinatario rifiuta, il mittente continua a vedere la richiesta come PENDING
 * (stato, filtri ed elenchi sono proiettati nel repository). Nessun endpoint rivela il rifiuto al mittente.
 *
 * Una volta ACCEPTED il Link e' persistente: non dipende piu' da partecipazioni, visibilita' o fine dell'evento.
 */
@Service
public class LinkRequestService {

	/** Richieste inviate ancora "in attesa" (per il mittente anche quelle rifiutate, che non sa essere rifiutate). */
	public static final int MAX_AWAITING_ANSWER = 20;
	/** Richieste inviate nelle ultime 24 ore. */
	public static final int MAX_PER_DAY = 10;

	private final LinkRequestRepository repository;
	private final EventRepository eventRepository;
	private final Clock clock;

	public LinkRequestService(LinkRequestRepository repository, EventRepository eventRepository, Clock clock) {
		this.repository = repository;
		this.eventRepository = eventRepository;
		this.clock = clock;
	}

	@Transactional
	public LinkRequestResponse send(UUID me, UUID eventId, UUID recipientId) {
		if (me.equals(recipientId)) {
			throw LinkRequestException.self();
		}
		if (eventId == null) {
			return sendFromDiscovery(me, recipientId);
		}
		// Blocca il mittente: i suoi invii simultanei vengono serializzati (vedi lockSender).
		if (!repository.lockSender(me)) {
			throw new UserNotFoundException();
		}

		Event event = eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
				.orElseThrow(() -> new EventNotFoundException(eventId));
		if (EventService.hasEnded(event, clock.instant())) {
			throw new EventEndedException();
		}
		if (!repository.hasParticipation(me, eventId)) {
			throw new ParticipationRequiredException("Declare your own participation in this event to send link requests.");
		}
		if (!repository.isVisibleParticipant(recipientId, eventId)) {
			throw LinkRequestException.recipientNotAvailable();
		}

		return insertAndView(me, recipientId, eventId);
	}

	/** Richiesta senza evento, da Discover People. Ordine: mittente visibile (403), destinatario idoneo (404), anti-spam (429), duplicato (409). */
	private LinkRequestResponse sendFromDiscovery(UUID me, UUID recipientId) {
		Set<UUID> locked = repository.lockUsers(me, recipientId);
		if (!locked.contains(me)) {
			throw new UserNotFoundException();
		}
		if (!repository.isDiscoverable(me)) {
			throw new DiscoveryRequiredException();
		}
		if (!locked.contains(recipientId) || !repository.isDiscoverable(recipientId)
				|| !repository.sharesInterest(me, recipientId)) {
			throw LinkRequestException.recipientNotAvailable();
		}
		return insertAndView(me, recipientId, null);
	}

	/** Anti-spam e inserimento atomico, comuni ai due modi d'invio. */
	private LinkRequestResponse insertAndView(UUID me, UUID recipientId, UUID eventId) {
		if (repository.countAwaitingAnswer(me) >= MAX_AWAITING_ANSWER
				|| repository.countCreatedSince(me, clock.instant().minus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC)) >= MAX_PER_DAY) {
			throw LinkRequestException.tooMany();
		}

		UUID id = repository.insert(me, recipientId, eventId).orElseThrow(LinkRequestException::duplicate);
		return view(id, me);
	}

	@Transactional
	public LinkRequestResponse accept(UUID me, UUID requestId) {
		return answer(me, requestId, LinkRequestStatus.ACCEPTED);
	}

	@Transactional
	public LinkRequestResponse decline(UUID me, UUID requestId) {
		return answer(me, requestId, LinkRequestStatus.DECLINED);
	}

	/**
	 * Risposta atomica: UPDATE ... WHERE status = 'PENDING' AND recipient = me. Se non aggiorna nulla si guarda lo stato:
	 * stesso stato richiesto = ripetizione idempotente (200, nessuna modifica); stato opposto = 409.
	 * Una richiesta inesistente o non indirizzata a "me" (anche se "me" e' il mittente) = 404, senza altri dettagli.
	 */
	private LinkRequestResponse answer(UUID me, UUID requestId, LinkRequestStatus target) {
		if (!repository.answer(requestId, me, target)) {
			LinkRequestStatus current = repository.findStatusForRecipient(requestId, me)
					.orElseThrow(LinkRequestException::notFound);
			if (current != target) {
				throw LinkRequestException.alreadyAnswered();
			}
		}
		return view(requestId, me);
	}

	@Transactional(readOnly = true)
	public PageResponse<LinkRequestResponse> list(UUID me, Direction direction, LinkRequestStatus status, int page, int size) {
		if (page < 0) {
			throw new InvalidEventFilterException("'page' must be 0 or greater");
		}
		if (size < 1 || size > PageResponse.MAX_SIZE) {
			throw new InvalidEventFilterException("'size' must be between 1 and " + PageResponse.MAX_SIZE);
		}
		long total = repository.count(me, direction, status);
		List<LinkRequestResponse> items = repository.findPage(me, direction, status, size, (long) page * size).stream()
				.map(this::toResponse).toList();
		return PageResponse.of(items, page, size, total);
	}

	private LinkRequestResponse view(UUID requestId, UUID me) {
		return repository.findOne(requestId, me).map(this::toResponse).orElseThrow(LinkRequestException::notFound);
	}

	private LinkRequestResponse toResponse(Row row) {
		return new LinkRequestResponse(row.id(), row.status(), row.direction(), local(row.createdAt()),
				local(row.respondedAt()), row.eventId(), row.eventTitle(),
				new LinkRequestUserResponse(row.otherId(), row.otherUsername(), row.otherDisplayName(), row.otherAvatarUrl()));
	}

	private OffsetDateTime local(OffsetDateTime value) {
		return value == null ? null : value.atZoneSameInstant(clock.getZone()).toOffsetDateTime();
	}

}
