import { useState, type FormEvent } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, deleteJson, patchJson, postJson } from "@/lib/api";
import { copyText } from "@/lib/clipboard";
import { useI18n } from "@/lib/i18n";
import type { IssuedKey, ManagedAccount } from "@/lib/types";

export function AccountsSettingsCard() {
  const { t } = useI18n();
  const { session, logout } = useAuth();
  const resource = useAdminResource<ManagedAccount[]>("/admin-api/accounts");
  const [name, setName] = useState("");
  const [names, setNames] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [issuedKey, setIssuedKey] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [pendingRotate, setPendingRotate] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<string | null>(null);
  const [deleteName, setDeleteName] = useState("");

  async function act(key: string, operation: () => Promise<unknown>, success: string) {
    setBusy(key);
    setError(null);
    setNotice(null);
    try {
      await operation();
      setNotice(success);
      await resource.reload();
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 401) { logout(); return; }
      setError(caught instanceof Error ? caught.message : t("账户操作失败。"));
    } finally { setBusy(null); }
  }

  function create(event: FormEvent) {
    event.preventDefault();
    if (!session || !name.trim()) { setError(t("请输入用户名。")); return; }
    void act("create", async () => {
      const created = await postJson<ManagedAccount & IssuedKey>(session, "/admin-api/accounts", { name: name.trim() });
      setName("");
      setIssuedKey(created.api_token);
      setCopied(false);
    }, t("账户已创建。"));
  }

  return <Card className="bg-white/90">
    <CardHeader><CardTitle>{t("账户管理")}</CardTitle><CardDescription>{t("仅管理员可修改服务端配置和管理账户。")}</CardDescription></CardHeader>
    <CardContent className="space-y-5">
      {resource.error ? <Alert variant="destructive"><AlertTitle>{t("无法读取账户")}</AlertTitle><AlertDescription>{resource.error} <Button size="sm" variant="outline" onClick={() => void resource.reload()}>{t("重试")}</Button></AlertDescription></Alert> : null}
      <form className="flex flex-col gap-2 sm:flex-row sm:items-end" onSubmit={create}>
        <label className="min-w-0 flex-1 space-y-2 text-sm font-medium"><span>{t("用户名")}</span><Input value={name} maxLength={120} required disabled={busy !== null} onChange={(event) => setName(event.target.value)} /></label>
        <Button type="submit" className="min-h-11 sm:min-h-0" disabled={busy !== null}>{t("创建用户")}</Button>
      </form>
      {issuedKey ? <Alert><AlertTitle>{t("新密钥仅显示一次，请立即复制并妥善保存。")}</AlertTitle><AlertDescription className="space-y-2"><Input readOnly aria-label="API Token" value={issuedKey} className="font-mono" /><div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" className="min-h-11 sm:min-h-0" onClick={async () => setCopied(await copyText(issuedKey))}>{copied ? t("已复制") : t("复制密钥")}</Button><Button size="sm" variant="ghost" className="min-h-11 sm:min-h-0" onClick={() => { setIssuedKey(null); setCopied(false); }}>{t("关闭密钥提示")}</Button></div></AlertDescription></Alert> : null}
      {notice ? <p className="text-sm text-emerald-700" role="status">{notice}</p> : null}
      {error ? <p className="text-sm text-red-700" role="alert">{error}</p> : null}
      {resource.loading && !resource.data ? <div className="h-24 animate-pulse rounded-xl bg-slate-100" /> : null}
      {resource.data?.length === 0 ? <p className="text-sm text-muted-foreground">{t("暂无普通用户账户。")}</p> : null}
      <div className="space-y-3">
        {resource.data?.map((account) => {
          const draft = names[account.account_id] ?? account.name;
          return <div key={account.account_id} className="min-w-0 rounded-xl border border-slate-200 p-3 sm:p-4">
            <div className="flex flex-wrap items-center justify-between gap-2"><p className="min-w-0 text-xs text-slate-500 [overflow-wrap:anywhere]">{t("普通用户")} · {account.account_id}</p><p className="text-xs text-slate-600">{account.active ? t("账户已启用") : t("账户已停用")}</p></div>
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <label className="min-w-0 basis-full space-y-2 text-sm font-medium sm:flex-1 sm:basis-52"><span>{t("用户名")}</span><Input value={draft} maxLength={120} disabled={busy !== null} onChange={(event) => setNames((previous) => ({ ...previous, [account.account_id]: event.target.value }))} /></label>
              <Button size="sm" variant="outline" className="min-h-11 sm:min-h-0" disabled={busy !== null || !draft.trim() || draft.trim() === account.name} onClick={() => { if (!session) return; void act(account.account_id, () => patchJson(session, `/admin-api/accounts/${encodeURIComponent(account.account_id)}`, { name: draft.trim() }), t("账户已更新。")); }}>{t("保存名称")}</Button>
              <Button size="sm" variant="outline" className="min-h-11 sm:min-h-0" disabled={busy !== null} onClick={() => { if (!session) return; void act(account.account_id, () => patchJson(session, `/admin-api/accounts/${encodeURIComponent(account.account_id)}`, { active: !account.active }), t("账户已更新。")); }}>{account.active ? t("停用账户") : t("启用账户")}</Button>
              <Button size="sm" variant="outline" className="min-h-11 sm:min-h-0" disabled={busy !== null} onClick={() => setPendingRotate(account.account_id)}>{t("重置密钥")}</Button>
              <Button size="sm" variant="destructive" className="min-h-11 sm:min-h-0" disabled={busy !== null} onClick={() => { setPendingDelete(account.account_id); setDeleteName(""); setPendingRotate(null); }}>{t("删除账户")}</Button>
            </div>
            {pendingDelete === account.account_id ? <div role="group" aria-label={t("删除账户")} className="mt-4 space-y-3 rounded-lg border border-red-200 bg-red-50 p-4">
              <p className="text-sm text-red-900">{t("这会永久删除该账户及其服务端库存、流水和同步记录，无法撤销。客户端本地数据不会被远程删除。")}</p>
              <label className="block text-sm [overflow-wrap:anywhere]">{t("输入账户名称确认：")}<strong>{account.name}</strong><Input className="mt-2" autoComplete="off" value={deleteName} onChange={(event) => setDeleteName(event.target.value)} /></label>
              <div className="flex flex-wrap gap-2"><Button size="sm" variant="destructive" className="min-h-11 sm:min-h-0" disabled={busy !== null || deleteName !== account.name} onClick={() => {
                if (!session) return;
                void act(account.account_id, async () => {
                  await deleteJson(session, `/admin-api/accounts/${encodeURIComponent(account.account_id)}`);
                  setPendingDelete(null); setIssuedKey(null);
                }, t("账户和服务端数据已删除。"));
              }}>{t("永久删除")}</Button><Button size="sm" variant="outline" className="min-h-11 sm:min-h-0" disabled={busy !== null} onClick={() => setPendingDelete(null)}>{t("取消")}</Button></div>
            </div> : null}
            {pendingRotate === account.account_id ? <div className="mt-3 rounded-lg bg-amber-50 p-3 text-sm"><p>{t("重置后旧密钥会立即失效。")}</p><div className="mt-2 flex flex-wrap gap-2"><Button size="sm" className="min-h-11 sm:min-h-0" disabled={busy !== null} onClick={() => { if (!session) return; setPendingRotate(null); void act(account.account_id, async () => { const rotated = await postJson<IssuedKey>(session, `/admin-api/accounts/${encodeURIComponent(account.account_id)}/rotate-key`, {}); setIssuedKey(rotated.api_token); setCopied(false); }, t("密钥已重置。")); }}>{t("确认重置密钥")}</Button><Button size="sm" variant="ghost" className="min-h-11 sm:min-h-0" onClick={() => setPendingRotate(null)}>{t("取消重置")}</Button></div></div> : null}
          </div>;
        })}
      </div>
    </CardContent>
  </Card>;
}
