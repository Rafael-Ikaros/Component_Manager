export const metadataFields = ["category", "package_name", "parameters"] as const;
export type MetadataField = typeof metadataFields[number];
type Parameters = Record<string, string>;
export interface OrderRow {
  component_id: string | null; sku: string; name: string; expected_updated_at: string | null;
  status: "ready" | "unchanged" | "unmatched" | "conflict"; messages: string[]; source_rows: number[];
  changes: { category?: {before: string; after: string}; package_name?: {before: string; after: string}; parameters?: {before: Parameters; after: Parameters} };
}
export interface OrderPreview { filename: string; rows: OrderRow[]; summary: Record<"ready" | "unchanged" | "unmatched" | "conflict", number> }
export interface OrderApplyItem { component_id: string; sku: string; expected_updated_at: string; request_id: string; changes: { category?: string; package_name?: string; parameters?: Parameters } }
export interface OrderResult { items: {component_id: string; sku: string; status: "updated" | "unchanged" | "conflict" | "failed"; message: string}[]; summary: Record<"updated" | "unchanged" | "conflict" | "failed", number> }
export function selectedOrderItems(rows: OrderRow[], selection: Record<number, MetadataField[]>, requestId: () => string): OrderApplyItem[] {
  return rows.flatMap((row, index) => {
    if (row.status !== "ready" || !row.component_id || !row.expected_updated_at) return [];
    const changes: OrderApplyItem["changes"] = {};
    for (const field of selection[index] ?? []) {
      const change = row.changes[field];
      if (change) Object.assign(changes, { [field]: change.after });
    }
    return Object.keys(changes).length ? [{component_id: row.component_id, sku: row.sku, expected_updated_at: row.expected_updated_at, request_id: requestId(), changes}] : [];
  });
}
export function retryOrderItems(items: OrderApplyItem[], result: OrderResult): OrderApplyItem[] {
  return items.filter(item => result.items.find(row => row.component_id === item.component_id)?.status === "failed");
}
