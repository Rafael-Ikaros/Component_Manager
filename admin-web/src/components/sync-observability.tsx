import { useState } from "react";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { useAuth } from "@/hooks/use-auth";
import { postJson } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { formatDateTime } from "@/lib/format";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

interface Device { device_id: string; first_seen_at: string; last_seen_at: string; push_count: number; pull_count: number }
interface Audit { id: number; device_id: string | null; direction: string; status: string; observed_at: string; accepted_components: number; accepted_stock_movements: number; cursor: number | null }
interface Resolution { id: number; resolution: string; note: string | null; created_at: string }
interface Conflict { id: number; entity_type: string; entity_id: string; device_id: string | null; server_device_id: string | null; detected_at: string; kind: string; winner: string; server_value: Record<string, unknown>; incoming_value: Record<string, unknown>; resolutions: Resolution[] }

export function SyncObservability() {
  const { t, locale } = useI18n();
  const [tab, setTab] = useState<"devices" | "audit" | "conflicts">("devices");
  return <Card><CardHeader><CardTitle>{t("设备与同步审计")}</CardTitle><CardDescription>{t("仅显示当前账户的数据。从此版本开始记录，旧客户端未提供设备标识的拉取会显示为未知设备。")}</CardDescription></CardHeader>
    <CardContent className="space-y-4">
      <div className="grid grid-cols-3 gap-1.5 sm:flex sm:flex-wrap sm:gap-2" role="group" aria-label={t("同步视图")}>
        {(["devices", "audit", "conflicts"] as const).map((value) => <Button key={value} className="min-h-11 min-w-0 whitespace-normal px-1.5 leading-tight sm:px-4" variant={tab === value ? "default" : "outline"} aria-pressed={tab === value} onClick={() => setTab(value)}>{t(({ devices: "设备注册表", audit: "同步日志", conflicts: "冲突历史" } as const)[value])}</Button>)}
      </div>
      {tab === "devices" ? <Devices /> : tab === "audit" ? <AuditLog /> : <Conflicts />}
      <p className="hidden text-xs text-muted-foreground lg:block">{t("当前账户")} · {locale}</p>
    </CardContent>
  </Card>;
}

