package com.linkr.backend.user;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.linkrequest.LinkRequestRepository;
import com.linkr.backend.linkrequest.LinkState;
import com.linkr.backend.linkrequest.LinkStatus;
import com.linkr.backend.people.dto.SharedInterestResponse;
import com.linkr.backend.upload.EventImageStorage;
import com.linkr.backend.user.dto.DiscoveryResponse;
import com.linkr.backend.user.dto.PublicProfileResponse;
import com.linkr.backend.user.dto.UpdateProfileRequest;
import com.linkr.backend.user.dto.UserResponse;

@Service
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository userRepository;
	private final UserMapper userMapper;
	private final ProfileRepository profileRepository;
	private final LinkRequestRepository linkRequestRepository;
	private final EventImageStorage imageStorage;

	public UserService(UserRepository userRepository, UserMapper userMapper, ProfileRepository profileRepository,
			LinkRequestRepository linkRequestRepository, EventImageStorage imageStorage) {
		this.userRepository = userRepository;
		this.userMapper = userMapper;
		this.profileRepository = profileRepository;
		this.linkRequestRepository = linkRequestRepository;
		this.imageStorage = imageStorage;
	}

	/**
	 * Aggiorna nome, bio e avatar dell'utente. Valida e pulisce TUTTO prima di scrivere (400 con l'elenco dei campi).
	 * L'avatar deve essere un file caricato davvero con POST /api/uploads/event-images; se non cambia non si ricontrolla,
	 * cosi' un profilo con un avatar precedente resta modificabile. Idempotente.
	 */
	@Transactional
	public UserResponse updateProfile(UUID userId, UpdateProfileRequest request) {
		User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
		Map<String, String> errors = new LinkedHashMap<>();
		String displayName = ProfileText.displayName(request.displayName(), errors);
		String bio = ProfileText.bio(request.bio(), errors);
		String avatarUrl = request.avatarUrl();
		if (avatarUrl != null && !avatarUrl.equals(user.getAvatarUrl())
				&& !EventImageStorage.filenameOf(avatarUrl).map(imageStorage::exists).orElse(false)) {
			errors.put("avatarUrl", "must reference an image uploaded through the upload endpoint");
		}
		if (!errors.isEmpty()) {
			throw new InvalidProfileException(errors);
		}
		user.changeProfile(displayName, bio, avatarUrl);
		userRepository.saveAndFlush(user);
		return userMapper.toResponse(user);
	}

	/** Profilo di un'altra persona (o il proprio), solo se le regole di ProfileRepository.findVisible lo permettono: altrimenti 404. */
	public PublicProfileResponse getPublicProfile(UUID viewerId, UUID targetId) {
		if (!userRepository.existsById(viewerId)) {
			throw new UserNotFoundException();
		}
		var row = (viewerId.equals(targetId) ? profileRepository.findSelf(targetId) : profileRepository.findVisible(viewerId, targetId))
				.orElseThrow(ProfileNotFoundException::new);
		List<SharedInterestResponse> interests = profileRepository.interestsOf(targetId);
		List<SharedInterestResponse> shared = List.of();
		LinkStatus linkStatus = LinkStatus.NONE;
		UUID linkRequestId = null;
		if (!viewerId.equals(targetId)) {
			Set<UUID> mine = profileRepository.interestsOf(viewerId).stream().map(SharedInterestResponse::id).collect(Collectors.toSet());
			shared = interests.stream().filter(i -> mine.contains(i.id())).toList();
			LinkState link = linkRequestRepository.findStates(viewerId, List.of(targetId)).get(targetId);
			if (link != null) {
				linkStatus = link.status();
				linkRequestId = link.requestId();
			}
		}
		return new PublicProfileResponse(row.userId(), row.username(), row.displayName(), row.bio(), row.avatarUrl(),
				interests, shared, linkStatus, linkRequestId);
	}

	/** Opt-in/opt-out di Discover People. Idempotente: ripetere la stessa richiesta da' lo stesso risultato. */
	@Transactional
	public DiscoveryResponse setDiscoverable(UUID userId, boolean discoverable) {
		User user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
		user.changeDiscoverable(discoverable);
		userRepository.saveAndFlush(user);
		return new DiscoveryResponse(user.isDiscoverable());
	}

	public UserResponse getProfile(UUID userId) {
		return userRepository.findById(userId)
				.map(userMapper::toResponse)
				.orElseThrow(UserNotFoundException::new);
	}

}
