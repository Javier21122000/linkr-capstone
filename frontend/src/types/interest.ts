export interface Interest {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly sortOrder: number;
}

export interface InterestCategory {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly sortOrder: number;
  readonly interests: readonly Interest[];
}

export interface UserInterest {
  readonly id: string;
  readonly slug: string;
  readonly name: string;
  readonly categoryId: string;
  readonly categorySlug: string;
}

export interface UserInterestsResponse {
  readonly interests: readonly UserInterest[];
}

export interface UpdateUserInterestsRequest {
  readonly interestIds: readonly string[];
}
