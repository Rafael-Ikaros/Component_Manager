import { useEffect, useId, useRef, useState } from "react";

import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, postJson, requestJson } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import {
  clearPendingRequest, loadPendingRequest, pendingStorageKey, requestForPayload,
  savePendingRequest, shouldRetainPending, type PendingRequest,
} from "@/lib/pending-request";

type Channel = "stable" | "dev";
interface UpdateJob {
  id: string; channel: Channel; status: "queued" | "running" | "succeeded" | "failed";
  stage: string; message: string; created_at: string; updated_at: string; target_version?: string | null;
}
interface UpdateStatus {
  enabled: boolean; current_version: string; current_channel: Channel | "unknown";
  active_job: UpdateJob | null; latest_job: UpdateJob | null;
}
const endpoint = "/admin-api/server-update";
const updateStages = {
  queued: "等待更新", preparing: "准备更新", pulling: "拉取镜像", validating: "验证版本",
  recreating: "重建服务", verifying: "健康检查", complete: "更新完成", failed: "更新失败",
} as const;
const updateMessages = {
  "Waiting to update.": "正在等待开始更新。",
  "Retry queued.": "已安排重试，正在等待更新。",
  "Recovering an interrupted update.": "正在恢复中断的更新。",
  "Checking the existing deployment.": "正在检查当前部署。",
  "Pulling the selected images.": "正在拉取所选渠道的镜像。",
  "Checking API/Web release identities.": "正在验证 API 与 Web 的版本是否一致。",
  "Recreating the existing services.": "正在重建 API 与 Web 服务。",
  "Waiting for the updated services.": "正在等待更新后的服务通过健康检查。",
  "Services are running the selected version.": "服务已运行所选版本。",
} as const;

