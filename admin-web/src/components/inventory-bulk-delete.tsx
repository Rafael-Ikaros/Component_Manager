import { useEffect, useId, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, postJson } from "@/lib/api";
import { formatCount, useI18n } from "@/lib/i18n";
import {
  clearPendingRequest, loadPendingRequest, pendingStorageKey, requestForPayload,
  savePendingRequest, shouldRetainPending, type PendingRequest,
} from "@/lib/pending-request";
import type { AdminComponentListItem } from "@/lib/types";

interface DeleteSelection { id: string; expected_updated_at: string }
interface BulkDeleteProps {
  selecting: boolean;
  onFinishSelection: () => void;
  selected: AdminComponentListItem[];
  disabled: boolean;
  allSelected: boolean;
  onSelectAll: () => void;
  onClear: () => void;
  onDeleted: (ids: string[]) => void;
  onRefresh: () => void;
}

export function InventoryBulkDelete({ selecting, onFinishSelection, selected, disabled, allSelected, onSelectAll, onClear, onDeleted, onRefresh }: BulkDeleteProps) {
  const { t, locale } = useI18n();
  const { session, identity, logout } = useAuth();
  const navigate = useNavigate();
  const route = useLocation();
  const dialog = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const [draft, setDraft] = useState<DeleteSelection[]>([]);
  const [pending, setPending] = useState<PendingRequest | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ error: boolean; text: string } | null>(null);
  const storageKey = session ? pendingStorageKey(session.apiBaseUrl, "bulk-delete", identity?.account_id) : "";

  useEffect(() => {
    setPending(storageKey ? loadPendingRequest(storageKey) : null);
  }, [storageKey]);

  function openConfirmation() {
    const items = pending
      ? (JSON.parse(pending.payload) as { items: DeleteSelection[] }).items
      : selected.map((item) => ({ id: item.id, expected_updated_at: item.updated_at }));
    setDraft(items);
    setNotice(null);
    dialog.current?.showModal();
    setDialogOpen(true);
  }

  async function removeSelected() {
    if (!session || busy || !draft.length) return;
    const request = requestForPayload(pending, { items: draft });
    setPending(request);
    const persisted = savePendingRequest(storageKey, request);
    setBusy(true);
    setNotice(null);
    try {
      const result = await postJson<{ items: Array<{ id: string; deleted: boolean }> }>(
        session, "/admin-api/components/bulk-delete", { items: draft, request_id: request.requestId },
      );
      clearPendingRequest(storageKey);
      setPending(null);
      dialog.current?.close();
      setNotice({ error: false, text: `${t("已删除所选元器件：")}${formatCount(result.items.length, locale)}` });
      onDeleted(result.items.map((item) => item.id));
    } catch (error) {
      const retain = !(error instanceof ApiError) || shouldRetainPending(error.status);
      if (!retain) {
        clearPendingRequest(storageKey);
        setPending(null);
      }
      if (error instanceof ApiError && error.status === 401) {
        logout();
        navigate("/login", { replace: true, state: { from: `${route.pathname}${route.search}`, reason: t("登录会话已失效。") } });
        return;
      }
      const message = error instanceof Error ? error.message : t("操作失败，请稍后重试。");
      setNotice({ error: true, text: `${message} ${retain
        ? t("请重试原批次以确认结果；重试不会重复删除。") + (!persisted ? ` ${t("浏览器无法保存恢复信息，请保持本页打开并使用原表单重试。")}` : "")
        : t("请关闭确认框，刷新库存核对当前状态后重新选择。")}` });
    } finally {
      setBusy(false);
    }
  }

  return <div className={selecting || pending ? "sticky bottom-0 z-10 mt-4 space-y-2 bg-background pb-[env(safe-area-inset-bottom)]" : "mt-4 space-y-2"}>
    {selecting || pending ? <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border bg-slate-50 p-2.5">
      <div className="space-y-1 text-sm">
        <p aria-live="polite">{t("本页已选：")}{formatCount(selected.length, locale)}</p>
        <p className="text-xs text-muted-foreground">{t("仅选择当前页；翻页或更改筛选会清空选择。")}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        {selecting && !pending ? <>
          <Button variant="outline" size="sm" className="min-h-11" disabled={disabled || busy} onClick={allSelected ? onClear : onSelectAll}>{allSelected ? t("取消本页选择") : t("选择本页")}</Button>
          <Button variant="ghost" size="sm" className="min-h-11" disabled={busy} onClick={onFinishSelection}>{locale === "zh-CN" ? "退出多选" : "Exit selection"}</Button>
        </> : null}
        <Button variant="destructive" size="sm" className="min-h-11" disabled={disabled || busy || (!selected.length && !pending)} onClick={openConfirmation}>{pending ? t("恢复批量删除") : t("删除所选")}</Button>
      </div>
    </div> : null}
    {notice && !dialogOpen ? <Alert variant={notice.error ? "destructive" : "default"}><AlertTitle>{notice.error ? t("批量删除未完成") : t("批量删除已完成")}</AlertTitle><AlertDescription>{notice.text}</AlertDescription></Alert> : null}
    <dialog ref={dialog} aria-labelledby={titleId} aria-describedby={descriptionId} className="m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-2xl border bg-white p-4 text-foreground shadow-xl backdrop:bg-slate-950/40 sm:p-6" onClose={() => setDialogOpen(false)} onCancel={(event) => { if (busy) event.preventDefault(); }}>
      <div className="space-y-4">
        <h3 id={titleId} className="text-lg font-semibold">{t("确认批量删除")}</h3>
        <div id={descriptionId} className="space-y-2 text-sm">
          <p>{t("即将从当前账户删除所选元器件：")}{formatCount(draft.length, locale)}</p>
          <p>{t("删除会同步到客户端，历史流水仍会保留。本批次全部成功或全部取消。")}</p>
          <ul className="max-h-40 list-inside list-disc overflow-auto rounded-lg bg-slate-50 p-3 text-muted-foreground">{draft.map((item) => <li key={item.id} className="break-all">{selected.find((selectedItem) => selectedItem.id === item.id)?.sku ?? item.id}</li>)}</ul>
        </div>
        {notice?.error ? <Alert variant="destructive"><AlertTitle>{t("批量删除未完成")}</AlertTitle><AlertDescription>{notice.text}</AlertDescription></Alert> : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={() => dialog.current?.close()}>{t("取消")}</Button>
          {notice?.error && !pending ? <Button variant="outline" onClick={() => { dialog.current?.close(); onRefresh(); }}>{t("刷新库存详情")}</Button> : null}
          <Button variant="destructive" disabled={busy || disabled} onClick={() => void removeSelected()}>{busy ? t("正在删除…") : pending ? t("重试原批次") : t("确认删除")}</Button>
        </div>
      </div>
    </dialog>
  </div>;
}
