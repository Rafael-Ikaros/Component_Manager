import { useEffect, useId, useState } from "react";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { postJson } from "@/lib/api";
import { useI18n } from "@/lib/i18n";

interface About { author: string; version: string; revision: string; deployment: string; repository_url: string; deployment_guide_url: string }
interface Update { latest_version: string; update_available: boolean | null; release_url: string; channel: "stable" | "dev" }

export function ServerAboutCard() {
  const { t } = useI18n();
  const { session } = useAuth();
  const info = useAdminResource<About>("/admin-api/about");
  const [update, setUpdate] = useState<Update | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [channel, setChannel] = useState<"stable" | "dev">("stable");
  const channelId = useId();
  const preferenceKey = `component-vault-update-channel:${session?.apiBaseUrl ?? ""}`;
  useEffect(() => {
    try { setChannel(localStorage.getItem(preferenceKey) === "dev" ? "dev" : "stable"); }
    catch { setChannel("stable"); }
    setUpdate(null); setError(null);
  }, [preferenceKey]);
  return <Card><CardHeader><CardTitle>{t("关于服务端")}</CardTitle></CardHeader><CardContent className="space-y-4">
    <p className="font-medium">Component Vault Server</p>
    {info.data ? <>
      <dl className="grid grid-cols-[auto,1fr] gap-x-6 gap-y-2 text-sm">
        <dt>{t("作者")}</dt><dd>{info.data.author}</dd>
        <dt>{t("版本")}</dt><dd>{info.data.version} <span className="text-muted-foreground">({info.data.revision.slice(0, 8)})</span></dd>
        <dt>{t("部署方式")}</dt><dd>{info.data.deployment === "container" ? t("Docker / 容器") : t("源码或系统服务")}</dd>
      </dl>
      <div className="space-y-2">
        <label htmlFor={channelId} className="block text-sm font-medium">{t("版本检测渠道")}</label>
        <select id={channelId} disabled={busy} value={channel} className="min-h-11 rounded-md border border-input bg-background px-3 py-2 text-sm" onChange={(event) => {
          const next = event.target.value === "dev" ? "dev" : "stable";
          setChannel(next); setUpdate(null); setError(null);
          try { localStorage.setItem(preferenceKey, next); } catch { /* This tab still keeps the preference. */ }
        }}>
          <option value="stable">{t("正式版（推荐）")}</option>
          <option value="dev">{t("开发版（含预发布）")}</option>
        </select>
        <p className="text-sm text-muted-foreground">{t("检测渠道仅保存在此浏览器，不会替换正在运行的容器。开发渠道也会包含比开发版更新的正式版。")}</p>
      </div>
      <div className="flex flex-wrap gap-3">
        <Button disabled={busy} onClick={async () => {
          if (!session) return;
          setBusy(true); setError(null); setUpdate(null);
          try { setUpdate(await postJson<Update>(session, `/admin-api/about/check-update?channel=${channel}`, {})); }
          catch { setError(t("无法检查更新，请稍后重试或打开发布页面。")); }
          finally { setBusy(false); }
        }}>{busy ? t("正在检查…") : t("检查更新")}</Button>
        <a className="self-center text-sm underline" href={`${info.data.repository_url}/releases`} target="_blank" rel="noreferrer">{t("发布记录")}</a>
        <a className="self-center text-sm underline" href={info.data.deployment_guide_url} target="_blank" rel="noreferrer">{t("部署与更新教程")}</a>
      </div>
      {info.data.deployment === "container" ? <div className="space-y-2 rounded-lg border p-4 text-sm">
        <p className="font-medium">{t("Docker 渠道切换")}</p>
        <p>{t("在原部署目录运行切换助手，选择渠道后自动拉取并重建 API 和 Web。沿用原项目名、端口和数据挂载，不需要手动修改镜像标签。")}</p>
        <pre className="overflow-x-auto rounded bg-muted p-3 text-xs"><code>{`sh tools/deployment/switch-channel.sh --channel ${channel} --project-name <PROJECT_NAME>`}</code></pre>
        <p>{t("将 PROJECT_NAME 替换为原 Compose 项目名。群晖可通过 SSH 运行；不使用 SSH 时按部署教程在 Container Manager 中切换。")}</p>
        <p className="text-muted-foreground">{t("Watchtower 跟随容器当前的镜像渠道，不会自行跨渠道。切换前备份完整 /data；切回正式版不能代替数据回滚。")}</p>
      </div> : null}
    </> : <Button variant="outline" disabled={info.loading} onClick={() => void info.reload()}>{t("重新读取")}</Button>}
    {info.error || error ? <p role="alert" className="text-sm text-red-700">{info.error || error}</p> : null}
    {update ? <p role="status" className="text-sm">{update.update_available === null ? t("源码构建无法自动比较版本。所选渠道最新版本：") : update.update_available ? t("发现新版本：") : t("当前版本不低于所选渠道最新版本：")}<a className="underline" href={update.release_url} target="_blank" rel="noreferrer">{update.latest_version}</a></p> : null}
  </CardContent></Card>;
}
