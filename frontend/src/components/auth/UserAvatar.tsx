import { useEffect, useState } from "react";

import { resolveEventImageUrl } from "../../lib/eventImages";

interface UserAvatarProps {
  readonly displayName: string;
  readonly avatarUrl: string | null;
  readonly className?: string;
}

function getInitials(displayName: string): string {
  const words = displayName.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "L";
  return `${words[0]?.[0] ?? ""}${words.length > 1 ? words.at(-1)?.[0] ?? "" : ""}`.toUpperCase();
}

export function UserAvatar({ displayName, avatarUrl, className = "size-20" }: UserAvatarProps) {
  const [imageFailed, setImageFailed] = useState(false);

  useEffect(() => setImageFailed(false), [avatarUrl]);

  if (avatarUrl && !imageFailed) {
    return (
      <img
        src={resolveEventImageUrl(avatarUrl)}
        alt={`Foto profilo di ${displayName}`}
        className={`${className} shrink-0 rounded-full border border-line object-cover`}
        onError={() => setImageFailed(true)}
      />
    );
  }

  return (
    <span
      className={`${className} grid shrink-0 place-items-center rounded-full bg-linkr-soft font-extrabold text-linkr-strong`}
      role="img"
      aria-label={`Avatar con iniziali di ${displayName}`}
    >
      {getInitials(displayName)}
    </span>
  );
}
