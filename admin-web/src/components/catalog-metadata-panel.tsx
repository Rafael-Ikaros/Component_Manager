import { useEffect, useId, useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, postJson, requestJson } from "@/lib/api";
import {
  buildCatalogSelections, catalogOptions, defaultCatalogSelection,
  type CatalogField, type CatalogJob, type CatalogRow, type CatalogSelection, type CatalogStatus, type ScanMode,
} from "@/lib/catalog-review";
import { displayParameterLabel } from "@/lib/component-parameters";
import { useI18n } from "@/lib/i18n";
import { pendingStorageKey } from "@/lib/pending-request";
import { createRequestId } from "@/lib/request-id";

interface Rows { page: number; total: number; page_size: number; items: CatalogRow[] }
interface ApplyResult {
  items: { component_id: string; status: string; message: string }[];
  summary: Record<string, number>;
}
const root = "/admin-api/components/catalog-metadata/jobs";
const filters = ["all", "ready", "unchanged", "skipped", "failed", "pending"] as const;

function readJobId(key: string): string | null {
  try { return sessionStorage.getItem(key); } catch { return null; }
}

function rememberJobId(key: string, id: string | null) {
  try {
    if (id) sessionStorage.setItem(key, id);
    else sessionStorage.removeItem(key);
  } catch { /* The current tab can still track the scan. */ }
}

