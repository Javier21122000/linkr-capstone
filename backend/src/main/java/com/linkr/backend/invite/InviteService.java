package com.linkr.backend.invite;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.event.Event;
import com.linkr.backend.event.EventNotFoundException;
import com.linkr.backend.event.EventRepository;
import com.linkr.backend.event.EventService;
import com.linkr.backend.event.EventStatus;
import com.linkr.backend.invite.InviteTokenService.IssuedToken;
import com.linkr.backend.invite.dto.InviteResponse;
import com.linkr.backend.linkrequest.LinkRequestRepository;
import com.linkr.backend.linkrequest.LinkRequestService;
import com.linkr.backend.linkrequest.LinkState;
import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.linkrequest.dto.LinkRequestResponse;
import com.linkr.backend.participation.EventEndedException;
import com.linkr.backend.user.User;
import com.linkr.backend.user.UserNotFoundException;
import com.linkr.backend.user.UserRepository;

/**
 * Inviti: "evento condiviso da [nome]". Il link base dell'evento non cambia e funziona sempre; il token (`?i=`) e' un di piu'.
 *
 * Regole (tutte applicate qui, mai fidandosi del client):
 *  - il token lo emette solo chi e' autenticato e solo a proprio nome: il mittente e' sempre il soggetto del JWT;
 *  - l'identita' del mittente (nome visualizzato e avatar) si rivela solo a chi e' autenticato, solo se il mittente e' VISIBILE
 *    (Discover People) e solo finche' l'evento e' pubblicato e non finito. Mai email, username, bio o interessi;
 *  - chi guarda non puo' essere il mittente stesso; ogni caso non utilizzabile e' lo stesso 404 ({@link InviteNotFoundException});
 *  - la richiesta Link e' un gesto esplicito e passa dalle regole di {@link LinkRequestService} (anti-spam, una per coppia).
 */
@Service
public class InviteService {

	private final InviteTokenService tokens;
	private final EventRepository eventRepository;
	private final UserRepository userRepository;
	private final LinkRequestRepository linkRequestRepository;
	private final LinkRequestService linkRequestService;
	private final Clock clock;

	public InviteService(InviteTokenService tokens, EventRepository eventRepository, UserRepository userRepository,
			LinkRequestRepository linkRequestRepository, LinkRequestService linkRequestService, Clock clock) {
		this.tokens = tokens;
		this.eventRepository = eventRepository;
		this.userRepository = userRepository;
		this.linkRequestRepository = linkRequestRepository;
		this.linkRequestService = linkRequestService;
		this.clock = clock;
	}

	/** Emette il token di chi condivide: evento pubblicato e non finito (altrimenti 404 / 409 come per le partecipazioni). */
	@Transactional(readOnly = true)
	public IssuedToken issue(UUID userId, UUID eventId) {
		if (!userRepository.existsById(userId)) {
			throw new UserNotFoundException();
		}
		Event event = eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
				.orElseThrow(() -> new EventNotFoundException(eventId));
		if (EventService.hasEnded(event, clock.instant())) {
			throw new EventEndedException();
		}
		return tokens.issue(eventId, userId);
	}

	@Transactional(readOnly = true)
	public InviteResponse resolve(String token, UUID viewerId) {
		InviteClaims claims = requireUsable(token, viewerId);
		User sender = requireVisibleSender(claims);
		LinkState state = linkRequestRepository.findStates(viewerId, List.of(claims.senderId())).get(claims.senderId());
		return new InviteResponse(claims.eventId(), new InviteResponse.Sender(sender.getDisplayName(), sender.getAvatarUrl()),
				state == null ? LinkStatus.NONE : state.status(), state == null ? null : state.requestId());
	}

	@Transactional
	public LinkRequestResponse sendLinkRequest(String token, UUID viewerId) {
		InviteClaims claims = requireUsable(token, viewerId);
		requireVisibleSender(claims);
		return linkRequestService.sendFromInvite(viewerId, claims.senderId(), claims.eventId());
	}

	/** Firma e scadenza valide, evento ancora pubblicato e non finito, chi guarda diverso dal mittente. */
	private InviteClaims requireUsable(String token, UUID viewerId) {
		InviteClaims claims = tokens.verify(token).orElseThrow(InviteNotFoundException::new);
		if (claims.senderId().equals(viewerId)) {
			throw new InviteNotFoundException();
		}
		Event event = eventRepository.findByIdAndStatus(claims.eventId(), EventStatus.PUBLISHED)
				.orElseThrow(InviteNotFoundException::new);
		if (EventService.hasEnded(event, clock.instant())) {
			throw new InviteNotFoundException();
		}
		return claims;
	}

	private User requireVisibleSender(InviteClaims claims) {
		return userRepository.findById(claims.senderId()).filter(User::isDiscoverable).orElseThrow(InviteNotFoundException::new);
	}

}
