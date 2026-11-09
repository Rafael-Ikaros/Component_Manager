import assert from "node:assert/strict";
import test from "node:test";
import { buildCatalogSelections, defaultCatalogSelection, type CatalogRow } from "./catalog-review.ts";

const row: CatalogRow = {
  id: "test-component", sku: "C108661", name: "Resistor", reason: "", status: "ready",
  changes: {
    category: { before: "Custom category", after: "贴片电阻" },
    package_name: { before: "", after: "0603" },
    parameters: { before: { "精度": "±5%" }, after: { "阻值": "33Ω", "精度": "±1%" } },
    reference_price: { before: null, after: { unit_price: "0.01", currency: "CNY" } },
  },
};

test("only missing fields and parameters are selected by default", () => {
  assert.deepEqual(defaultCatalogSelection(row), ["package_name", "parameter:阻值"]);
  assert.deepEqual(defaultCatalogSelection({ ...row, match_source: "mpn" }), []);
});

test("payload carries only explicitly selected parameter keys", () => {
  const selected = { [row.id]: ["parameter:阻值", "parameter:unknown"] };
  assert.deepEqual(buildCatalogSelections([row], selected, new Set(), () => "request-1"), [{
    component_id: row.id, fields: ["parameters"], parameter_keys: ["阻值"], request_id: "request-1",
  }]);
  assert.deepEqual(buildCatalogSelections([{ ...row, status: "skipped" }], selected, new Set(), () => "request-1"), []);
});

test("a manufacturer match cannot be applied before explicit confirmation", () => {
  const candidate: CatalogRow = { ...row, match_source: "mpn" };
  const selected = { [candidate.id]: ["package_name"] };
  assert.deepEqual(buildCatalogSelections([candidate], selected, new Set(), () => "request-1"), []);
  assert.deepEqual(buildCatalogSelections([candidate], selected, new Set([candidate.id]), () => "request-1"), [{
    component_id: candidate.id, fields: ["package_name"], request_id: "request-1", confirm_match: true,
  }]);
});
