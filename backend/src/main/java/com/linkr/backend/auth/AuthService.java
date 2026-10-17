package com.linkr.backend.auth;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.linkr.backend.auth.dto.AuthResponse;
import com.linkr.backend.auth.dto.LoginRequest;
import com.linkr.backend.auth.dto.RegisterRequest;
import com.linkr.backend.user.User;
import com.linkr.backend.user.UserMapper;
import com.linkr.backend.user.UserRepository;

@Service
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwtService;
	private final UserMapper userMapper;

	/** Hash di una password qualsiasi: serve a far durare uguale il login anche quando l'email non esiste. */
	private final String dummyHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
			UserMapper userMapper) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtService = jwtService;
		this.userMapper = userMapper;
		this.dummyHash = passwordEncoder.encode("not-a-real-password");
	}

	@Transactional
	public AuthResponse register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		String username = request.username().trim();

		if (userRepository.existsByEmail(email)) {
			throw new DuplicateUserException("email");
		}
		if (userRepository.existsByUsernameIgnoreCase(username)) {
			throw new DuplicateUserException("username");
		}

		User user = User.create(username, email, passwordEncoder.encode(request.password()),
				request.displayName().trim());
		try {
			userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			// Due registrazioni identiche simultanee: i vincoli UNIQUE del database fanno da rete di sicurezza.
			throw new DuplicateUserException("email or username");
		}
		return toAuthResponse(user);
	}

	@Transactional(readOnly = true)
	public AuthResponse login(LoginRequest request) {
		User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
		String hash = user != null ? user.getPasswordHash() : dummyHash;
		boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
		if (user == null || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return toAuthResponse(user);
	}

	private AuthResponse toAuthResponse(User user) {
		return new AuthResponse(jwtService.issueToken(user), "Bearer", jwtService.expiresInSeconds(),
				userMapper.toResponse(user));
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

}
