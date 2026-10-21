import { useLinkRequestCount } from "../../context/LinkRequestCountContext";
import { CountBadge } from "../ui/CountBadge";

export function IncomingLinkCountBadge() {
  const { incomingCount } = useLinkRequestCount();
  return <CountBadge count={incomingCount} />;
}
