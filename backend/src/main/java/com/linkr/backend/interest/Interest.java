package com.linkr.backend.interest;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Interesse specifico (es. Techno): l'unita' usata per il futuro matching. Il catalogo si gestisce con le migrazioni. */
@Entity
@Table(name = "interest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Interest {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id", nullable = false)
	private InterestCategory category;

	@Column(nullable = false, length = 50)
	private String slug;

	@Column(nullable = false, length = 60)
	private String name;

	@Column(nullable = false)
	private int sortOrder;

}
