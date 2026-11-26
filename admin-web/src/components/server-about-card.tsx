import { useEffect, useId, useState } from "react";
import { Card, CardHeader, CardTitle, CardContent } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { postJson } from "@/lib/api";
import { useI18n } from "@/lib/i18n";
import { ServerUpdateCard } from "@/components/server-update-card";

interface BuildIdentity { version?: string; revision?: string }
interface About extends BuildIdentity { author: string; deployment: string; repository_url: string; deployment_guide_url: string }
interface Update { latest_version: string; update_available: boolean | null; release_url: string; channel: "stable" | "dev" }

const WEB_BUILD = {
  version: import.meta.env.VITE_COMPONENT_VAULT_WEB_VERSION || "source",
  revision: import.meta.env.VITE_COMPONENT_VAULT_WEB_REVISION,
};

function withoutTagPrefix(version: string) {
  return version.replace(/^v(?=\d)/, "");
}

function BuildVersion({ version, revision }: BuildIdentity) {
  const { t } = useI18n();
  if (!version) return <>{t("未知")}</>;
  const isDw31 = withoutTagPrefix(version) === "0.7.9-dev.31";
  return <span className="flex flex-wrap gap-x-2 break-words">
    <span>{isDw31 ? "dw31" : version === "source" ? t("源码构建") : version}</span>
    {isDw31 ? <span className="text-muted-foreground">{version}</span> : null}
    {revision && revision !== "unknown" ? <span className="text-muted-foreground">({revision.slice(0, 8)})</span> : null}
  </span>;
}

export function ServerAboutCard() {
  const { t } = useI18n();
  const { session } = useAuth();
  const info = useAdminResource<About>("/admin-api/about");
  const readHealth = !info.loading && !info.data?.version;
  const health = useAdminResource<BuildIdentity>(readHealth ? "/health" : null);
  const apiBuild = info.data?.version ? info.data : health.data;
  const apiVersion = apiBuild?.version;
  const manualGuideUrl = info.data
    ? `${info.data.repository_url}/blob/master/docs/docker-manual.md#更新-api-容器`
    : "";
  const versionsDiffer = apiVersion && apiVersion !== "source" && WEB_BUILD.version !== "source"
    && withoutTagPrefix(apiVersion) !== withoutTagPrefix(WEB_BUILD.version);
  const [update, setUpdate] = useState<Update | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [channel, setChannel] = useState<"stable" | "dev">("stable");
  const [updaterBusy, setUpdaterBusy] = useState(false);
  const channelId = useId();
  const preferenceKey = `component-vault-update-channel:${session?.apiBaseUrl ?? ""}`;
  useEffect(() => {
    try { setChannel(localStorage.getItem(preferenceKey) === "dev" ? "dev" : "stable"); }
    catch { setChannel("stable"); }
    setUpdate(null); setError(null);
  }, [preferenceKey]);
  return <Card><CardHeader><CardTitle>{t("关于服务端")}</CardTitle></CardHeader><CardContent className="space-y-4">
    <p className="font-medium">Component Vault Server</p>
    <dl className="grid grid-cols-[auto,minmax(0,1fr)] gap-x-4 gap-y-2 text-sm sm:gap-x-6">
      <dt>{t("Web 管理台版本")}</dt><dd><BuildVersion {...WEB_BUILD} /></dd>
      <dt>{t("API 服务端版本")}</dt>
      <dd>{info.loading || (readHealth && health.loading) ? t("正在读取…") : <BuildVersion version={apiBuild?.version} revision={apiBuild?.revision} />}</dd>
      {session ? <><dt>{t("API 地址")}</dt><dd className="break-all">{session.apiBaseUrl}</dd></> : null}
      {info.data ? <>
        <dt>{t("作者")}</dt><dd>{info.data.author}</dd>
        <dt>{t("部署方式")}</dt><dd>{info.data.deployment === "container" ? t("Docker / 容器") : t("源码或系统服务")}</dd>
      </> : null}
    </dl>
    {versionsDiffer ? <p className="text-sm text-muted-foreground">
      {t("Web 与 API 版本不同。请核对管理台连接的 API 地址和该容器的镜像。")}
      {manualGuideUrl ? <> <a className="underline" href={manualGuideUrl} target="_blank" rel="noreferrer">{t("更新 API 容器")}</a></> : null}
    </p> : null}
    {info.data ? <>
      <div className="space-y-2">
        <label htmlFor={channelId} className="block text-sm font-medium">{t("更新渠道")}</label>
        <select id={channelId} disabled={busy || updaterBusy} value={channel} className="min-h-11 rounded-md border border-input bg-background px-3 py-2 text-sm" onChange={(event) => {
          const next = event.target.value === "dev" ? "dev" : "stable";
          setChannel(next); setUpdate(null); setError(null);
          try { localStorage.setItem(preferenceKey, next); } catch { /* This tab still keeps the preference. */ }
        }}>
          <option value="stable">{t("正式版（推荐）")}</option>
          <option value="dev">{t("开发版（含预发布）")}</option>
        </select>
        <p className="text-sm text-muted-foreground">{t("此选择用于版本检查和已启用的网页更新目标；切换选项本身不会安装或更新。渠道偏好保存在此浏览器。")}</p>
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
      <ServerUpdateCard currentVersion={apiVersion || "source"} channel={channel} onBusyChange={setUpdaterBusy} guideUrl={`${info.data.repository_url}/blob/master/docs/server-web-update.md`} manualGuideUrl={manualGuideUrl} />
    </> : <Button variant="outline" disabled={info.loading} onClick={() => void info.reload()}>{t("重新读取")}</Button>}
    {info.error || error ? <p role="alert" className="text-sm text-red-700">{info.error || error}</p> : null}
    {update ? <p role="status" className="text-sm">{update.update_available === null ? t("源码构建无法自动比较版本。所选渠道最新版本：") : update.update_available ? t("发现新版本：") : t("当前版本不低于所选渠道最新版本：")}<a className="underline" href={update.release_url} target="_blank" rel="noreferrer">{update.latest_version}</a></p> : null}
  </CardContent></Card>;
}
