import { useI18n } from "@/lib/i18n";
import { ClipboardList, DatabaseZap, LayoutDashboard, LogOut, Settings, ShieldCheck, UserRound } from "lucide-react";
import { useEffect, useRef } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useAuth } from "@/hooks/use-auth";

const navigationItems = [
  { to: "/dashboard", label: "概览", icon: LayoutDashboard },
  { to: "/inventory", label: "库存", icon: DatabaseZap },
  { to: "/bom-presets", label: "BOM 预设", icon: ClipboardList },
  { to: "/sync", label: "同步", icon: ShieldCheck },
  { to: "/settings", label: "设置", icon: Settings },
] as const;

export function AppShell() {
  const { t, locale } = useI18n();
  const { identity, logout, session, logoutError } = useAuth();
  const { pathname } = useLocation();
  const accountDialog = useRef<HTMLDialogElement>(null);
  const accountLabel = locale === "zh-CN" ? "当前账户" : "Current account";

  useEffect(() => {
    if (!window.matchMedia("(min-width: 1024px)").matches) window.scrollTo(0, 0);
  }, [pathname]);

  return (
    <div className="min-h-screen">
      <div className="mx-auto flex min-h-screen max-w-[1600px] flex-col lg:flex-row">
        <aside className="hidden w-72 shrink-0 border-r border-white/60 bg-white/85 px-6 py-6 backdrop-blur lg:block">
          <div className="space-y-2">
            <Badge variant="secondary" className="rounded-full px-3 py-1">
              {t("独立管理台")}
            </Badge>
            <div>
              <div className="flex items-center gap-2">
                <img src="/component-vault-logo.png" alt={locale === "zh-CN" ? "Component Vault 标志" : "Component Vault logo"} width={36} height={36} className="h-9 w-9 shrink-0 object-contain" />
                <h1 className="text-xl font-semibold tracking-tight">Component Vault</h1>
              </div>
              <p className="text-sm text-muted-foreground">
                {t("库存默认只读，服务端启用后可操作；同时支持 MQTT 配置。")}
              </p>
            </div>
          </div>

          <nav className="mt-4 grid grid-cols-2 gap-1.5 lg:mt-8 lg:grid-cols-1 lg:gap-2">
            {navigationItems.map(({ icon: Icon, label, to }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium transition-colors lg:gap-3 lg:rounded-2xl lg:px-4 lg:py-3",
                    isActive
                      ? "bg-primary text-primary-foreground shadow-sm"
                      : "text-slate-700 hover:bg-slate-100",
                  )
                }
              >
                <Icon className="h-4 w-4" />
                {t(label)}
              </NavLink>
            ))}
          </nav>
        </aside>

        <div className="min-w-0 flex-1">
          <header className="flex min-h-12 items-center justify-between border-b bg-card px-3 lg:hidden">
            <div className="flex min-w-0 items-center gap-2">
              <img src="/component-vault-logo.png" alt="" width={26} height={26} className="h-[26px] w-[26px] shrink-0 object-contain" />
              <span className="truncate text-sm font-semibold">Component Vault</span>
            </div>
            <Button variant="ghost" className="h-11 w-11 shrink-0 p-0" aria-label={accountLabel} onClick={() => accountDialog.current?.showModal()}>
              <UserRound aria-hidden="true" className="h-5 w-5" />
            </Button>
          </header>
          <dialog ref={accountDialog} aria-label={accountLabel} className="fixed inset-x-0 bottom-0 top-auto m-0 max-h-[85dvh] w-full max-w-none overflow-y-auto rounded-t-2xl border bg-card p-4 pb-[max(1rem,env(safe-area-inset-bottom))] text-foreground backdrop:bg-slate-950/40">
            <div className="flex items-center justify-between gap-2">
              <h2 className="font-semibold">{accountLabel}</h2>
              <Button variant="ghost" className="min-h-11" onClick={() => accountDialog.current?.close()}>{locale === "zh-CN" ? "关闭" : "Close"}</Button>
            </div>
            <div className="mt-2 space-y-2 border-t pt-4 text-sm">
              <p className="font-medium">{t("已连接 API")}</p>
              <p className="break-all text-muted-foreground">{session?.apiBaseUrl}</p>
              {identity ? <p className="break-words">{identity.name} · {identity.role === "admin" ? t("管理员") : t("普通用户")}</p> : null}
              <Button variant="outline" className="min-h-11" onClick={() => { accountDialog.current?.close(); void logout(); }}>{t("退出登录")}</Button>
            </div>
          </dialog>
          <header className="sticky top-0 z-20 hidden border-b border-white/70 bg-background/75 px-6 py-4 backdrop-blur lg:block">
            <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
              <div>
                <p className="text-sm font-medium text-slate-600">{t("已连接 API")}</p>
                <p className="break-all text-sm text-slate-500">{session?.apiBaseUrl}</p>
                {identity ? <p className="text-sm text-slate-600">{identity.role === "admin" && identity.name === "Administrator" ? t("管理员") : `${identity.name} · ${identity.role === "admin" ? t("管理员") : t("普通用户")}`}</p> : null}
              </div>
              <div className="flex items-center gap-3">
                <Badge variant="success">{t("会话有效")}</Badge>
                <Button variant="outline" onClick={logout}>
                  <LogOut className="mr-2 h-4 w-4" />
                  {t("退出登录")}
                </Button>
              </div>
            </div>
          </header>

          <main className="px-3 pt-4 pb-[calc(var(--mobile-nav-height)+1rem)] sm:px-5 lg:p-6">
            {logoutError ? <p role="alert" className="mb-4 text-red-700">{t("退出失败，请重试。")}{logoutError}</p> : null}
            <Outlet />
          </main>
          <nav aria-label={locale === "zh-CN" ? "主导航" : "Main navigation"} className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-5 border-t bg-card pb-[env(safe-area-inset-bottom)] lg:hidden">
            {navigationItems.map(({ icon: Icon, label, to }) => (
              <NavLink key={to} to={to} aria-label={t(label)} className={({ isActive }) =>
                cn("flex h-14 min-w-0 flex-col items-center justify-center gap-0.5 text-[11px] font-medium focus-visible:outline focus-visible:outline-2 focus-visible:outline-inset focus-visible:outline-ring",
                  isActive ? "text-primary" : "text-muted-foreground")}>
                {({ isActive }) => <>
                  <span className={cn("flex h-6 w-10 items-center justify-center rounded-lg", isActive && "bg-primary text-primary-foreground")}>
                    <Icon className="h-4 w-4" aria-hidden="true" />
                  </span>
                  <span>{to === "/bom-presets" ? "BOM" : t(label)}</span>
                </>}
              </NavLink>
            ))}
          </nav>
        </div>
      </div>
    </div>
  );
}
