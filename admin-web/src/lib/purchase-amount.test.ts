import { test } from "node:test";
import assert from "node:assert/strict";
import { purchaseAmount } from "./purchase-amount.ts";

test("purchase total preserves fractions, free gifts and unknown prices", () => {
  assert.equal(purchaseAmount("0.012", 10, "unit"), "0.12");
  assert.equal(purchaseAmount("12.50", 3, "total"), "12.5");
  assert.equal(purchaseAmount("0.00000001", 3, "unit"), "0.00000003");
  assert.equal(purchaseAmount("0", 3, "unit"), "0");
  assert.equal(purchaseAmount("", 3, "total"), null);
  assert.equal(purchaseAmount("12345678901234567890.12345678", 2, "unit"), "24691357802469135780.24691356");
});

test("invalid money is not silently treated as free or rounded", () => {
  for (const value of ["-1", "1e2", "NaN", "0.123456789", "1,000", "1."]) {
    assert.throws(() => purchaseAmount(value, 1, "total"));
  }
  assert.throws(() => purchaseAmount("1", 0, "unit"));
});
