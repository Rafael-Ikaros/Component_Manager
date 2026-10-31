export type ComponentParameter = { label: string; value: string };

const englishLabels: Record<string, string> = {
  "阻值": "Resistance", "容量": "Capacitance", "电感量": "Inductance", "精度": "Tolerance",
  "耐压": "Voltage rating", "额定功率": "Power rating", "工作温度": "Operating temperature",
  "温度系数": "Temperature coefficient", "直流电阻": "DC resistance",
};

export function displayParameterLabel(label: string, locale: string): string {
  const normalized = label.normalize("NFKC").trim().toLowerCase().replace(/\s+/g, " ");
  const canonical = labels[normalized] ?? label;
  return locale === "zh-CN" ? canonical : englishLabels[canonical] ?? label;
}

const labels: Record<string, string> = {
  resistance: "阻值", "阻值": "阻值",
  capacitance: "容量", "容量": "容量", "容值": "容量",
  inductance: "电感量", "电感量": "电感量", "电感值": "电感量",
  tolerance: "精度", "精度": "精度", "容差": "精度", "误差": "精度",
  "voltage rating": "耐压", "rated voltage": "耐压", "耐压": "耐压", "额定电压": "耐压",
  "power(watts)": "额定功率", "power rating": "额定功率", "额定功率": "额定功率", "功率": "额定功率",
  "operating temperature": "工作温度", "工作温度": "工作温度",
  "temperature coefficient": "温度系数", "温度系数": "温度系数",
  "dc resistance(dcr)": "直流电阻", "直流电阻": "直流电阻",
};

export function parseComponentParameters(description: string | null | undefined): ComponentParameter[] {
  if (!description) return [];
  const parameters: ComponentParameter[] = [];
  const seen = new Set<string>();
  for (const rawLine of description.split(/[\r\n；]+/)) {
    const line = rawLine.trim();
    const prefix = /^参数[·：:]/u.exec(line);
    if (!prefix) continue;
    const body = line.slice(prefix[0].length);
    const separator = body.search(/[：:]/u);
    if (separator <= 0 || separator === body.length - 1) continue;
    const key = body.slice(0, separator).trim();
    const value = body.slice(separator + 1).trim();
    if (!key || !value) continue;
    const normalizedKey = key.normalize("NFKC").toLowerCase().replace(/\s+/g, " ");
    if (["package", "package/case", "封装"].includes(normalizedKey)) continue;
    const unitKey = /^(.+?)\s*\(([^()]+)\)$/u.exec(key.normalize("NFKC"));
    const baseKey = unitKey?.[1].trim().toLowerCase().replace(/\s+/g, " ");
    const label = labels[normalizedKey] ?? (baseKey ? labels[baseKey] : undefined) ?? key;
    const unit = unitKey?.[2].trim();
    const recognizedUnit = unit && /^(?:[pnumkMµμ]?(?:V|A|W|F|H|Ω)|ohm|%)$/iu.test(unit);
    const displayedValue = label !== key && recognizedUnit && /^[+-]?\d+(?:\.\d+)?$/u.test(value)
      ? `${value}${unit.toLowerCase() === "v" ? "V" : unit}`
      : value;
    const identity = `${label}\0${displayedValue}`;
    if (seen.has(identity)) continue;
    seen.add(identity);
    parameters.push({ label, value: displayedValue });
  }
  return parameters;
}