export function CatalogMetadataPanel({ enabled, onChanged }: { enabled: boolean; onChanged: () => void }) {
  const { locale } = useI18n();
  const text = (zh: string, en: string) => locale === "zh-CN" ? zh : en;
  const { identity, session, logout } = useAuth();
  const id = useId();
  const dialog = useRef<HTMLDialogElement>(null);
  const epoch = useRef(0);
  const [open, setOpen] = useState(false);
  const [openedOnce, setOpenedOnce] = useState(false);
  const [mode, setMode] = useState<ScanMode>("missing");
  const [job, setJob] = useState<CatalogJob | null>(null);
  const [jobId, setJobId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [filter, setFilter] = useState<"all" | CatalogStatus>("all");
  const [rows, setRows] = useState<Rows | null>(null);
  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const [confirmedMatches, setConfirmedMatches] = useState<Set<string>>(new Set());
  const [resolved, setResolved] = useState<Set<string>>(new Set());
  const [confirmItems, setConfirmItems] = useState<CatalogSelection[]>([]);
  const [pending, setPending] = useState<CatalogSelection[] | null>(null);
  const [result, setResult] = useState<ApplyResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const storageKey = session && identity ? pendingStorageKey(session.apiBaseUrl, "catalog-preview", identity.account_id) : null;
  const running = job?.state === "running" || job?.state === "queued";
  const names: Record<CatalogField, string> = {
    category: text("分类", "Category"), package_name: text("封装", "Package"),
    parameters: text("参数", "Parameters"), reference_price: text("商城参考价", "Catalog reference price"),
  };
  const labels: Record<string, string> = {
    all: text("全部", "All"), ready: text("待复核", "Ready to review"),
    unchanged: text("无待更新资料", "No updates"), skipped: text("跳过", "Skipped"),
    failed: text("查询失败", "Lookup failed"), pending: text("待扫描", "Pending"),
    completed: text("扫描完成", "Scan complete"), cancelled: text("已取消", "Cancelled"),
    running: text("扫描中", "Scanning"), queued: text("排队中", "Queued"),
    updated: text("已应用", "Applied"), conflict: text("库存已变更，需重新扫描", "Inventory changed; scan again"),
  };
  const reasons: Record<string, string> = {
    invalid_sku: text("没有可查询的 C 料号或可核对的完整型号。", "No usable C number or verifiable full model."),
    no_exact_match: text("目录中没有可核对的精确匹配。", "No verifiable exact catalog match."),
    identity_conflict: text("目录候选与已有电气参数冲突，已跳过。", "The catalog candidate conflicts with saved electrical data."),
    no_catalog_data: text("目录匹配到了元件，但没有可用资料。", "The matched part has no usable catalog data."),
    no_changes: text("本次没有发现待更新字段。", "This scan found no fields to update."),
    upstream_rate_limited: text("嘉立创查询限流，请稍后重试。", "The catalog rate limited the lookup. Try again later."),
    upstream_network: text("服务端无法连接嘉立创，请检查容器网络后重试。", "The server could not reach the catalog. Check container networking."),
    upstream_response: text("嘉立创响应无法读取，请稍后重试。", "The catalog response could not be read. Try again later."),
  };

  function fail(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) logout();
    setError(cause instanceof Error ? cause.message : text("请求失败，请重试。", "Request failed. Please retry."));
  }

  useEffect(() => {
    const generation = ++epoch.current;
    setJob(null); setJobId(null); setRows(null); setPending(null); setResult(null);
    setResolved(new Set()); setPage(1); setFilter("all"); setBusy(false); setError(null);
    if (!openedOnce || !session || !storageKey) return;
    const saved = readJobId(storageKey);
    if (saved) setJobId(saved);
    else {
      setBusy(true);
      void requestJson<CatalogJob | null>(session, `${root}/active`).then(next => {
        if (generation !== epoch.current || !next) return;
        setJob(next); setJobId(next.id); rememberJobId(storageKey, next.id);
      }).catch(cause => {
        if (generation !== epoch.current) return;
        if (cause instanceof ApiError && cause.status === 404) {
          setError(text("此 API 版本没有库存资料检查。请将 API 和 Web 管理台配套更新。", "This API has no catalog review. Update the API and Web console together."));
        } else fail(cause);
      }).finally(() => { if (generation === epoch.current) setBusy(false); });
    }
    return () => { epoch.current++; };
  }, [openedOnce, session, storageKey]);

  useEffect(() => {
    if (!session || !jobId || !storageKey) return;
    let live = true;
    let reconnecting = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function poll() {
      try {
        const next = await requestJson<CatalogJob>(session!, `${root}/${jobId}`);
        if (!live) return;
        setJob(next);
        if (reconnecting) { setError(null); reconnecting = false; }
        if (next.state === "running" || next.state === "queued") timer = setTimeout(() => void poll(), 1500);
      } catch (cause) {
        if (!live) return;
        if (cause instanceof ApiError && cause.status === 404) {
          rememberJobId(storageKey!, null);
          setJobId(null); setJob(null);
          setError(text("这次预览已失效，请重新扫描。", "This preview expired. Start a new scan."));
        } else {
          fail(cause);
          if (cause instanceof ApiError && (cause.status === 0 || cause.status >= 500)) {
            reconnecting = true;
            timer = setTimeout(() => void poll(), 4000);
          }
        }
      }
    }
    void poll();
    return () => { live = false; if (timer) clearTimeout(timer); };
  }, [session, jobId, storageKey]);

  const jobState = job?.state;
  useEffect(() => {
    setRows(null); setSelected({}); setConfirmedMatches(new Set());
    if (!session || !jobId || !jobState || jobState === "running" || jobState === "queued") return;
    let live = true;
    const query = new URLSearchParams({ page: String(page) });
    if (filter !== "all") query.set("status", filter);
    void requestJson<Rows>(session, `${root}/${jobId}/rows?${query}`).then(next => {
      if (!live) return;
      setRows(next);
      setSelected(Object.fromEntries(next.items.map(row => [row.id, defaultCatalogSelection(row)])));
    }).catch(cause => { if (live) fail(cause); });
    return () => { live = false; };
  }, [session, jobId, jobState, page, filter]);

  async function action(kind: "start" | "retry" | "cancel" | "clear") {
    if (!session || busy || pending || (kind !== "start" && !jobId)) return;
    const generation = epoch.current;
    setBusy(true); setError(null);
    try {
      const path = kind === "start" ? root : `${root}/${jobId}/${kind}`;
      const body = kind === "start" ? { mode, language: locale === "zh-CN" ? "zh-CN" : "en" } : {};
      const next = await postJson<CatalogJob>(session, path, body);
      if (generation !== epoch.current) return;
      if (kind === "clear") {
        setJob(null); setJobId(null); setRows(null); setResult(null); setResolved(new Set());
        if (storageKey) rememberJobId(storageKey, null);
      } else if (kind === "cancel") setJob(next);
      else {
        setPage(1); setFilter("all"); setRows(null); setJob(next); setJobId(next.id);
        setPending(null); setResult(null); setResolved(new Set());
        if (storageKey) rememberJobId(storageKey, next.id);
      }
    } catch (cause) {
      if (generation === epoch.current) {
        if (cause instanceof ApiError && cause.status === 409 && kind === "start") {
          setError(text("当前账户已有扫描任务。请等任务结束后重试。", "This account already has an active scan. Wait for it to finish."));
        } else fail(cause);
      }
    } finally { if (generation === epoch.current) setBusy(false); }
  }

  function requestApply() {
    const items = pending ?? buildCatalogSelections(
      (rows?.items ?? []).filter(row => !resolved.has(row.id)), selected, confirmedMatches, createRequestId,
    );
    if (!items.length) return;
    setConfirmItems(items);
    dialog.current?.showModal();
  }

  async function apply(items: CatalogSelection[]) {
    if (!session || !jobId || !enabled || busy || !items.length) return;
    const generation = epoch.current;
    dialog.current?.close();
    setBusy(true); setPending(items); setError(null);
    try {
      const next = await postJson<ApplyResult>(session, `${root}/${jobId}/apply`, { items });
      if (generation !== epoch.current) return;
      setResult(next);
      const failed = new Set(next.items.filter(item => item.status === "failed").map(item => item.component_id));
      const retry = items.filter(item => failed.has(item.component_id));
      setPending(retry.length ? retry : null);
      setResolved(previous => new Set([...previous, ...next.items.filter(item => item.status !== "failed").map(item => item.component_id)]));
      if (next.summary.updated > 0 || next.summary.unchanged > 0) onChanged();
    } catch (cause) {
      if (generation !== epoch.current) return;
      fail(cause);
      if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) setPending(null);
    } finally { if (generation === epoch.current) setBusy(false); }
  }

  function value(field: CatalogField, entry: unknown) {
    if (typeof entry === "string") return entry || "—";
    if (field !== "reference_price" || !entry || typeof entry !== "object") return "—";
    const quote = entry as { unit_price?: string; currency?: string; minimum_quantity?: number; queried_at?: string };
    if (!quote.unit_price || !quote.currency) return "—";
    const queried = quote.queried_at ? new Date(quote.queried_at) : null;
    const minimum = quote.minimum_quantity ? text(`${quote.minimum_quantity} 件起`, `From ${quote.minimum_quantity} pieces`) : "";
    return [`${quote.currency} ${quote.unit_price}`, minimum, queried && Number.isFinite(queried.getTime()) ? queried.toLocaleString(locale) : ""].filter(Boolean).join(" · ");
  }

  const selectedCount = (rows?.items ?? []).filter(row =>
    row.status === "ready" && !resolved.has(row.id) && selected[row.id]?.length && (row.match_source !== "mpn" || confirmedMatches.has(row.id)),
  ).length;
  const pageCount = Math.max(1, Math.ceil((rows?.total ?? 0) / (rows?.page_size ?? 50)));
  const scanMode = job?.scan_mode ?? (job?.mode === "missing" || job?.mode === "review" ? job.mode : undefined);
  const fieldLevel = Boolean(scanMode || job?.language);

  return <Card className="min-w-0">
    <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3">
      <CardTitle>{text("库存资料检查", "Inventory data review")}</CardTitle>
      <Button type="button" variant="outline" className="min-h-11" aria-expanded={open} aria-controls={id}
        onClick={() => { setOpen(!open); setOpenedOnce(true); }}>{open ? text("收起", "Collapse") : text("展开", "Expand")}</Button>
    </CardHeader>
    {open && <CardContent id={id} className="min-w-0 space-y-4">
      <p className="text-sm text-muted-foreground">{text("扫描当前账户的有效库存，包括零库存元件。扫描仅生成预览，确认后才将选中的字段同步到客户端。", "Scan active inventory in this account, including zero-stock items. The scan previews changes. Only fields you confirm are saved and synced.")}</p>
      {!enabled && <p className="text-sm text-muted-foreground">{text("当前可扫描和查看差异。启用 Web 库存操作后可应用更新。", "Scans and previews are available. Enable Web inventory writes to apply changes.")}</p>}
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <label className="grid gap-1 text-sm">
          {text("扫描方式", "Scan mode")}
          <select className="min-h-11 rounded-md border bg-background px-3 text-base" value={mode} disabled={busy || running || !!pending} onChange={event => setMode(event.target.value as ScanMode)}>
            <option value="missing">{text("只补缺失", "Fill missing only")}</option>
            <option value="review">{text("复查已有参数", "Review existing data")}</option>
          </select>
        </label>
        <Button className="min-h-11 whitespace-normal" disabled={busy || running || !!pending} onClick={() => void action("start")}>{text("扫描当前账户库存", "Scan this account's inventory")}</Button>
        {running && <Button variant="outline" className="min-h-11" disabled={busy} onClick={() => void action("cancel")}>{text("取消扫描", "Cancel scan")}</Button>}
      </div>
      {error && <Alert variant="destructive"><AlertTitle>{text("资料检查未完成", "Data review incomplete")}</AlertTitle><AlertDescription className="break-words">{error}</AlertDescription></Alert>}
      {job && <div className="space-y-2 rounded-xl border bg-muted/30 p-3 text-sm" role="status">
        <p className="font-medium">{labels[job.state] ?? job.state} · {job.processed}/{job.total} {text("项库存", "inventory items")}</p>
        <p className="text-muted-foreground">{job.language === "en" ? text("来源：国际 EasyEDA", "Source: EasyEDA International") : text("来源：国内嘉立创 EDA", "Source: JLCEDA China")}
          {scanMode ? ` · ${scanMode === "missing" ? text("只补缺失", "Fill missing only") : text("复查已有参数", "Review existing data")}` : ""}
        </p>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          <span>{labels.ready} {job.ready}</span><span>{labels.unchanged} {job.unchanged ?? "—"}</span>
          <span>{labels.skipped} {job.skipped}</span><span>{labels.failed} {job.failed}</span>
        </div>
        {Object.entries(job.reasons ?? {}).filter(([key]) => key !== "no_changes").map(([key, count]) => <p key={key} className="break-words text-xs">{reasons[key] ?? key} · {count}</p>)}
      </div>}
      {job && !running && <div className="flex flex-wrap gap-2">
        <Button variant="outline" className="min-h-11 whitespace-normal" disabled={busy || !!pending || (!job.failed && job.processed === job.total)} onClick={() => void action("retry")}>{text("重试失败及未完成项", "Retry failed or pending items")}</Button>
        <Button variant="ghost" className="min-h-11" disabled={busy || !!pending} onClick={() => void action("clear")}>{text("清除预览", "Clear preview")}</Button>
      </div>}
      {job && !running && !fieldLevel && <p className="text-sm text-amber-800">{text("当前 API 返回的是旧版预览。请配套更新 API 与 Web 后再应用逐项更新。", "The API returned a legacy preview. Update the API and Web together to apply field-level changes.")}</p>}
      {job && !running && <label className="grid max-w-xs gap-1 text-sm">
        {text("筛选扫描结果", "Filter scan results")}
        <select className="min-h-11 rounded-md border bg-background px-3 text-base" value={filter} disabled={busy || !!pending}
          onChange={event => { setFilter(event.target.value as "all" | CatalogStatus); setPage(1); }}>
          {filters.map(option => <option key={option} value={option}>{labels[option]}</option>)}
        </select>
      </label>}
      {result && <div role="status" className="space-y-1 break-words rounded-xl border p-3 text-sm">
        <p>{Object.entries(result.summary).map(([key, count]) => `${labels[key] ?? key} ${count}`).join(" · ")}</p>
        {result.items.filter(item => item.status === "failed" || item.status === "conflict").map(item =>
          <p key={item.component_id}>{rows?.items.find(row => row.id === item.component_id)?.sku ?? item.component_id} · {labels[item.status]}{item.message ? ` · ${item.message}` : ""}</p>)}
      </div>}
      {rows && <>
        {rows.items.length === 0 && <p className="rounded-xl border border-dashed p-3 text-sm text-muted-foreground">
          {job?.total === 0 ? text("这个账户没有有效库存。请核对当前账户和客户端同步状态。", "There are no active items in this account. Check the account and device sync.") : text("此筛选下没有结果。", "No items match this filter.")}
        </p>}
        <div className="space-y-3">
          {rows.items.map(row => {
            const options = catalogOptions(row);
            const matchConfirmed = confirmedMatches.has(row.id);
            const locked = !enabled || !fieldLevel || busy || !!pending || resolved.has(row.id) || row.status !== "ready";
            return <article key={row.id} className="min-w-0 space-y-2 rounded-xl border p-3 sm:p-4">
              <h3 className="break-words font-semibold [overflow-wrap:anywhere]">{row.sku} · {row.name}</h3>
              <p className="text-sm text-muted-foreground">{resolved.has(row.id) ? text("本次已处理", "Processed") : labels[row.status]}</p>
              {row.reason && <p className="text-sm text-muted-foreground [overflow-wrap:anywhere]">{reasons[row.reason] ?? row.reason}</p>}
              {row.match_source === "mpn" && <label className="flex min-h-11 items-start gap-3 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm">
                <input className="mt-1 h-4 w-4 shrink-0" type="checkbox" checked={matchConfirmed} disabled={locked} onChange={event => {
                  const checked = event.target.checked;
                  setConfirmedMatches(previous => {
                    const next = new Set(previous);
                    if (checked) next.add(row.id); else next.delete(row.id);
                    return next;
                  });
                }} />
                <span className="min-w-0 [overflow-wrap:anywhere]">{text("确认厂商型号匹配", "Confirm the manufacturer match")} · {[row.match_brand, row.match_model].filter(Boolean).join(" · ")}</span>
              </label>}
              {options.length > 0 && <div className="space-y-2">
                {options.map(option => {
                  const checkboxDisabled = locked || (row.match_source === "mpn" && !matchConfirmed);
                  return <label key={option.id} className="flex min-h-11 items-start gap-3 rounded-lg bg-muted/50 p-3">
                    <input className="mt-1 h-4 w-4 shrink-0" type="checkbox" checked={(selected[row.id] ?? []).includes(option.id)}
                      disabled={checkboxDisabled} onChange={event => {
                        const checked = event.target.checked;
                        setSelected(previous => ({ ...previous, [row.id]: checked ? [...(previous[row.id] ?? []), option.id] : (previous[row.id] ?? []).filter(key => key !== option.id) }));
                      }} />
                    <span className="min-w-0 flex-1 text-sm [overflow-wrap:anywhere]">
                      <span className="block font-medium">{option.parameter ? displayParameterLabel(option.parameter, locale) : names[option.field]}</span>
                      {!option.safe && option.field !== "reference_price" && <span className="block text-xs font-medium text-amber-800">{text("与已有值不同", "Differs from saved value")}</span>}
                      <span className="mt-1 block text-muted-foreground">{text("当前", "Current")} · {value(option.field, option.before)}</span>
                      <span className="block">{text("拟更新", "Proposed")} · {value(option.field, option.after)}</span>
                    </span>
                  </label>;
                })}
              </div>}
              {row.source_url?.startsWith("https://") && <a className="inline-flex min-h-11 items-center text-sm text-primary underline underline-offset-2" href={row.source_url} target="_blank" rel="noreferrer">{text("查看目录来源", "View catalog source")}</a>}
            </article>;
          })}
        </div>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-sm">{page} / {pageCount}</span>
          <div className="flex gap-2">
            <Button variant="outline" className="min-h-11" disabled={page <= 1 || busy || !!pending} onClick={() => setPage(page - 1)}>{text("上一页", "Previous")}</Button>
            <Button variant="outline" className="min-h-11" disabled={page >= pageCount || busy || !!pending} onClick={() => setPage(page + 1)}>{text("下一页", "Next")}</Button>
          </div>
        </div>
      </>}
      {(rows || pending) && <div className="flex flex-wrap items-center justify-between gap-3 border-t bg-background py-3">
        <span className="text-sm text-muted-foreground">{text(`已选 ${pending?.length ?? selectedCount} 项`, `${pending?.length ?? selectedCount} selected`)}</span>
        <Button className="min-h-11 whitespace-normal" disabled={!enabled || !fieldLevel || busy || (!pending && !selectedCount)} onClick={requestApply}>
          {pending ? text("重试原请求", "Retry original request") : text("应用本页所选", "Apply selected on this page")}
        </Button>
      </div>}
      <dialog ref={dialog} aria-labelledby={`${id}-confirm`} onClose={() => setConfirmItems([])}
        className="m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-md overflow-y-auto rounded-2xl border bg-background p-5 text-foreground shadow-xl backdrop:bg-slate-950/40">
        <h3 id={`${id}-confirm`} className="text-lg font-semibold">{text("确认应用资料", "Confirm catalog updates")}</h3>
        <p className="my-3 text-sm">{text(`将更新 ${confirmItems.length} 个元件的已选字段。请确认目录资料和实际元件一致。`, `Apply the selected fields to ${confirmItems.length} components. Confirm that the catalog data matches the parts.`)}</p>
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" className="min-h-11" onClick={() => dialog.current?.close()}>{text("取消", "Cancel")}</Button>
          <Button className="min-h-11" disabled={!enabled || !confirmItems.length || busy} onClick={() => void apply(confirmItems)}>{text("确认应用", "Confirm updates")}</Button>
        </div>
      </dialog>
    </CardContent>}
  </Card>;
}
