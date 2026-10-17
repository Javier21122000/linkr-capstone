package com.linkr.backend.user;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Utente di LINKR (tabella app_user, vedi V2). Non va mai restituito direttamente dalle API: si usa UserResponse.
 * Il ruolo non si sceglie dall'esterno: create() produce sempre un USER.
 */
@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // richiesto da JPA
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 30)
	private String username;

	@Column(nullable = false, length = 254)
	private String email;

	@Column(nullable = false, length = 100)
	private String passwordHash;

	@Column(nullable = false, length = 60)
	private String displayName;

	@Column(length = 500)
	private String bio;

	@Column(length = 500)
	private String avatarUrl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	@CreationTimestamp
	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(nullable = false)
	private Instant updatedAt;

	/** Nuovo utente standard. L'email deve essere gia' normalizzata (minuscola) e la password gia' cifrata. */
	public static User create(String username, String email, String passwordHash, String displayName) {
		User user = new User();
		user.username = username;
		user.email = email;
		user.passwordHash = passwordHash;
		user.displayName = displayName;
		user.role = UserRole.USER;
		return user;
	}

}