function Devices() {
  const { t, locale } = useI18n();
  const resource = useAdminResource<{ items: Device[] }>("/admin-api/sync/devices");
  const devices = resource.data?.items ?? [];
  return <>
    <ResourceState {...resource} />
    {devices.length > 0 ? <>
      <ul className="divide-y lg:hidden">
        {devices.map((device) => (
          <li key={device.device_id} className="space-y-2 py-3 text-sm first:pt-0 last:pb-0 [overflow-wrap:anywhere]">
            <p className="font-medium">{device.device_id}</p>
            <dl className="grid grid-cols-2 gap-x-2 gap-y-2 text-xs">
              <div><dt className="text-muted-foreground">{t("首次同步")}</dt><dd>{formatDateTime(device.first_seen_at, locale)}</dd></div>
              <div><dt className="text-muted-foreground">{t("最近同步")}</dt><dd>{formatDateTime(device.last_seen_at, locale)}</dd></div>
              <div className="col-span-2 flex flex-wrap gap-x-2 gap-y-1"><dt className="text-muted-foreground">{t("上传 / 拉取次数")}</dt><dd>{device.push_count} / {device.pull_count}</dd></div>
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden lg:block">
        <Table><TableHeader><TableRow>{(["设备", "首次同步", "最近同步", "上传 / 拉取次数"] as const).map((label) => <TableHead key={label}>{t(label)}</TableHead>)}</TableRow></TableHeader><TableBody>{devices.map((device) => <TableRow key={device.device_id}>
          <TableCell className="break-all font-mono">{device.device_id}</TableCell><TableCell>{formatDateTime(device.first_seen_at, locale)}</TableCell><TableCell>{formatDateTime(device.last_seen_at, locale)}</TableCell><TableCell>{device.push_count} / {device.pull_count}</TableCell>
        </TableRow>)}</TableBody></Table>
      </div>
    </> : null}
  </>;
}

function AuditLog() {
  const { t, locale } = useI18n();
  const resource = useAdminResource<{ items: Audit[] }>("/admin-api/sync/audit?limit=100");
  const entries = resource.data?.items ?? [];
  const direction = (entry: Audit) => t(entry.direction === "push" ? "上传" : "拉取");
  const result = (entry: Audit) => t(entry.status === "success" ? "成功" : entry.status === "conflict" ? "冲突" : "拒绝");
  const accepted = (entry: Audit) => entry.direction === "push" ? `${entry.accepted_components} / ${entry.accepted_stock_movements}` : "—";
  return <>
    <p className="text-sm text-muted-foreground">{t("显示最近 100 条同步记录。")}</p>
    <ResourceState {...resource} />
    {entries.length > 0 ? <>
      <ul className="divide-y lg:hidden">
        {entries.map((entry) => (
          <li key={entry.id} className="space-y-2 py-3 text-sm first:pt-0 last:pb-0 [overflow-wrap:anywhere]">
            <p className="font-medium">{entry.device_id || t("未知设备")}</p>
            <p className="text-muted-foreground">{direction(entry)} · {result(entry)} · {formatDateTime(entry.observed_at, locale)}</p>
            <dl className="grid grid-cols-2 gap-2 text-xs">
              <div><dt className="text-muted-foreground">{t("接收元件 / 流水")}</dt><dd>{accepted(entry)}</dd></div>
              <div><dt className="text-muted-foreground">{t("同步游标")}</dt><dd>{entry.cursor ?? "—"}</dd></div>
            </dl>
          </li>
        ))}
      </ul>
      <div className="hidden lg:block">
        <Table><TableHeader><TableRow>{(["时间", "设备", "方向", "结果", "接收元件 / 流水", "同步游标"] as const).map((label) => <TableHead key={label}>{t(label)}</TableHead>)}</TableRow></TableHeader><TableBody>{entries.map((entry) => <TableRow key={entry.id}>
          <TableCell>{formatDateTime(entry.observed_at, locale)}</TableCell><TableCell className="break-all">{entry.device_id || t("未知设备")}</TableCell><TableCell>{direction(entry)}</TableCell><TableCell>{result(entry)}</TableCell><TableCell>{accepted(entry)}</TableCell><TableCell>{entry.cursor ?? "—"}</TableCell>
        </TableRow>)}</TableBody></Table>
      </div>
    </> : null}
  </>;
}

function Conflicts() {
  const { t } = useI18n();
  const resource = useAdminResource<{ items: Conflict[] }>("/admin-api/sync/conflicts?limit=100");
  return <div className="space-y-3"><p className="text-sm text-muted-foreground">{t("显示最近 100 次被拒绝的冲突写入。这里保存处理备注，客户端仍保留待同步的改动。")}</p><ResourceState {...resource} />{resource.data?.items.map((conflict) => <ConflictItem key={conflict.id} conflict={conflict} reload={resource.reload} />)}</div>;
}

function ConflictItem({ conflict, reload }: { conflict: Conflict; reload: () => Promise<void> }) {
  const { t, locale } = useI18n(); const { session } = useAuth();
  const [note, setNote] = useState(""); const [busy, setBusy] = useState(false); const [error, setError] = useState<string | null>(null);
  async function resolve(resolution: string) {
    if (!session) return;
    setBusy(true); setError(null);
    try { await postJson(session, `/admin-api/sync/conflicts/${conflict.id}/resolve`, { resolution, note: note.trim() || null }); await reload(); }
    catch (caught) { setError(caught instanceof Error ? caught.message : t("提交失败")); }
    finally { setBusy(false); }
  }
  return <details className="min-w-0 rounded-xl border px-3 [overflow-wrap:anywhere] sm:px-4"><summary className="min-h-11 cursor-pointer py-3 text-sm font-medium">
    {String(conflict.server_value.sku || conflict.incoming_value.sku || conflict.entity_id)} · {conflict.device_id || t("未知设备")} · {formatDateTime(conflict.detected_at, locale)} · {t(conflict.resolutions.length ? "已记录处理" : "待处理")}
  </summary><div className="space-y-3 pb-3">
    <p className="text-sm">{t("服务端保留了当前版本，传入数据未覆盖。")}</p><p className="text-sm">{t("服务端数据来源：")}{conflict.server_device_id || t("未知设备")}</p>
    <div className="grid gap-3 md:grid-cols-2">{([["服务端版本", conflict.server_value], ["传入版本", conflict.incoming_value]] as const).map(([label, value]) => <div key={String(label)}><h4 className="mb-2 text-sm font-medium">{t(label)}</h4><pre className="max-h-64 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-slate-50 p-3 text-xs">{JSON.stringify(value, null, 2)}</pre></div>)}</div>
    {conflict.resolutions.map((resolution) => <p key={resolution.id} className="text-sm">{formatDateTime(resolution.created_at, locale)} · {t(resolution.resolution === "server_kept" ? "确认保留服务端" : "已在客户端重新提交")} {resolution.note}</p>)}
    <label className="block text-sm">{t("处理备注")}<Input value={note} maxLength={1000} onChange={(event) => setNote(event.target.value)} /></label>
    <div className="flex flex-wrap gap-2"><Button className="min-h-11 whitespace-normal" disabled={busy} onClick={() => void resolve("server_kept")}>{t("记录保留服务端版本")}</Button><Button className="min-h-11 whitespace-normal" variant="outline" disabled={busy} onClick={() => void resolve("client_resubmitted")}>{t("记录：已在客户端重新提交")}</Button></div>
    {error ? <p role="alert" className="text-sm text-red-700">{error}</p> : null}
  </div></details>;
}

function ResourceState({ data, loading, error, reload }: { data: { items: unknown[] } | null; loading: boolean; error: string | null; reload: () => Promise<void> }) {
  const { t } = useI18n();
  return <div className="flex flex-wrap items-center gap-3 text-sm"><Button size="sm" className="min-h-11" variant="outline" disabled={loading} onClick={() => void reload()}>{t(loading ? "正在读取…" : "刷新")}</Button>{error ? <span role="alert" className="min-w-0 text-red-700 [overflow-wrap:anywhere]">{error}</span> : !loading && data?.items.length === 0 ? <span>{t("暂无记录")}</span> : null}</div>;
}
