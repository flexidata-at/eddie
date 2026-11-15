// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";

// Execute the real component without a browser or permission-administrator connection.
// The filtered form data reproduces the browser omission of the disabled point input.
async function loadComponent(file) {
  const context = vm.createContext({
    FormData: class {
      constructor(values) { this.values = values; }
      get(name) { return this.values.get(name) ?? null; }
    },
  });
  let code = await readFile(file, "utf8");
  if (process.env.AT_PERMISSION_FORM_COMPILED === "1") {
    // Test the exact emitted submit method without initializing bundled Lit's DOM runtime.
    const start = code.lastIndexOf("handleSubmit(");
    const end = code.indexOf("render(){", start);
    assert.ok(start >= 0 && end > start, "compiled component submit method must exist");
    code = `export default class { ${code.slice(start, end)} }`;
  }
  const module = new vm.SourceTextModule(code, { context });
  await module.link(async (specifier) => {
    if (specifier === "lit") {
      return new vm.SyntheticModule(["html", "nothing"], function () {
        this.setExport("html", () => undefined);
        this.setExport("nothing", undefined);
      }, { context });
    }
    if (specifier.endsWith("permission-request-form-base.js")) {
      return new vm.SyntheticModule(["default"], function () {
        this.setExport("default", class {});
      }, { context });
    }
    assert.ok(specifier.startsWith("https://cdn.jsdelivr.net/"), specifier);
    return new vm.SyntheticModule([], function () {}, { context });
  });
  await module.evaluate();
  return module.namespace.default;
}

const source = process.env.AT_PERMISSION_FORM_MODULE
  ? new URL(process.env.AT_PERMISSION_FORM_MODULE, "file:///")
  : new URL("../../main/web/permission-request-form.js", import.meta.url);
const Component = await loadComponent(source);
const point = "AT9999990699900000000000206868100";

function submit(pointHint, editablePoint) {
  const component = new Component();
  component.accountingPointId = pointHint;
  component.companyId = "AT999999";
  component.connectionId = "synthetic-connection";
  component._dataNeedIds = ["synthetic-history-4-months"];
  let payload;
  component.createPermissionRequest = (request) => {
    payload = request;
    return Promise.resolve();
  };
  let prevented = false;
  const values = new Map();
  if (editablePoint !== undefined) values.set("meteringPointId", editablePoint);
  component.handleSubmit({ target: values, preventDefault() { prevented = true; } });
  assert.ok(prevented);
  assert.equal(payload.connectionId, "synthetic-connection");
  assert.deepEqual(Array.from(payload.dataNeedIds), ["synthetic-history-4-months"]);
  return payload;
}

test("locked prefilled point survives its disabled input being omitted from FormData", () => {
  assert.equal(submit(point, undefined).meteringPointId, point);
});

test("manual point remains usable without a prefilled point", () => {
  assert.equal(submit(undefined, point).meteringPointId, point);
});

test("empty service hint leaves manual point entry available", () => {
  assert.equal(submit("", point).meteringPointId, point);
});

test("optional point stays null when neither service nor user provides one", () => {
  assert.equal(submit(undefined, undefined).meteringPointId, null);
});

test("editable form values cannot override the locked service-provided point", () => {
  assert.equal(submit(point, "AT9999990699900000000000206868101").meteringPointId, point);
});
