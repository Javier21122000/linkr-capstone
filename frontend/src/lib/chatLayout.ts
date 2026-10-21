interface ScrollMetrics {
  readonly scrollHeight: number;
  readonly scrollTop: number;
  readonly clientHeight: number;
}

/** Distanza dal fondo tollerata per considerare l'utente ancora agganciato agli ultimi messaggi. */
export function isNearChatBottom(metrics: ScrollMetrics, threshold = 96): boolean {
  return metrics.scrollHeight - metrics.scrollTop - metrics.clientHeight < threshold;
}

/** I propri messaggi portano sempre al fondo; quelli ricevuti solo se l'utente era già in fondo. */
export function shouldPinNewMessage(mine: boolean, nearBottom: boolean): boolean {
  return mine || nearBottom;
}
