package com.linkr.backend.interest;

import java.util.UUID;

import org.springframework.data.domain.Persistable;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * Associazione utente-interesse (riga di user_interest).
 * Implementa Persistable cosi' Spring Data fa un INSERT diretto, senza una SELECT per ogni riga.
 */
@Entity
@Table(name = "user_interest")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserInterest implements Persistable<UserInterestId> {

	@EmbeddedId
	private UserInterestId id;

	@Transient
	private boolean newEntity = true;

	public UserInterest(UUID userId, UUID interestId) {
		this.id = new UserInterestId(userId, interestId);
	}

	@Override
	public UserInterestId getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return newEntity;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.newEntity = false;
	}

}
