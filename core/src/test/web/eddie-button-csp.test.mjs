// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

const source = await readFile(new URL("../../main/js/eddie-connect-button.js", import.meta.url), "utf8");
// Execute the real event methods with dynamic code generation forbidden, like a strict CSP page.
const methods = source.slice(source.indexOf("  handleDialogShow(event) {"), source.indexOf("  simIsEnabled() {"));
assert.ok(methods.includes("addRequestStatusHandlers()"));
function component() {
  const context = vm.createContext({ dialogOpenEvent: "open", dialogCloseEvent: "close" },
    { codeGeneration: { strings: false, wasm: false } });
  return vm.runInContext(`new (class { ${methods} })()`, context);
}

test("dialog events work under strict CSP when optional code handlers are absent", () => {
  const button = component();
  const events = [];
  button.dispatchEvent = event => events.push(event);
  for (const value of [undefined, null, ""]) {
    button.onOpen = value;
    button.onClose = value;
    button.handleDialogShow({ currentTarget: button, target: button });
    button.handleDialogHide({ currentTarget: button, target: button });
  }
  assert.deepEqual(events, ["open", "close", "open", "close", "open", "close"]);
});

test("status events work under strict CSP without optional code handlers", () => {
  const button = component();
  let listener;
  button.addEventListener = (name, callback) => { assert.equal(name, "eddie-request-status"); listener = callback; };
  button.getAttribute = () => null;
  button.addRequestStatusHandlers();
  for (const value of [undefined, null, ""]) {
    button.onStatusChange = value;
    assert.doesNotThrow(() => listener({ detail: { status: "ACCEPTED" } }));
  }
});

test("nested dialog events do not dispatch parent events", () => {
  const button = component();
  button.dispatchEvent = () => assert.fail("nested event must be ignored");
  button.handleDialogShow({ currentTarget: button, target: {} });
  button.handleDialogHide({ currentTarget: button, target: {} });
});
