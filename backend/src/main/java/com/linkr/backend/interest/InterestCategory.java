package com.linkr.backend.interest;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Categoria del catalogo (es. Musica). Serve solo a organizzare la UI: non e' selezionabile come interesse. */
@Entity
@Table(name = "interest_category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InterestCategory {

	@Id
	private UUID id;

	@Column(nullable = false, length = 50)
	private String slug;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(nullable = false)
	private int sortOrder;

}
