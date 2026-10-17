package com.linkr.backend.user;

import java.time.Clock;

import org.springframework.stereotype.Component;

import com.linkr.backend.user.dto.UserResponse;

@Component
public class UserMapper {

	private final Clock clock;

	public UserMapper(Clock clock) {
		this.clock = clock;
	}

	public UserResponse toResponse(User user) {
		return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getDisplayName(),
				user.getBio(), user.getAvatarUrl(), user.getRole(),
				user.getCreatedAt().atZone(clock.getZone()).toOffsetDateTime());
	}

}
