export interface UserResponse {
  readonly id: string;
  readonly username: string;
  readonly email: string;
  readonly displayName: string;
  readonly bio: string | null;
  readonly avatarUrl: string | null;
  readonly role: "USER" | "ADMIN";
  readonly createdAt: string;
}

export interface AuthResponse {
  readonly accessToken: string;
  readonly tokenType: "Bearer";
  readonly expiresIn: number;
  readonly user: UserResponse;
}

export interface LoginRequest {
  readonly email: string;
  readonly password: string;
}

export interface RegisterRequest extends LoginRequest {
  readonly username: string;
  readonly displayName: string;
}

export interface ProblemDetail {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly field?: string;
  readonly errors?: Readonly<Record<string, string>>;
  readonly unknownIds?: readonly string[];
}
