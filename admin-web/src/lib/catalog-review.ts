export type ScanMode = "missing" | "review";
export type CatalogField = "category" | "package_name" | "parameters" | "reference_price";
export type CatalogStatus = "ready" | "unchanged" | "skipped" | "failed" | "pending";

export interface CatalogJob {
  id: string;
  state: string;
  total: number;
  processed: number;
  ready: number;
  skipped: number;
  failed: number;
  unchanged?: number;
  reasons?: Record<string, number>;
  scan_mode?: ScanMode;
  mode?: string;
  language?: "zh-CN" | "en";
}

export interface CatalogRow {
  id: string;
  sku: string;
  name: string;
  status: CatalogStatus;
  reason: string;
  match_source?: "sku" | "mpn";
  match_model?: string;
  match_brand?: string;
  source_url?: string;
  changes: Partial<Record<CatalogField, { before: unknown; after: unknown }>>;
}

export interface CatalogOption {
  id: string;
  field: CatalogField;
  parameter?: string;
  before: unknown;
  after: unknown;
  safe: boolean;
}

export interface CatalogSelection {
  component_id: string;
  fields: CatalogField[];
  request_id: string;
  parameter_keys?: string[];
  confirm_match?: true;
}

const fields: CatalogField[] = ["category", "package_name", "parameters", "reference_price"];

function parameterMap(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function isEmpty(value: unknown): boolean {
  return value == null || (typeof value === "string" && value.trim() === "");
}

export function catalogOptions(row: CatalogRow): CatalogOption[] {
  return fields.flatMap((field): CatalogOption[] => {
    const change = row.changes?.[field];
    if (!change) return [];
    if (field !== "parameters") {
      return [{ id: field, field, ...change, safe: field !== "reference_price" && isEmpty(change.before) }];
    }
    const before = parameterMap(change.before);
    return Object.entries(parameterMap(change.after)).map(([parameter, after]) => ({
      id: `parameter:${parameter}`, field, parameter, before: before[parameter], after, safe: isEmpty(before[parameter]),
    }));
  });
}

export function defaultCatalogSelection(row: CatalogRow): string[] {
  if (row.status !== "ready" || row.match_source === "mpn") return [];
  return catalogOptions(row).filter(option => option.safe).map(option => option.id);
}

export function canSelectCatalogRow(row: CatalogRow, confirmedMatches: ReadonlySet<string>): boolean {
  return row.status === "ready" && (row.match_source !== "mpn" || confirmedMatches.has(row.id));
}

export function buildCatalogSelections(
  rows: CatalogRow[],
  selected: Record<string, string[]>,
  confirmedMatches: ReadonlySet<string>,
  requestId: () => string,
): CatalogSelection[] {
  return rows.flatMap((row): CatalogSelection[] => {
    if (!canSelectCatalogRow(row, confirmedMatches)) return [];
    const choices = selected[row.id] ?? [];
    const options = catalogOptions(row).filter(option => choices.includes(option.id));
    if (!options.length) return [];
    const parameterKeys = options.flatMap(option => option.parameter === undefined ? [] : [option.parameter]);
    return [{
      component_id: row.id,
      fields: [...new Set(options.map(option => option.field))],
      request_id: requestId(),
      ...(parameterKeys.length ? { parameter_keys: parameterKeys } : {}),
      ...(row.match_source === "mpn" ? { confirm_match: true } : {}),
    }];
  });
}
