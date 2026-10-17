package com.linkr.backend.interest;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Chiave composta (user_id, interest_id) della tabella user_interest. */
@Embeddable
public record UserInterestId(
		@Column(name = "user_id") UUID userId,
		@Column(name = "interest_id") UUID interestId) implements Serializable {
}
