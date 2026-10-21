import assert from "node:assert/strict";
import { test } from "node:test";

import { isNearChatBottom, shouldPinNewMessage } from "../src/lib/chatLayout.ts";

test("riconosce il fondo della conversazione con una piccola tolleranza", () => {
  assert.equal(isNearChatBottom({ scrollHeight: 2_000, scrollTop: 1_320, clientHeight: 600 }), true);
  assert.equal(isNearChatBottom({ scrollHeight: 2_000, scrollTop: 1_000, clientHeight: 600 }), false);
});

test("un messaggio ricevuto non interrompe la lettura dei messaggi precedenti", () => {
  assert.equal(shouldPinNewMessage(false, false), false);
  assert.equal(shouldPinNewMessage(false, true), true);
  assert.equal(shouldPinNewMessage(true, false), true);
});
