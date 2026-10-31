import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, postJson, requestJson } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { createRequestId } from "@/lib/request-id";
import { pendingStorageKey } from "@/lib/pending-request";
import { displayParameterLabel } from "@/lib/component-parameters";

type Field = "category" | "package_name" | "parameters" | "reference_price";
interface Job { id: string; state: string; total: number; processed: number; ready: number; skipped: number; failed: number }
interface Row { id: string; sku: string; name: string; status: string; reason: string; changes: Partial<Record<Field, { before: unknown; after: unknown }>> }
interface Rows { page: number; total: number; page_size: number; items: Row[] }
interface Selection { component_id: string; fields: Field[]; request_id: string }
interface Result { items: { component_id: string; status: string; message: string }[]; summary: Record<string, number> }
const root = "/admin-api/components/catalog-metadata/jobs";

export function CatalogMetadataPanel({ enabled, onChanged }: { enabled: boolean; onChanged: () => void }) {
  const { locale } = useI18n();
  const text = (zh: string, en: string) => locale === "zh-CN" ? zh : en;
  const { identity, session, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const [job, setJob] = useState<Job | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [rows, setRows] = useState<Rows | null>(null);
  const [selected, setSelected] = useState<Record<string, Field[]>>({});
  const [pending, setPending] = useState<Selection[] | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const epoch = useRef(0);
  const storageKey = session && identity ? pendingStorageKey(session.apiBaseUrl, "catalog-preview", identity.account_id) : null;
  const running = job?.state === "running" || job?.state === "queued";
  const names: Record<Field, string> = { category: text("分类", "Category"), package_name: text("封装", "Package"), parameters: text("明确参数（整组替换）", "Explicit parameters (replace group)"), reference_price: text("参考价格", "Reference price") };
  const statuses: Record<string, string> = { ready: text("待确认", "Ready"), skipped: text("跳过", "Skipped"), unchanged: text("无差异", "Unchanged"), failed: text("失败", "Failed"), pending: text("待分析", "Pending"), completed: text("分析完成", "Completed"), cancelled: text("已取消", "Cancelled"), running: text("分析中", "Analyzing"), queued: text("排队中", "Queued"), updated: text("已应用", "Applied"), conflict: text("版本冲突，请重新分析", "Version conflict; analyze again") };
  function fail(cause: unknown) { if (cause instanceof ApiError && cause.status === 401) logout(); setError(cause instanceof Error ? cause.message : String(cause)); }
  useEffect(() => {
    const generation = ++epoch.current;
    setJob(null); setJobId(null); setRows(null); setPending(null); setResult(null); setBusy(false); setPage(1); setError(null);
    if (!session || !storageKey) return;
    let saved: string | null = null;
    try { saved = sessionStorage.getItem(storageKey); } catch { /* Session recovery is optional. */ }
    if (saved) setJobId(saved);
    else void requestJson<Job | null>(session, `${root}/active`).then(next => { if (generation === epoch.current && next) { setJob(next); setJobId(next.id); try { sessionStorage.setItem(storageKey, next.id); } catch { /* Current page still tracks the job. */ } } }).catch(cause => { if (generation === epoch.current) fail(cause); });
    return () => { epoch.current++; };
  }, [session, storageKey]);
  useEffect(() => {
    if (!session || !jobId) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const poll = async () => {
      try { const next = await requestJson<Job>(session, `${root}/${jobId}`); if (!active) return; setJob(next); if (["running", "queued"].includes(next.state)) timer = setTimeout(() => void poll(), 1000); }
      catch (cause) { if (active) { fail(cause); if (cause instanceof ApiError && cause.status === 404) { setJobId(null); setJob(null); } } }
    };
    void poll();
    return () => { active = false; if (timer) clearTimeout(timer); };
  }, [session, jobId]);
  useEffect(() => {
    setRows(null); setSelected({}); setResult(null);
    if (!session || !jobId || running || !job) return;
    let active = true;
    void requestJson<Rows>(session, `${root}/${jobId}/rows?page=${page}`).then(next => {
      if (!active) return;
      setRows(next);
      setSelected(Object.fromEntries(next.items.map(row => [row.id, row.status === "ready" ? Object.keys(row.changes) as Field[] : []])));
    }).catch(cause => { if (active) fail(cause); });
    return () => { active = false; };
  }, [session, jobId, page, running, job?.state]);
  async function action(kind: "start" | "retry" | "cancel" | "clear") {
    if (!session || busy || pending) return;
    const generation = epoch.current;
    setBusy(true); setError(null);
    try {
      const next = await postJson<Job>(session, kind === "start" ? root : `${root}/${jobId}/${kind}`, {});
      if (generation !== epoch.current) return;
      if (kind === "clear") { setJob(null); setJobId(null); setRows(null); try { if (storageKey) sessionStorage.removeItem(storageKey); } catch { /* Current page is cleared. */ } }
      else if (kind === "cancel") setJob(next);
      else { setPage(1); setRows(null); setJob(next); setJobId(next.id); setPending(null); setResult(null); try { if (storageKey) sessionStorage.setItem(storageKey, next.id); } catch { /* Current page still tracks the job. */ } }
    } catch (cause) { if (generation === epoch.current) fail(cause); }
    finally { if (generation === epoch.current) setBusy(false); }
  }
  async function apply() {
    if (!session || !jobId || !enabled || busy) return;
    const items = pending ?? (rows?.items ?? []).flatMap(row => (selected[row.id] ?? []).length ? [{ component_id: row.id, fields: selected[row.id], request_id: createRequestId() }] : []);
    if (!items.length || !window.confirm(text(`应用本页 ${items.length} 个元件的已选字段？参数组将按预览整体替换。`, `Apply selected fields for ${items.length} components on this page? Parameter groups will be replaced as previewed.`))) return;
    const generation = epoch.current;
    setBusy(true); setPending(items); setError(null);
    try {
      const next = await postJson<Result>(session, `${root}/${jobId}/apply`, { items });
      if (generation !== epoch.current) return;
      setResult(next);
      const failed = items.filter(item => next.items.find(row => row.component_id === item.component_id)?.status === "failed");
      setPending(failed.length ? failed : null);
      setSelected({});
      onChanged();
    } catch (cause) { if (generation === epoch.current) fail(cause); }
    finally { if (generation === epoch.current) setBusy(false); }
  }
  const value = (field: Field, entry: unknown) => {
    if (typeof entry === "string") return entry || "—";
    if (entry == null || typeof entry !== "object") return "—";
    if (field === "reference_price") {
      const quote = entry as { unit_price?: string; currency?: string; minimum_quantity?: number; queried_at?: string; source_url?: string };
      if (!quote.unit_price || !quote.currency) return text("未获取参考价", "No reference price");
      const at = quote.queried_at ? new Date(quote.queried_at) : null;
      return [
        `${quote.currency} ${quote.unit_price} / ${text("件", "piece")}`,
        quote.minimum_quantity ? text(`${quote.minimum_quantity} 件起适用`, `For ${quote.minimum_quantity}+ pieces`) : text("适用数量未提供", "Applicable quantity not provided"),
        at && Number.isFinite(at.getTime()) ? text(`查询时间：${at.toLocaleString("zh-CN")}`, `Checked: ${at.toLocaleString("en-US")}`) : "",
        quote.source_url ? text(`来源：${quote.source_url}`, `Source: ${quote.source_url}`) : "",
      ].filter(Boolean).join("\n");
    }
    return Object.entries(entry).map(([key, val]) => `${displayParameterLabel(key, locale)}：${String(val)}`).join("\n") || "—";
  };
  return <Card>
    <CardHeader className="flex-row items-center justify-between gap-4"><CardTitle>{text("国内资料批量更新", "Update metadata from JLCEDA China")}</CardTitle><Button variant="outline" onClick={() => setOpen(!open)}>{open ? text("收起", "Collapse") : text("展开", "Expand")}</Button></CardHeader>
    {open && <CardContent className="space-y-4">
      <p className="text-sm text-muted-foreground">{text("分析当前账户全部在库元件，仅有效 C 料号查询嘉立创EDA国内库；分析不写库存。逐页核对并勾选分类、封装、参数组及参考价格后应用。名称、图片、自由备注、数量和库位保留；结果通过同步传到手机和 Windows。", "Analyze all active components in this account using valid C numbers in the JLCEDA China library. Analysis is read-only. Review and select category, package, parameter groups and reference prices page by page. Names, photos, free notes, stock and locations are preserved; changes sync to native clients.")}</p>
      <p className="text-xs text-muted-foreground">{text("参考价格币种依据国内官方界面的 ￥ 标识；显示查询时间，不代表下单实时价。预览暂存于服务器内存，重启后请重新分析。", "CNY reference prices follow the official China UI yuan symbol. Query time is recorded; checkout prices may differ. Previews are kept in server memory; analyze again after a restart.")}</p>
      {!enabled && <p className="text-sm text-muted-foreground">{text("Web 库存写入未启用：可以分析，启用后才能应用。", "Web inventory writes are disabled. Analysis is available; applying requires writes to be enabled.")}</p>}
      <div className="flex flex-wrap gap-2">
        <Button disabled={busy || !!running || !!pending} onClick={() => void action("start")}>{text("分析当前账户全部库存", "Analyze this account's inventory")}</Button>
        {running && <Button variant="outline" disabled={busy} onClick={() => void action("cancel")}>{text("取消分析", "Cancel analysis")}</Button>}
        {job && !running && <><Button variant="outline" disabled={busy || !!pending || (!job.failed && job.processed === job.total)} onClick={() => void action("retry")}>{text("重试失败及未完成项", "Retry failed and pending")}</Button><Button variant="ghost" disabled={busy || !!pending} onClick={() => void action("clear")}>{text("清除预览", "Clear preview")}</Button></>}
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {job && <p role="status" className="text-sm">{statuses[job.state] ?? job.state} · {job.processed}/{job.total} · {text("待确认", "Ready")} {job.ready} · {text("跳过/无差异", "Skipped/unchanged")} {job.skipped} · {text("失败", "Failed")} {job.failed}</p>}
      {rows && <>
        <div className="flex flex-wrap items-center gap-3"><Button variant="outline" disabled={page <= 1 || busy || !!pending} onClick={() => setPage(page - 1)}>{text("上一页", "Previous")}</Button><span>{page} / {Math.max(1, Math.ceil(rows.total / rows.page_size))}</span><Button variant="outline" disabled={page * rows.page_size >= rows.total || busy || !!pending} onClick={() => setPage(page + 1)}>{text("下一页", "Next")}</Button><Button disabled={!enabled || busy || (!pending && !Object.values(selected).some(fields => fields.length))} onClick={() => void apply()}>{pending ? text("重试待确认应用", "Retry pending application") : text("应用本页已选字段", "Apply selected fields on this page")}</Button></div>
        {result && <div role="status" className="space-y-1 text-sm"><p>{Object.entries(result.summary).map(([key, count]) => `${statuses[key] ?? key}: ${count}`).join(" · ")}</p>{result.items.filter(item => ["failed", "conflict"].includes(item.status)).map(item => <p key={item.component_id}>{item.component_id}: {statuses[item.status]} · {item.message}</p>)}</div>}
        <div className="max-h-[36rem] space-y-3 overflow-auto rounded-md border p-3">
          {rows.items.length === 0 && <p>{text("当前账户没有在库元件。", "This account has no active components.")}</p>}
          {rows.items.map(row => <section key={row.id} className="space-y-2 rounded-md border p-3"><h3 className="font-medium">{row.sku} · {row.name} <span className="text-sm text-muted-foreground">{statuses[row.status] ?? row.status}</span></h3>
            {row.reason && <p className="text-sm text-muted-foreground">{row.reason === "invalid_sku" ? text("无有效 C 料号，已跳过", "No valid C number; skipped") : row.reason === "no_exact_match" ? text("无精确匹配", "No exact match") : row.reason === "no_changes" ? text("资料无差异", "No changes") : row.reason}</p>}
            {(Object.entries(row.changes ?? {}) as [Field, { before: unknown; after: unknown }][]).map(([field, change]) => <div key={field} className="space-y-1"><label className="flex min-h-11 items-center gap-2 text-sm"><input type="checkbox" checked={(selected[row.id] ?? []).includes(field)} disabled={busy || !!pending || !!result} onChange={event => setSelected(previous => ({ ...previous, [row.id]: event.target.checked ? [...(previous[row.id] ?? []), field] : (previous[row.id] ?? []).filter(key => key !== field) }))} />{names[field]}</label><div className="grid gap-2 text-xs sm:grid-cols-2"><div><p className="text-muted-foreground">{text("当前", "Current")}</p><pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-muted p-2">{value(field, change.before)}</pre></div><div><p className="text-muted-foreground">{text("拟更新", "Proposed")}</p><pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded bg-muted p-2">{value(field, change.after)}</pre></div></div></div>)}
          </section>)}
        </div>
      </>}
    </CardContent>}
  </Card>;
}
