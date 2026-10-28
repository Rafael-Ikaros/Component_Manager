import { useEffect, useId, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, postJson, previewOrderMetadata } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { createRequestId } from "@/lib/request-id";
import { metadataFields, retryOrderItems, selectedOrderItems, type MetadataField, type OrderApplyItem, type OrderPreview, type OrderResult } from "@/lib/order-metadata";

export function OrderMetadataPanel({ enabled, onChanged }: { enabled: boolean; onChanged: () => void }) {
  const { locale } = useI18n();
  const text = (zh: string, en: string) => locale === "zh-CN" ? zh : en;
  const { session, logout } = useAuth();
  const fileId = useId();
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [preview, setPreview] = useState<OrderPreview | null>(null);
  const [selection, setSelection] = useState<Record<number, MetadataField[]>>({});
  const [pending, setPending] = useState<OrderApplyItem[] | null>(null);
  const [result, setResult] = useState<OrderResult | null>(null);
  const [busy, setBusy] = useState<"preview" | "apply" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const inFlight = useRef(false);
  const changed = useRef(onChanged);
  changed.current = onChanged;
  useEffect(() => () => { generation.current++; controller.current?.abort(); }, []);
  useEffect(() => { generation.current++; controller.current?.abort(); setOpen(false); setFile(null); setPreview(null); setPending(null); setResult(null); setBusy(null); inFlight.current = false; }, [session]);

  function discard() {
    if ((preview || pending || busy) && !window.confirm(text("关闭或更换文件会清除当前预览及重试记录。继续？", "Closing or changing the file clears the preview and retry record. Continue?"))) return false;
    generation.current++; controller.current?.abort(); setPreview(null); setPending(null); setResult(null); setSelection({}); setError(null); setBusy(null); return true;
  }
  function fail(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) logout();
    setError(cause instanceof Error ? cause.message : text("请求失败，请重试。", "Request failed. Please retry."));
  }
  async function loadPreview() {
    if (!file || !session || inFlight.current) return;
    if (pending && !window.confirm(text("重新预览会清除当前重试记录。继续？", "A new preview clears the retry record. Continue?"))) return;
    const token = ++generation.current;
    controller.current?.abort(); controller.current = new AbortController();
    setBusy("preview"); setError(null); setPreview(null); setResult(null); setPending(null);
    try {
      const next = await previewOrderMetadata<OrderPreview>(session, file, controller.current.signal);
      if (token !== generation.current) return;
      setPreview(next);
      setSelection(Object.fromEntries(next.rows.map((row, index) => [index, row.status === "ready" ? metadataFields.filter(field => row.changes[field]) : []])));
    } catch (cause) { if (token === generation.current && !controller.current?.signal.aborted) fail(cause); }
    finally { if (token === generation.current) setBusy(null); }
  }
  async function apply() {
    if (!session || !enabled || !preview || inFlight.current) return;
    const items = pending ?? selectedOrderItems(preview.rows, selection, createRequestId);
    if (!items.length || !window.confirm(text(`确认更新 ${items.length} 个元件的已选资料？库存、名称、库位、图片和备注保持不变。`, `Update selected details for ${items.length} components? Stock, names, locations, photos and notes stay unchanged.`))) return;
    const token = generation.current;
    inFlight.current = true; setBusy("apply"); setError(null); setPending(items);
    try {
      const next = await postJson<OrderResult>(session, "/admin-api/components/order-metadata/apply", { items });
      if (next.summary.updated > 0) changed.current();
      if (token !== generation.current) return;
      setResult(next); setPending(retryOrderItems(items, next));
    } catch (cause) {
      if (token === generation.current) { fail(cause); if (cause instanceof ApiError && cause.status === 409) setPending([]); }
    } finally { inFlight.current = false; if (token === generation.current) setBusy(null); }
  }
  const label = (field: MetadataField) => field === "category" ? text("分类", "Category") : field === "package_name" ? text("封装", "Package") : text("参数组", "Parameter group");
  const display = (value: string | Record<string, string>) => typeof value === "string" ? value || "—" : Object.entries(value).map(([key, val]) => `${key}: ${val}`).join("; ") || "—";
  const selectedCount = preview ? preview.rows.filter((row, index) => row.status === "ready" && (selection[index]?.length ?? 0) > 0).length : 0;
  const status = (value: string) => ({ready: text("可更新", "Ready"), unchanged: text("无变化", "Unchanged"), unmatched: text("未匹配", "Unmatched"), conflict: text("冲突", "Conflict"), updated: text("已更新", "Updated"), failed: text("失败", "Failed")})[value] ?? value;
  return <Card className="min-w-0 bg-white/90">
    <CardHeader><CardTitle className="flex flex-wrap items-center justify-between gap-3"><span>{text("从订单更新资料", "Update details from an order")}</span><Button variant="outline" disabled={busy === "apply"} onClick={() => { if (!open) setOpen(true); else if (discard()) {setOpen(false); setFile(null);} }}>{open ? text("关闭", "Close") : text("打开", "Open")}</Button></CardTitle></CardHeader>
    {open && <CardContent className="min-w-0 space-y-4" aria-busy={busy !== null}>
      <p className="text-sm text-muted-foreground">{text("请先同步设备到服务端，再上传立创商城 .xls 订单。仅更新分类、封装和表内明确参数；保留已有名称、库存、库位、图片和备注。服务端更新成功后，请在设备上再次同步。", "Sync devices to the server first, then upload an LCSC .xls order. Only category, package and explicit order parameters change; existing names, stock, locations, photos and notes are preserved. Sync devices again after the server update.")}</p>
      {!enabled && <p className="text-sm text-amber-800">{text("Web 库存操作未启用：可以预览，不能应用更新。", "Web inventory editing is disabled: preview is available, applying changes is disabled.")}</p>}
      <div className="flex flex-wrap items-end gap-3"><div className="min-w-0 flex-1 space-y-2"><label htmlFor={fileId} className="text-sm font-medium">{text("订单文件（.xls）", "Order file (.xls)")}</label><Input id={fileId} type="file" accept=".xls" disabled={busy === "apply"} onChange={event => { const next = event.target.files?.[0] ?? null; event.target.value = ""; if (!discard()) return; setFile(next); }} /><p className="break-all text-sm">{file?.name ?? text("尚未选择文件", "No file selected")}</p></div><Button disabled={!file || busy !== null} onClick={() => void loadPreview()}>{busy === "preview" ? text("正在生成预览…", "Preparing preview…") : text("生成只读预览", "Generate read-only preview")}</Button>{busy === "preview" && <Button variant="outline" onClick={() => {generation.current++; controller.current?.abort(); setBusy(null);}}>{text("取消上传", "Cancel upload")}</Button>}</div>
      {error && <Alert variant="destructive"><AlertTitle>{text("无法完成请求", "Request could not be completed")}</AlertTitle><AlertDescription>{error}</AlertDescription></Alert>}
      {preview && <div className="space-y-3">
        <p className="break-all text-sm font-medium">{preview.filename}</p>
        <p className="text-sm" role="status">{Object.entries(preview.summary).map(([key, count]) => `${status(key)}: ${count}`).join(" · ")}</p>
        {!preview.rows.length && <p className="rounded-lg border border-dashed p-4 text-sm">{text("订单中没有可预览的元件。", "No components to preview in this order.")}</p>}
        <div className="max-h-[34rem] space-y-3 overflow-y-auto">
          {preview.rows.map((row, index) => <article key={`${row.sku}-${index}`} className="min-w-0 space-y-3 rounded-xl border p-4">
            <div className="flex flex-wrap items-start justify-between gap-2"><div className="min-w-0"><p className="break-words font-medium">{row.sku} · {row.name}</p><p className="text-xs text-muted-foreground">{text("订单行", "Order rows")}: {row.source_rows.join(", ")}</p></div><span className="text-sm">{status(row.status)}</span></div>
            {row.messages.map((message, i) => <p key={i} className="break-words text-sm text-amber-800">{message}</p>)}
            {metadataFields.map(field => { const change = row.changes[field]; return change && <label key={field} className="flex min-h-11 items-start gap-3 rounded-lg bg-slate-50 p-3"><input className="mt-1 h-4 w-4 shrink-0" type="checkbox" checked={selection[index]?.includes(field) ?? false} disabled={row.status !== "ready" || busy !== null || pending !== null || result !== null} onChange={event => setSelection(previous => ({...previous, [index]: event.target.checked ? [...(previous[index] ?? []), field] : (previous[index] ?? []).filter(value => value !== field)}))} /><span className="min-w-0 text-sm"><span className="font-medium">{label(field)}</span><span className="mt-1 block break-words text-muted-foreground">{text("当前", "Current")}: {display(change.before)}</span><span className="mt-1 block break-words">{text("更新为", "Proposed")}: {display(change.after)}</span></span></label>; })}
          </article>)}
        </div>
        <div className="flex flex-wrap items-center gap-3"><Button disabled={!enabled || busy !== null || (pending ? pending.length === 0 : result !== null || selectedCount === 0)} onClick={() => void apply()}>{busy === "apply" ? text("正在更新…", "Updating…") : pending?.length ? text("重试未确认或失败项（相同请求 ID）", "Retry uncertain or failed items (same request IDs)") : text(`确认更新 ${selectedCount} 项`, `Confirm ${selectedCount} updates`)}</Button><span className="text-xs text-muted-foreground">{text("冲突项请重新预览后选择，不会自动重试。", "Preview conflicts again before selecting; conflicts are never automatically retried.")}</span></div>
      </div>}
      {result && <div className="space-y-2 rounded-xl border p-4" role="status"><p className="font-medium">{Object.entries(result.summary).map(([key, count]) => `${status(key)}: ${count}`).join(" · ")}</p>{result.items.map((item, index) => <p key={index} className="break-words text-sm">{item.sku} · {status(item.status)} · {item.message}</p>)}{result.summary.updated > 0 && <p className="text-sm text-emerald-800">{text("服务端已更新，请在设备上再次同步。", "Server updated. Sync your devices again.")}</p>}</div>}
    </CardContent>}
  </Card>;
}
