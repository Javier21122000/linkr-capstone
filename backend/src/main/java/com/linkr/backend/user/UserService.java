package com.linkr.backend.user;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.user.dto.UserResponse;

@Service
@Transactional(readOnly = true)
public class UserService {

	private final UserRepository userRepository;
	private final UserMapper userMapper;

	public UserService(UserRepository userRepository, UserMapper userMapper) {
		this.userRepository = userRepository;
		this.userMapper = userMapper;
	}

	public UserResponse getProfile(UUID userId) {
		return userRepository.findById(userId)
				.map(userMapper::toResponse)
				.orElseThrow(UserNotFoundException::new);
	}

}
