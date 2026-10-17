package com.linkr.backend.interest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.interest.dto.UserInterestResponse;
import com.linkr.backend.interest.dto.UserInterestsResponse;
import com.linkr.backend.user.UserNotFoundException;
import com.linkr.backend.user.UserRepository;

@Service
public class UserInterestService {

	private final UserRepository userRepository;
	private final InterestRepository interestRepository;
	private final UserInterestRepository userInterestRepository;

	public UserInterestService(UserRepository userRepository, InterestRepository interestRepository,
			UserInterestRepository userInterestRepository) {
		this.userRepository = userRepository;
		this.interestRepository = interestRepository;
		this.userInterestRepository = userInterestRepository;
	}

	@Transactional(readOnly = true)
	public UserInterestsResponse getInterests(UUID userId) {
		return toResponse(interestRepository.findByUserId(userId));
	}

	/**
	 * Sostituisce la selezione dell'utente. Prima si valida TUTTO (nessuna scrittura se qualcosa non va),
	 * poi si cancella la selezione precedente e si inserisce la nuova: tutto in una sola transazione,
	 * quindi o cambia tutto o non cambia niente. Ripetere la stessa richiesta da' sempre lo stesso risultato.
	 */
	@Transactional
	public UserInterestsResponse replaceInterests(UUID userId, List<UUID> interestIds) {
		if (!userRepository.existsById(userId)) {
			throw new UserNotFoundException();
		}

		Set<UUID> unique = new HashSet<>(interestIds);
		if (unique.size() != interestIds.size()) {
			throw new InvalidInterestSelectionException("The same interest cannot be selected more than once.", List.of());
		}
		if (!unique.isEmpty()) {
			// Una categoria non e' un interesse: il suo id non e' in "interest", quindi risulta sconosciuto.
			Set<UUID> existing = new HashSet<>(interestRepository.findExistingIds(unique));
			List<UUID> unknown = interestIds.stream().filter(id -> !existing.contains(id)).toList();
			if (!unknown.isEmpty()) {
				throw new InvalidInterestSelectionException("Some interest ids do not exist.", unknown);
			}
		}

		userInterestRepository.deleteAllByUserId(userId);
		userInterestRepository.saveAll(interestIds.stream().map(id -> new UserInterest(userId, id)).toList());
		userInterestRepository.flush();
		return toResponse(interestRepository.findByUserId(userId));
	}

	private UserInterestsResponse toResponse(List<Interest> interests) {
		return new UserInterestsResponse(interests.stream()
				.map(i -> new UserInterestResponse(i.getId(), i.getSlug(), i.getName(),
						i.getCategory().getId(), i.getCategory().getSlug()))
				.toList());
	}

}
