/** CNY input uses at most eight decimal places; never pass money through Number. */
export function purchaseAmount(value: string, quantity: number, mode: "total" | "unit"): string | null {
  const input = value.trim();
  if (!input) return null;
  if (!/^\d+(?:\.\d{1,8})?$/.test(input)) throw new Error("金额必须为非负数，最多8位小数。");
  if (!Number.isSafeInteger(quantity) || quantity <= 0) throw new Error("数量必须为正整数。");
  const [whole, fraction = ""] = input.split(".");
  const scale = 100_000_000n;
  const raw = (BigInt(whole) * scale + BigInt(fraction.padEnd(8, "0"))) * (mode === "unit" ? BigInt(quantity) : 1n);
  const decimal = (raw % scale).toString().padStart(8, "0").replace(/0+$/, "");
  return `${raw / scale}${decimal ? `.${decimal}` : ""}`;
}
