// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
// Run from the repository root: node downstream/permission-forms.test.mjs .

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { resolve } from "node:path";

const root = process.argv[2];
assert(root, "Pass the patched EDDIE source directory");
function load(country, globals = {}) {
  const file = resolve(root, `region-connectors/region-connector-${country}/src/main/web/permission-request-form.js`);
  const source = readFileSync(file, "utf8").replace(/^import .*;\n/gm, "")
    .replace("export default PermissionRequestForm;", "globalThis.Form = PermissionRequestForm;");
  const context = vm.createContext({
    PermissionRequestFormBase: class { error(message) { this.notification = message; } },
    html: (parts, ...values) => parts.reduce((out, part, i) => out + part + (values[i] ?? ""), ""),
    nothing: "", ifDefined: value => value, unsafeSVG: value => value, logo: "", URL,
    FormData: class { get(name) { return name === "nif" ? "TESTNIF00" : "TESTCUPS"; } },
    ...globals,
  });
  vm.runInContext(source, context);
  return new context.Form();
}
const event = { preventDefault() {}, target: {} };
const spain = load("es-datadis", { fetch: async () => ({ ok: false, status: 503 }) });
spain.dataNeedId = "history,forward";
let submissions = 0;
spain.createPermissionRequest = async () => { submissions++; return { permissionIds: ["one", "two"] }; };
await spain.handleSubmit(event);
assert.equal(spain._requestCreated, true, "Successful creation must advance without an SSE event");
assert.equal(spain._isSentToPermissionAdministrator, undefined, "Creation must not invent a Datadis status");
assert(spain.render().includes("Open Datadis"));
await spain.handleSubmit(event);
assert.equal(submissions, 1, "Repeated clicks must not create duplicate permissions");
await spain.accepted();
assert.equal(spain._isVerifying, false);
assert.match(spain._errorMessage, /503/);
const failed = load("es-datadis");
failed.dataNeedId = "history";
failed.createPermissionRequest = async () => { throw new Error("Invalid CUPS"); };
await failed.handleSubmit(event);
assert.equal(failed._isSubmitDisabled, false);
assert(failed.render().includes("Invalid CUPS"));

const prefilled = load("es-datadis");
prefilled.dataNeedId = "history,forward";
prefilled.accountingPointId = "PREFILLED-CUPS";
prefilled.customerIdentification = "PREFILLED-NIF";
let prefilledPayload;
prefilled.createPermissionRequest = async payload => {
  prefilledPayload = payload;
  return { permissionIds: ["prefilled"] };
};
await prefilled.handleSubmit(event);
assert.equal(prefilledPayload.meteringPointId, "PREFILLED-CUPS", "Disabled prefilled CUPS must be included");
assert.equal(prefilledPayload.nif, "PREFILLED-NIF", "Disabled prefilled NIF must be included");
assert.equal(Array.from(prefilledPayload.dataNeedIds).join(","), "history,forward");

const manual = load("es-datadis");
manual.dataNeedId = "history";
let manualPayload;
manual.createPermissionRequest = async payload => {
  manualPayload = payload;
  return { permissionIds: ["manual"] };
};
await manual.handleSubmit(event);
assert.equal(manualPayload.meteringPointId, "TESTCUPS", "Manual CUPS remains supported");
assert.equal(manualPayload.nif, "TESTNIF00", "Manual NIF remains supported");

const empty = load("es-datadis");
empty.dataNeedId = "history";
empty.createPermissionRequest = async () => ({ permissionIds: [] });
await empty.handleSubmit(event);
assert.notEqual(empty._requestCreated, true, "Empty creation response must not advance the form");
assert.equal(empty._isSubmitDisabled, false, "Failed creation must permit retry");
assert.match(empty._errorMessage, /did not return a permission request/);

let decisions = 0;
let finishDecision;
const pendingDecision = load("es-datadis", { fetch: url => {
  decisions++;
  assert(url.endsWith("?permission-id=one%2Ctwo"), "Grouped permission IDs must be URL encoded");
  return new Promise(resolveResponse => { finishDecision = resolveResponse; });
} });
pendingDecision.requestUrl = "https://example.com/permission-request";
pendingDecision.permissionId = "one,two";
const acceptance = pendingDecision.accepted();
await pendingDecision.accepted();
assert.equal(decisions, 1, "A pending verification must not be submitted twice");
finishDecision({ ok: true });
await acceptance;
assert.equal(pendingDecision._isVerifying, true, "A successful HTTP response still waits for Datadis verification");

let opened = false;
const popup = { location: {}, closed: false, close() { this.closed = true; } };
const france = load("fr-enedis", { window: { open() { opened = true; return popup; } } });
let requests = 0;
france.createPermissionRequest = async () => {
  requests++;
  assert(opened, "The popup must open synchronously before awaiting the request");
  return { redirectUri: "https://example.com/authorization" };
};
await france.handleSubmit(event);
assert.equal(popup.location.href, "https://example.com/authorization");
await france.handleSubmit(event);
assert.equal(requests, 1, "Retrying the link must reuse the existing permission");
const blocked = load("fr-enedis", { window: { open() { return null; } } });
blocked.createPermissionRequest = france.createPermissionRequest;
await blocked.handleSubmit(event);
assert(blocked.render().includes('href="https://example.com/authorization"'));
assert(blocked.render().includes("Continue to Enedis"));
const errorPopup = { closed: false, close() { this.closed = true; } };
const franceFailed = load("fr-enedis", { window: { open() { return errorPopup; } } });
franceFailed.createPermissionRequest = async () => { throw new Error("Connection unavailable"); };
await franceFailed.handleSubmit(event);
assert.equal(errorPopup.closed, true);
assert.equal(franceFailed._isSubmitting, false);
assert(franceFailed.render().includes("Connection unavailable"));
console.log("PASS: Datadis progresses without SSE, preserves prefills/manual inputs, rejects empty creation, guards duplicate submissions, and shows HTTP errors");
console.log("PASS: Enedis opens before fetch, provides a blocked-popup fallback, and retries safely");

for (const [hint, manual, expected] of [
  [" 571313180400000001 ", null, "571313180400000001"],
  [undefined, "571313180400000002", "571313180400000002"],
  ["", "571313180400000002", "571313180400000002"],
]) {
  const denmark = load("dk-energinet", {
    FormData: class { get(name) { return name === "meteringPoint" ? manual : "synthetic-token"; } },
  });
  denmark.accountingPointId = hint;
  denmark.connectionId = "synthetic-connection";
  denmark.dataNeedId = "synthetic-need";
  let submitted;
  denmark.createPermissionRequest = async payload => { submitted = payload; };
  denmark.handleSubmit(event);
  await Promise.resolve();
  assert.equal(submitted.meteringPoint, expected);
  assert.equal(submitted.refreshToken, "synthetic-token");
  assert.equal(submitted.connectionId, "synthetic-connection");
  assert.equal(submitted.dataNeedId, "synthetic-need");
  assert.equal(denmark._isPermissionRequestCreated, true);
}
console.log("PASS: Energinet retains locked prefilled points and manual entry");

