import { translateServerText, useI18n } from "@/lib/i18n";
import { ChevronDown, Info, Radio, Server, SlidersHorizontal, Users, type LucideIcon } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { useAdminResource } from "@/hooks/use-admin-resource";
import type { AdminSettingsResponse } from "@/lib/types";
import { MqttSettingsCard } from "@/components/mqtt-settings-card";
import { DeploymentSettingsCard } from "@/components/deployment-settings-card";
import { useAuth } from "@/hooks/use-auth";
import { serverSetupUrl } from "@/lib/clipboard";
import { LanguageSelect } from "@/components/language-select";
import { ServerAboutCard } from "@/components/server-about-card";
import { AccountsSettingsCard } from "@/components/accounts-settings-card";

export function SettingsPage() {
  const { t, locale } = useI18n();
  const { identity, session } = useAuth();
  const isAdmin = identity?.role === "admin";
  const [desktop, setDesktop] = useState(() => window.matchMedia("(min-width: 1024px)").matches);
  const { data, error, loading, reload } = useAdminResource<AdminSettingsResponse>(
    "/admin-api/settings",
  );

  useEffect(() => {
    const media = window.matchMedia("(min-width: 1024px)");
    const update = () => setDesktop(media.matches);
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  return (
    <section className="space-y-3 lg:space-y-6">
      <header className="space-y-2">
        <h2 className="text-2xl font-semibold tracking-tight lg:text-3xl">{t("Settings")}</h2>
        <p className="hidden max-w-3xl text-sm text-slate-600 lg:block">
          {isAdmin ? t("Runtime configuration and access posture read from the same FastAPI process.") : t("此账户的库存由服务端独立保存。")}
        </p>
      </header>

      <Card className="bg-white/90">
        <CardHeader className="hidden lg:flex"><CardTitle>{t("语言")}</CardTitle></CardHeader>
        <CardContent className="py-2.5 lg:pt-0"><LanguageSelect /></CardContent>
      </Card>

      <SettingsSection title={t("关于服务端")} icon={Info} desktop={desktop}>
        <ServerAboutCard />
      </SettingsSection>

      {isAdmin ? <>
        <SettingsSection title={t("账户管理")} icon={Users} desktop={desktop}>
          <AccountsSettingsCard />
        </SettingsSection>
        <SettingsSection title={t("服务端配置")} icon={SlidersHorizontal} desktop={desktop}>
          <DeploymentSettingsCard />
        </SettingsSection>
        <SettingsSection title={locale === "zh-CN" ? "MQTT 设置" : "MQTT settings"} icon={Radio} desktop={desktop}>
          <MqttSettingsCard />
        </SettingsSection>
      </> : null}

      <SettingsSection title={locale === "zh-CN" ? "连接与运行详情" : "Connection and runtime details"} icon={Server} desktop={desktop}>
        <Alert>
          <AlertTitle>{t("后端会话认证")}</AlertTitle>
          <AlertDescription className="mt-2 space-y-3">
            <p>{t("账户密钥仅用于登录。浏览器通过 HttpOnly 会话 Cookie 访问服务端，不保存原始密钥。")}</p>
            <p>{isAdmin ? t("管理员密钥可在部署环境的 API_TOKEN 或数据目录 config.json 中查看；普通用户密钥由管理员创建或重置后发放。") : t("如需在其他设备登录，请使用管理员发放的账户密钥。")}</p>
            {isAdmin && session && serverSetupUrl(session.apiBaseUrl) ? <a className="flex min-h-11 items-center underline lg:min-h-0" href={serverSetupUrl(session.apiBaseUrl)} target="_blank" rel="noopener noreferrer">{t("打开服务端配置与日志")}</a> : null}
          </AlertDescription>
        </Alert>

        {error ? (
          <Alert variant="destructive">
            <AlertTitle>{t("Settings view unavailable")}</AlertTitle>
            <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
              <span className="min-w-0 [overflow-wrap:anywhere]">{error}</span>
              <Button onClick={() => void reload()} size="sm" variant="outline">
                {t("Retry")}
              </Button>
            </AlertDescription>
          </Alert>
        ) : null}

        {isAdmin && data ? (
          <>
            <div className="grid min-w-0 gap-3 lg:gap-6 xl:grid-cols-[minmax(0,1.2fr),minmax(0,0.8fr)]">
              <Card className="bg-white/90">
                <CardHeader>
                  <CardTitle>{t("Runtime configuration")}</CardTitle>
                  <CardDescription>
                    {t("Effective process settings that drive this sync service.")}
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  {data.runtime_configuration.map((item, index) => (
                    <div key={item.label}>
                      <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-6">
                        <p className="text-sm font-medium text-slate-600">{translateServerText(item.label, locale)}</p>
                        <p className="min-w-0 text-sm text-slate-900 [overflow-wrap:anywhere] sm:max-w-[60%] sm:text-right">
                          {translateServerText(item.value, locale)}
                        </p>
                      </div>
                      {index < data.runtime_configuration.length - 1 ? (
                        <Separator className="mt-4" />
                      ) : null}
                    </div>
                  ))}
                </CardContent>
              </Card>

              <Card className="bg-white/90">
                <CardHeader>
                  <CardTitle>{t("Access posture")}</CardTitle>
                  <CardDescription>
                    {t("Operational URLs and deployment-sensitive settings.")}
                  </CardDescription>
                </CardHeader>
                <CardContent className="space-y-4">
                  {data.access_posture.map((item, index) => (
                    <div key={item.label}>
                      <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-6">
                        <p className="text-sm font-medium text-slate-600">{translateServerText(item.label, locale)}</p>
                        <p className="min-w-0 text-sm text-slate-900 [overflow-wrap:anywhere] sm:max-w-[60%] sm:text-right">
                          {translateServerText(item.value, locale)}
                        </p>
                      </div>
                      {index < data.access_posture.length - 1 ? (
                        <Separator className="mt-4" />
                      ) : null}
                    </div>
                  ))}
                </CardContent>
              </Card>
            </div>
          </>
        ) : null}

        {isAdmin && loading && !data ? <Card className="h-56 animate-pulse bg-white/70" /> : null}
      </SettingsSection>
    </section>
  );
}

function SettingsSection({ title, icon: Icon, desktop, children }: {
  title: string;
  icon: LucideIcon;
  desktop: boolean;
  children: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);

  return <details className="group min-w-0 rounded-xl border bg-card lg:border-0 lg:bg-transparent" open={desktop || expanded} onToggle={(event) => {
    if (!desktop) setExpanded(event.currentTarget.open);
  }}>
    <summary className="flex min-h-12 cursor-pointer list-none items-center gap-3 px-4 py-2 text-sm font-semibold lg:hidden [&::-webkit-details-marker]:hidden">
      <Icon aria-hidden="true" className="h-5 w-5 shrink-0 text-muted-foreground" />
      <span className="min-w-0 flex-1">{title}</span>
      <ChevronDown aria-hidden="true" className="h-4 w-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-180 motion-reduce:transition-none" />
    </summary>
    <div className="space-y-3 border-t p-2.5 lg:space-y-6 lg:border-0 lg:p-0">{children}</div>
  </details>;
}