export function ServerUpdateCard({ currentVersion, guideUrl, channel, onBusyChange }: {
  currentVersion: string; guideUrl: string; channel: Channel; onBusyChange: (busy: boolean) => void;
}) {
  const { t } = useI18n();
  const { identity, session } = useAuth();
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [job, setJob] = useState<UpdateJob | null>(null);
  const [pending, setPending] = useState<PendingRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [reconnecting, setReconnecting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const pendingKey = session ? pendingStorageKey(session.apiBaseUrl, "server-update", identity?.account_id) : "";
  const updating = job?.status === "queued" || job?.status === "running";

  useEffect(() => { onBusyChange(busy || updating || !!pending); }, [busy, updating, pending, onBusyChange]);

  useEffect(() => {
    setPending(pendingKey ? loadPendingRequest(pendingKey) : null);
  }, [pendingKey]);

  useEffect(() => {
    if (!session || identity?.role !== "admin") return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    async function poll() {
      let again = false;
      try {
        const value = await requestJson<UpdateStatus>(session!, `${endpoint}/status`);
        if (cancelled) return;
        setStatus(value);
        setReconnecting(false);
        const latest = value.active_job ?? value.latest_job;
        if (latest) setJob(latest);
        again = latest?.status === "queued" || latest?.status === "running";
      } catch (error) {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 404) {
          setStatus({enabled: false, current_version: currentVersion, current_channel: "unknown", active_job: null, latest_job: null});
          setMessage(t("此 API 或更新服务版本尚不支持网页更新，请先在群晖项目中配置配套版本。"));
          setReconnecting(false);
          return;
        }
        setReconnecting(true);
        again = true;
      }
      if (!cancelled && again) timer = setTimeout(() => void poll(), 3000);
    }
    void poll();
    return () => { cancelled = true; if (timer) clearTimeout(timer); };
  }, [identity?.role, session, refresh, job?.id, updating, currentVersion]);

  async function submit(retryJob = false) {
    if (!session || busy) return;
    setBusy(true); setMessage(null);
    let request = pending;
    if (!retryJob) {
      const chosen = request ? (JSON.parse(request.payload) as { channel: Channel }).channel : channel;
      request = requestForPayload(request, { channel: chosen });
      setPending(request);
      if (!savePendingRequest(pendingKey, request)) setMessage(t("浏览器无法保存恢复信息，请保持本页打开并使用原表单重试。"));
    }
    try {
      const next = retryJob && job
        ? await postJson<UpdateJob>(session, `${endpoint}/jobs/${encodeURIComponent(job.id)}/retry`, {})
        : await postJson<UpdateJob>(session, `${endpoint}/jobs`, { ...JSON.parse(request!.payload) as { channel: Channel }, request_id: request!.requestId });
      setJob(next);
      clearPendingRequest(pendingKey); setPending(null);
      dialog.current?.close();
      setRefresh((value) => value + 1);
    } catch (error) {
      if (!retryJob && error instanceof ApiError && !shouldRetainPending(error.status)) {
        clearPendingRequest(pendingKey); setPending(null);
      }
      setMessage(t("更新请求暂未确认。请查询状态或重试原请求；更新期间 API 可能短暂断线。"));
      setRefresh((value) => value + 1);
    } finally { setBusy(false); }
  }

  if (identity?.role !== "admin") return null;
  const channelName = (value: string) => value === "dev" ? t("开发渠道") : value === "stable" ? t("正式渠道") : t("未知");
  const stageLabel = job ? t(updateStages[job.stage as keyof typeof updateStages] ?? "正在更新") : "";
  const normalMessage = job ? t(updateMessages[job.message as keyof typeof updateMessages]
    ?? (job.status === "succeeded" ? "服务已运行所选版本。" : "正在执行更新任务。")) : "";
  return <section className="space-y-4 rounded-xl border bg-slate-50 p-4" aria-label={t("服务端更新")}>
    <h3 className="font-semibold">{t("服务端更新")}</h3>
    <p className="text-sm text-muted-foreground">{t("当前运行：")}{status?.current_version ?? currentVersion} · {channelName(status?.current_channel ?? (currentVersion === "source" ? "unknown" : currentVersion.includes("-dev.") ? "dev" : "stable"))}</p>
    {reconnecting ? <p role="status" className="text-sm text-amber-800">{t("正在重新连接更新服务；已知任务会保留。更新重启期间请稍候。")}</p> : null}
    {status?.enabled === false ? <div className="space-y-2 text-sm">
      <p>{t("此部署尚未启用网页更新。请在 Container Manager 或 Docker GUI 中按教程一次性添加 updater 服务，并为 API 配置 UPDATER_URL。")}</p>
      <p>{t("更新服务配置完成后，即可在这里切换正式或开发渠道并更新 API 与 Web。源码或系统服务部署仍可使用上方版本检查。")}</p>
      <a className="underline" href={guideUrl} target="_blank" rel="noreferrer">{t("配置网页更新教程")}</a>
    </div> : status?.enabled ? <>
      <div className="space-y-2">
        <p className="text-sm font-medium">{t("目标更新渠道")}: {channelName(channel)}</p>
        <p className="text-sm text-muted-foreground">{t("按所选渠道更新本部署的 API 和 Web。开发渠道包含预发布；切回正式渠道可能降级，不能代替数据回滚。")}</p>
        <p className="text-sm text-muted-foreground">{t("目标版本必须支持网页更新；若不支持，会取消切换并保留当前部署。")}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button disabled={busy || updating || !!pending || reconnecting} onClick={() => dialog.current?.showModal()}>{t("更新到所选渠道")}</Button>
        {pending ? <Button variant="outline" disabled={busy} onClick={() => void submit()}>{t("确认上次更新请求")}</Button> : null}
        {job?.status === "failed" && !pending ? <Button variant="outline" disabled={busy} onClick={() => void submit(true)}>{t("重试失败更新")}</Button> : null}
        {job?.status === "succeeded" ? <Button variant="outline" onClick={() => window.location.reload()}>{t("重新加载管理台")}</Button> : null}
      </div>
    </> : !reconnecting ? <p className="text-sm text-muted-foreground">{t("正在读取更新能力…")}</p> : null}
    {job ? <div className="space-y-1 rounded-lg border bg-white p-3 text-sm" role="status">
      <p className="font-medium">{t("更新任务：")}{t(({ queued: "等待更新", running: "正在更新", succeeded: "更新成功", failed: "更新失败" } as const)[job.status])} · {channelName(job.channel)}</p>
      {job.target_version ? <p>{t("目标版本：")}{job.target_version}</p> : null}
      <p className="break-words text-muted-foreground">{job.message.includes("TARGET_WEB_UPDATE_UNSUPPORTED")
        ? t("目标版本尚不支持网页更新，为保留更新入口已取消切换；请等待支持此功能的正式版。")
        : job.status === "failed" ? t("更新未完成，请查看详情后重试。") : `${stageLabel} · ${normalMessage}`}</p>
      {job.status === "failed" && !job.message.includes("TARGET_WEB_UPDATE_UNSUPPORTED") ? <details className="pt-1"><summary className="cursor-pointer">{t("技术详情")}</summary><pre className="mt-2 whitespace-pre-wrap break-words rounded bg-slate-50 p-2 text-xs text-muted-foreground">{job.message}</pre></details> : null}
    </div> : null}
    {message ? <p role="alert" className="text-sm text-amber-800">{message}</p> : null}
    <Button variant="outline" size="sm" disabled={busy} onClick={() => setRefresh((value) => value + 1)}>{t("刷新更新状态")}</Button>
    <dialog ref={dialog} aria-labelledby={titleId} className="m-auto w-[calc(100%-2rem)] max-w-lg rounded-2xl border bg-white p-6 text-foreground shadow-xl backdrop:bg-slate-950/40" onCancel={(event) => { if (busy) event.preventDefault(); }}>
      <div className="space-y-4">
        <h3 id={titleId} className="text-lg font-semibold">{t("确认服务端更新")}</h3>
        <p className="text-sm">{t("当前运行：")}{status?.current_version ?? currentVersion} · {channelName(status?.current_channel ?? "unknown")}</p>
        <p className="text-sm">{t("目标更新渠道")}: {channelName(channel)} ({channel === "stable" ? "latest / web-latest" : "dev / web-dev"})</p>
        <p className="text-sm text-muted-foreground">{t("请先备份完整 /data。更新会重建 API 和 Web，并可能短暂断线；数据迁移后的降级不保证兼容，也不会自动回滚。")}</p>
        {message ? <p role="alert" className="text-sm text-amber-800">{message}</p> : null}
        <div className="flex flex-wrap justify-end gap-2"><Button variant="outline" disabled={busy} onClick={() => dialog.current?.close()}>{t("取消")}</Button><Button disabled={busy || updating || !!pending} onClick={() => void submit()}>{busy ? t("正在提交…") : t("确认更新")}</Button></div>
      </div>
    </dialog>
  </section>;
}
