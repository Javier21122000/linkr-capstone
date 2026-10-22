export const DISPLAY_NAME_MIN = 2;
export const DISPLAY_NAME_MAX = 60;
export const BIO_MAX = 300;

export interface ProfileValidationErrors {
  readonly displayName?: string;
  readonly bio?: string;
}

const FORBIDDEN_PROFILE_TEXT = /[<>\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f\u200b-\u200f\u202a-\u202e\u2060-\u206f\ufeff]/u;

export function cleanDisplayName(value: string): string {
  return value.trim().replace(/\s+/gu, " ");
}

export function cleanBio(value: string): string | null {
  const cleaned = value.trim().replace(/\n(?:[ \t]*\n){2,}/gu, "\n\n");
  return cleaned || null;
}

export function validateProfileDraft(displayName: string, bio: string): ProfileValidationErrors {
  const cleanedName = cleanDisplayName(displayName);
  const cleanedBio = cleanBio(bio);
  const errors: { displayName?: string; bio?: string } = {};

  if (cleanedName.length < DISPLAY_NAME_MIN) {
    errors.displayName = `Inserisci almeno ${DISPLAY_NAME_MIN} caratteri.`;
  } else if (cleanedName.length > DISPLAY_NAME_MAX) {
    errors.displayName = `Il nome non può superare ${DISPLAY_NAME_MAX} caratteri.`;
  } else if (FORBIDDEN_PROFILE_TEXT.test(cleanedName)) {
    errors.displayName = "Il nome contiene caratteri non consentiti.";
  }

  if (cleanedBio && cleanedBio.length > BIO_MAX) {
    errors.bio = `La bio non può superare ${BIO_MAX} caratteri.`;
  } else if (cleanedBio && FORBIDDEN_PROFILE_TEXT.test(cleanedBio)) {
    errors.bio = "La bio contiene caratteri non consentiti.";
  }

  return errors;
}
