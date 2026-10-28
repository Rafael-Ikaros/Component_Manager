import assert from "node:assert/strict";
import test from "node:test";
import { selectedOrderItems, retryOrderItems, type OrderRow, type OrderResult } from "./order-metadata.ts";
const ready: OrderRow = {component_id: "id", sku: "C1", name: "Keep my name", expected_updated_at: "version", status: "ready", messages: [], source_rows: [2], changes: {category: {before: "old", after: "new"}, package_name: {before: "A", after: "B"}, parameters: {before: {}, after: {voltage: "5V"}}}};
test("payload contains only selected metadata and preserves expected version", () => {
  assert.deepEqual(selectedOrderItems([ready], {0: ["parameters", "category"]}, () => "request"), [{component_id: "id", sku: "C1", expected_updated_at: "version", request_id: "request", changes: {parameters: {voltage: "5V"}, category: "new"}}]);
  assert.deepEqual(selectedOrderItems([ready], {}, () => "request"), []);
});
test("unmatched, conflicting, unchanged and versionless rows cannot be applied", () => {
  for (const status of ["unmatched", "conflict", "unchanged"] as const) assert.deepEqual(selectedOrderItems([{...ready, status}], {0: ["category"]}, () => "request"), []);
  assert.deepEqual(selectedOrderItems([{...ready, expected_updated_at: null}], {0: ["category"]}, () => "request"), []);
});
test("retry retains exact failed payload and IDs and excludes conflicts and successes", () => {
  const items = selectedOrderItems([ready, {...ready, component_id: "conflict"}, {...ready, component_id: "done"}], {0: ["category"], 1: ["category"], 2: ["category"]}, () => "stable-request");
  const result: OrderResult = {items: [{component_id: "id", sku: "C1", status: "failed", message: "retry"}, {component_id: "conflict", sku: "C1", status: "conflict", message: "preview"}, {component_id: "done", sku: "C1", status: "updated", message: "ok"}], summary: {failed: 1, conflict: 1, updated: 1, unchanged: 0}};
  assert.deepEqual(retryOrderItems(items, result), [items[0]]);
  assert.equal(retryOrderItems(items, result)[0], items[0]);
});
