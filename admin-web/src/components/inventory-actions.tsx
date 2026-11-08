import { currentTranslation, formatCount, useI18n } from "@/lib/i18n";
import { useEffect, useId, useMemo, useRef, useState, type FormEvent } from "react";
import { RefreshCw } from "lucide-react";
import { useLocation, useNavigate } from "react-router-dom";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/hooks/use-auth";
import { ApiError, deleteJson, postJson, putJson } from "@/lib/api";
import {
  clearPendingRequest, loadPendingRequest, pendingStorageKey, requestForPayload, savePendingRequest, shouldRetainPending,
} from "@/lib/pending-request";
import type { AdminComponentDetail, AdminStorageLocation } from "@/lib/types";
import { purchaseAmount } from "@/lib/purchase-amount";

interface InventoryActionsProps {
  scope: "create" | "component";
  enabled: boolean;
  component: AdminComponentDetail | null;
  locations: AdminStorageLocation[];
  onChanged: (component?: AdminComponentDetail) => void;
  onDeleted?: () => void;
  onLocationsChanged: () => void;
}

const emptyComponent = {
  sku: "", name: "", category: "", package_name: "", description: "", min_stock: 0, location_id: "",
};

export function InventoryActions({ scope, enabled, component, locations, onChanged, onDeleted, onLocationsChanged }: InventoryActionsProps) {
  const { t, locale } = useI18n();
  const { identity, logout, session } = useAuth();
  const navigate = useNavigate();
  const route = useLocation();
  const [locationDraft, setLocationDraft] = useState({ id: "", name: "" });
  const [createDraft, setCreateDraft] = useState(emptyComponent);
  const [editDraft, setEditDraft] = useState(emptyComponent);
  const [movement, setMovement] = useState({ movement_type: "inbound", quantity: 1, reason: currentTranslation("库存操作"), note: "", location_id: "" });
  const [busy, setBusy] = useState<string | null>(null);
  const [purchaseValue, setPurchaseValue] = useState("");
  const [purchaseMode, setPurchaseMode] = useState<"total" | "unit">("total");
  const [inventoryAmount, setInventoryAmount] = useState("");
  const [notice, setNotice] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [recoverableMovement, setRecoverableMovement] = useState<ReturnType<typeof loadPendingRequest>>(null);
  const memoryPending = useRef(new Map<string, NonNullable<ReturnType<typeof loadPendingRequest>>>());
  const previousComponentId = useRef<string | null>(null);
  const [createPanel, setCreatePanel] = useState<"location" | "component" | null>(null);
  const [componentPanel, setComponentPanel] = useState<"edit" | "movement" | "delete" | "cost" | null>(null);
  const locationCodeId = useId();
  const locationNameId = useId();
  const movementTypeId = useId();
  const purchaseModeId = useId();

  useEffect(() => {
    if (!component) return;
    if (previousComponentId.current !== component.id) {
      setComponentPanel(null);
      setPurchaseValue("");
      setInventoryAmount("");
      previousComponentId.current = component.id;
    }
    setEditDraft({
      sku: component.sku, name: component.name, category: component.category,
      package_name: component.package_name, description: component.description ?? "",
      min_stock: component.min_stock, location_id: "",
    });
    setMovement((value) => ({ ...value, location_id: "" }));
  }, [component]);

  useEffect(() => {
    if (!session || !component) {
      setRecoverableMovement(null);
      return;
    }
    const key = pendingStorageKey(session.apiBaseUrl, `movement:${component.id}`, identity?.account_id);
    setRecoverableMovement(loadPendingRequest(key) ?? memoryPending.current.get(key) ?? null);
  }, [component, identity?.account_id, session]);

  const movementLocations = useMemo(() => {
    if (!component?.inventory_managed) return [];
    if (movement.movement_type === "inbound") return locations;
    const quantities = new Map(component.allocations.map((item) => [item.location_id, item.quantity]));
    return locations.filter((item) => (quantities.get(item.id) ?? 0) > 0);
  }, [component, locations, movement.movement_type]);

  async function submit<T>(key: string, payload: object, request: (body: object) => Promise<T>, done: (value: T) => void) {
    if (!session) return;
    const storageKey = pendingStorageKey(session.apiBaseUrl, key, identity?.account_id);
    const previous = loadPendingRequest(storageKey) ?? memoryPending.current.get(storageKey) ?? null;
    const pending = requestForPayload(previous, payload);
    memoryPending.current.set(storageKey, pending);
    const persisted = savePendingRequest(storageKey, pending);
    if (key.startsWith("movement:")) setRecoverableMovement(pending);
    setBusy(key);
    setNotice(null);
    try {
      const value = await request({ ...payload, request_id: pending.requestId });
      clearPendingRequest(storageKey);
      memoryPending.current.delete(storageKey);
      if (key.startsWith("movement:")) setRecoverableMovement(null);
      done(value);
    } catch (error) {
      if (error instanceof ApiError && !shouldRetainPending(error.status)) {
        clearPendingRequest(storageKey);
        memoryPending.current.delete(storageKey);
        if (key.startsWith("movement:")) setRecoverableMovement(null);
      }
      if (error instanceof ApiError && error.status === 401) {
        logout();
        navigate("/login", { replace: true, state: { from: `${route.pathname}${route.search}`, reason: t("登录已失效，请重新验证 API 令牌。") } });
        return;
      }
      if (error instanceof ApiError && error.status === 409) onChanged();
      const baseText = error instanceof ApiError ? error.message : t("操作失败，请稍后重试。");
      const text = !persisted && error instanceof ApiError && error.status === 0
        ? `${baseText} ${t("浏览器无法保存恢复信息，请保持本页打开并使用原表单重试。")}`
        : baseText;
      setNotice({ kind: "error", text });
    } finally {
      setBusy(null);
    }
  }

  function createLocation(event: FormEvent) {
    event.preventDefault();
    const payload = { id: locationDraft.id.trim(), name: locationDraft.name.trim() };
    void submit<AdminStorageLocation>("create-location", payload, (body) => postJson(session!, "/admin-api/storage-locations", body), () => {
      setLocationDraft({ id: "", name: "" });
      setCreatePanel(null);
      setNotice({ kind: "success", text: t("库位已创建，可以用于新元件和入库。") });
      onLocationsChanged();
    });
  }

  function createComponent(event: FormEvent) {
    event.preventDefault();
    const payload = { ...createDraft, sku: createDraft.sku.trim(), name: createDraft.name.trim(), category: createDraft.category.trim(), package_name: createDraft.package_name.trim(), description: createDraft.description.trim() || null, min_stock: Number(createDraft.min_stock) };
    void submit<AdminComponentDetail>("create-component", payload, (body) => postJson(session!, "/admin-api/components", body), (value) => {
      setCreateDraft(emptyComponent);
      setCreatePanel(null);
      setNotice({ kind: "success", text: `${t("已创建")} ${value.sku}${t("，初始库存为 0。")}` });
      onChanged(value);
    });
  }

  function updateComponent(event: FormEvent) {
    event.preventDefault();
    if (!component) return;
    const payload = { expected_updated_at: component.updated_at, sku: editDraft.sku.trim(), name: editDraft.name.trim(), category: editDraft.category.trim(), package_name: editDraft.package_name.trim(), description: editDraft.description.trim() || null, min_stock: Number(editDraft.min_stock) };
    void submit<AdminComponentDetail>(`edit:${component.id}`, payload, (body) => putJson(session!, `/admin-api/components/${encodeURIComponent(component.id)}`, body), (value) => {
      setNotice({ kind: "success", text: t("元器件资料已保存。") });
      onChanged(value);
    });
  }

  function removeComponent() {
    if (!component) return;
    const payload = { expected_updated_at: component.updated_at };
    void submit<{ id: string; deleted: boolean }>(
      `delete:${component.id}`,
      payload,
      (body) => deleteJson(session!, `/admin-api/components/${encodeURIComponent(component.id)}`, body),
      () => onDeleted?.(),
    );
  }

  function applyMovement(event: FormEvent) {
    event.preventDefault();
    if (!component) return;
    if (recoverableMovement) {
      setNotice({ kind: "error", text: t("请先恢复并确认上一笔库存操作，再发起新的入出库。") });
      return;
    }
    const payload: Record<string, unknown> = {
      expected_updated_at: component.updated_at,
      movement_type: movement.movement_type,
      quantity: Number(movement.quantity),
      reason: movement.reason.trim(),
      note: movement.note.trim() || null,
    };
    try {
      payload.purchase_amount = movement.movement_type === "inbound"
        ? purchaseAmount(purchaseValue, Number(movement.quantity), purchaseMode) : null;
    } catch {
      setNotice({ kind: "error", text: t("金额必须为非负数，最多8位小数。") });
      return;
    }
    payload.location_id = component.inventory_managed ? movement.location_id : null;
    void submit<AdminComponentDetail>(`movement:${component.id}`, payload, (body) => postJson(session!, `/admin-api/components/${encodeURIComponent(component.id)}/movements`, body), (value) => {
      setNotice({ kind: "success", text: `${movement.movement_type === "inbound" ? t("入库") : t("出库")}${t("完成，当前库存")}${formatCount(value.quantity, locale)}${t("。")}` });
      setMovement((item) => ({ ...item, quantity: 1, note: "", location_id: "" }));
      setPurchaseValue("");
      onChanged(value);
    });
  }

  function backfillCost(event: FormEvent) {
    event.preventDefault();
    if (!component) return;
    let total: string | null;
    try { total = purchaseAmount(inventoryAmount, component.quantity, "total"); }
    catch { total = null; }
    if (total === null) {
      setNotice({ kind: "error", text: t("金额必须为非负数，最多8位小数。") });
      return;
    }
    void submit<AdminComponentDetail>(`cost:${component.id}`, {
      expected_updated_at: component.updated_at, inventory_amount: total,
    }, (body) => postJson(session!, `/admin-api/components/${encodeURIComponent(component.id)}/cost`, body), (value) => {
      setInventoryAmount("");
      setComponentPanel(null);
      setNotice({ kind: "success", text: t("当前库存成本已保存，数量与历史采购记录未改变。") });
      onChanged(value);
    });
  }

  function recoverMovement() {
    if (!component || !recoverableMovement) return;
    const payload = JSON.parse(recoverableMovement.payload) as Record<string, unknown>;
    void submit<AdminComponentDetail>(`movement:${component.id}`, payload, (body) => postJson(session!, `/admin-api/components/${encodeURIComponent(component.id)}/movements`, body), (value) => {
      setNotice({ kind: "success", text: `${t("已通过原请求编号核对操作结果，当前库存")}${formatCount(value.quantity, locale)}${t("。")}` });
      onChanged(value);
    });
  }

  if (!enabled) {
    return scope === "create" ? <Alert><AlertTitle>{t("当前为只读模式")}</AlertTitle><AlertDescription>{t("服务端未启用 Web 库存操作。你仍可搜索、筛选和查看库存详情。")}</AlertDescription></Alert> : null;
  }

  if (scope === "create") return (
    <div className="space-y-5">
      {notice ? <Alert variant={notice.kind === "error" ? "destructive" : "default"}><AlertTitle>{notice.kind === "error" ? t("操作未完成") : t("操作完成")}</AlertTitle><AlertDescription>{notice.text}</AlertDescription></Alert> : null}
      <Card className="bg-white/90"><CardContent className="flex flex-wrap gap-2 pt-6">
        <Button type="button" variant={createPanel === "component" ? "default" : "outline"} onClick={() => setCreatePanel(createPanel === "component" ? null : "component")}>{createPanel === "component" ? t("收起新建元器件") : t("新建元器件")}</Button>
        <Button type="button" variant={createPanel === "location" ? "default" : "outline"} onClick={() => setCreatePanel(createPanel === "location" ? null : "location")}>{createPanel === "location" ? t("收起创建库位") : t("创建库位")}</Button>
      </CardContent></Card>
      {createPanel === "location" ? <Card className="bg-white/90"><CardHeader className="flex flex-row items-center justify-between gap-3"><CardTitle>{t("创建库位")}</CardTitle><Button type="button" size="sm" variant="ghost" onClick={() => setCreatePanel(null)}>{t("取消")}</Button></CardHeader><CardContent>
          <form className="flex flex-col gap-3 sm:flex-row sm:items-end" onSubmit={createLocation}>
            <Field label={t("库位编码")} labelFor={locationCodeId} className="flex-1"><Input id={locationCodeId} required minLength={1} maxLength={120} value={locationDraft.id} onChange={(event) => setLocationDraft({ ...locationDraft, id: event.target.value })} placeholder={t("例如 A01 或 主仓-01")} disabled={busy === "create-location"} /></Field>
            <Field label={t("库位名称")} labelFor={locationNameId} className="flex-1"><Input id={locationNameId} required maxLength={200} value={locationDraft.name} onChange={(event) => setLocationDraft({ ...locationDraft, name: event.target.value })} placeholder={t("例如 主货架 A-01")} disabled={busy === "create-location"} /></Field>
            <Button className="min-h-11" disabled={busy !== null}>{busy === "create-location" ? t("正在创建…") : t("创建库位")}</Button>
          </form>
        </CardContent></Card> : null}
      {createPanel === "component" ? <Card className="bg-white/90"><CardHeader className="flex flex-row items-center justify-between gap-3"><CardTitle>{t("新建元器件")}</CardTitle><Button type="button" size="sm" variant="ghost" onClick={() => setCreatePanel(null)}>{t("取消")}</Button></CardHeader><CardContent>
          {locations.length === 0 ? <p className="mb-4 text-sm text-amber-700">{t("请先创建一个库位。新元件会以 0 库存建立初始分配。")}</p> : null}
          <form className="grid gap-3 sm:grid-cols-2" onSubmit={createComponent}>
            <TextField label={t("料号")} required value={createDraft.sku} onChange={(value) => setCreateDraft({ ...createDraft, sku: value })} />
            <TextField label={t("名称")} required value={createDraft.name} onChange={(value) => setCreateDraft({ ...createDraft, name: value })} />
            <TextField label={t("分类")} required value={createDraft.category} onChange={(value) => setCreateDraft({ ...createDraft, category: value })} />
            <TextField label={t("封装")} required value={createDraft.package_name} onChange={(value) => setCreateDraft({ ...createDraft, package_name: value })} />
            <TextField label={t("最低库存")} type="number" min="0" required value={String(createDraft.min_stock)} onChange={(value) => setCreateDraft({ ...createDraft, min_stock: Number(value) })} />
            <LocationSelect label={t("初始库位")} required locations={locations} value={createDraft.location_id} onChange={(value) => setCreateDraft({ ...createDraft, location_id: value })} />
            <TextField label={t("说明（可选）")} className="sm:col-span-2" value={createDraft.description} onChange={(value) => setCreateDraft({ ...createDraft, description: value })} />
            <Button className="min-h-11 sm:col-span-2" disabled={busy !== null || locations.length === 0}>{busy === "create-component" ? t("正在创建…") : t("创建元器件")}</Button>
          </form>
        </CardContent></Card> : null}
    </div>
  );

  if (!component) return null;
  const projectedQuantity = movement.movement_type === "inbound"
    ? component.quantity + Number(movement.quantity || 0)
    : Math.max(0, component.quantity - Number(movement.quantity || 0));
  return (
    <div className="mt-5 space-y-4 border-t pt-5">
      {notice ? <Alert variant={notice.kind === "error" ? "destructive" : "default"}><AlertTitle>{notice.kind === "error" ? t("操作未完成") : t("操作完成")}</AlertTitle><AlertDescription>{notice.text}</AlertDescription></Alert> : null}
      {recoverableMovement ? <Alert><AlertTitle>{t("有一笔结果未确认的库存操作")}</AlertTitle><AlertDescription className="space-y-3"><p>{t("请先恢复上一笔操作。系统会识别这次重试，不会重复增加或扣减库存。你也可以先刷新详情核对最新库存。")}</p><div className="flex flex-wrap gap-2"><Button type="button" size="sm" disabled={busy !== null} onClick={recoverMovement}>{t("恢复上次操作")}</Button><Button type="button" size="sm" variant="outline" disabled={busy !== null} onClick={() => onChanged()}>{t("刷新库存详情")}</Button></div></AlertDescription></Alert> : null}
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant={componentPanel === "edit" ? "default" : "outline"} onClick={() => setComponentPanel(componentPanel === "edit" ? null : "edit")}>{componentPanel === "edit" ? t("收起编辑") : t("编辑资料")}</Button>
        <Button type="button" variant={componentPanel === "movement" ? "default" : "outline"} onClick={() => setComponentPanel(componentPanel === "movement" ? null : "movement")}>{componentPanel === "movement" ? t("收起入出库") : t("办理入出库")}</Button>
        <Button type="button" disabled={busy !== null || component.quantity === 0 || recoverableMovement !== null} variant={componentPanel === "cost" ? "default" : "outline"} onClick={() => setComponentPanel(componentPanel === "cost" ? null : "cost")}>{t("补录当前库存成本")}</Button>
        <Button type="button" variant={componentPanel === "delete" ? "destructive" : "outline"} onClick={() => setComponentPanel(componentPanel === "delete" ? null : "delete")}>{t("删除元器件")}</Button>
      </div>
      {componentPanel === "delete" ? <Card className="border-red-200 bg-red-50/70"><CardHeader><CardTitle>{t("删除元器件")} {component.sku}</CardTitle></CardHeader><CardContent className="space-y-4"><p className="text-sm">{t("删除后，此元件将从当前账户的库存列表中移除，并同步到客户端。历史流水仍会保留。")}</p><div className="flex flex-wrap gap-2"><Button type="button" variant="destructive" disabled={busy !== null} onClick={removeComponent}>{busy === `delete:${component.id}` ? t("正在删除…") : t("确认删除")}</Button><Button type="button" variant="outline" disabled={busy !== null} onClick={() => setComponentPanel(null)}>{t("取消")}</Button></div></CardContent></Card> : null}
      {componentPanel === "edit" ? <Card className="bg-slate-50/70"><CardHeader className="flex flex-row items-center justify-between gap-3"><CardTitle>{t("编辑")} {component.sku}</CardTitle><Button type="button" size="sm" variant="ghost" onClick={() => setComponentPanel(null)}>{t("取消")}</Button></CardHeader><CardContent>
          <form className="grid gap-3 sm:grid-cols-2" onSubmit={updateComponent}>
            <TextField label={t("料号")} required value={editDraft.sku} onChange={(value) => setEditDraft({ ...editDraft, sku: value })} />
            <TextField label={t("名称")} required value={editDraft.name} onChange={(value) => setEditDraft({ ...editDraft, name: value })} />
            <TextField label={t("分类")} required value={editDraft.category} onChange={(value) => setEditDraft({ ...editDraft, category: value })} />
            <TextField label={t("封装")} required value={editDraft.package_name} onChange={(value) => setEditDraft({ ...editDraft, package_name: value })} />
            <TextField label={t("最低库存")} type="number" min="0" required value={String(editDraft.min_stock)} onChange={(value) => setEditDraft({ ...editDraft, min_stock: Number(value) })} />
            <TextField label={t("说明（可选）")} value={editDraft.description} onChange={(value) => setEditDraft({ ...editDraft, description: value })} />
            <Button className="min-h-11 sm:col-span-2" disabled={busy !== null}>{busy === `edit:${component.id}` ? t("正在保存…") : t("保存资料")}</Button>
          </form>
        </CardContent></Card> : null}
      {componentPanel === "cost" ? <Card className="bg-slate-50/70"><CardHeader><CardTitle>{t("补录当前库存成本")}</CardTitle></CardHeader><CardContent>
        <form className="space-y-3" onSubmit={backfillCost}>
          <p className="text-sm">{t("仅对当前剩余库存估值，不修改数量或历史采购记录。")}</p>
          <p className="text-sm">{t("当前数量")}: {formatCount(component.quantity, locale)}</p>
          <TextField label={t("当前库存总成本（CNY）")} inputMode="decimal" required pattern="[0-9]+([.][0-9]{1,8})?" value={inventoryAmount} onChange={setInventoryAmount} />
          <div className="flex gap-2"><Button disabled={busy !== null || recoverableMovement !== null}>{t("确认补录")}</Button><Button type="button" variant="outline" disabled={busy !== null} onClick={() => setComponentPanel(null)}>{t("取消")}</Button></div>
        </form>
      </CardContent></Card> : null}
      {componentPanel === "movement" ? <Card className="bg-slate-50/70"><CardHeader className="flex flex-row items-center justify-between gap-3"><CardTitle>{t("库存入出库")}</CardTitle><Button type="button" size="sm" variant="ghost" onClick={() => setComponentPanel(null)}>{t("取消")}</Button></CardHeader><CardContent>
          {!component.inventory_managed ? <p className="mb-4 text-sm text-muted-foreground">{t("这是旧版库存记录，入出库会继续沿用当前默认库位。")}</p> : null}
          <form className="grid gap-3 sm:grid-cols-2" onSubmit={applyMovement}>
            <Field label={t("操作")} labelFor={movementTypeId}><select id={movementTypeId} className="h-11 w-full rounded-xl border bg-white px-3 text-sm" value={movement.movement_type} onChange={(event) => setMovement({ ...movement, movement_type: event.target.value, location_id: "" })}><option value="inbound">{t("入库")}</option><option value="outbound">{t("出库")}</option></select></Field>
            <TextField label={t("数量")} type="number" min="1" required value={String(movement.quantity)} onChange={(value) => setMovement({ ...movement, quantity: Number(value) })} />
            {movement.movement_type === "inbound" ? <>
              <Field label={t("购买金额依据")} labelFor={purchaseModeId}><select id={purchaseModeId} className="h-11 w-full rounded-xl border bg-white px-3 text-sm" value={purchaseMode} onChange={(event) => { setPurchaseMode(event.target.value as "total" | "unit"); setPurchaseValue(""); }}><option value="total">{t("商品总金额")}</option><option value="unit">{t("购买单价")}</option></select></Field>
              <TextField label={purchaseMode === "unit" ? t("购买单价（CNY）") : t("商品总金额（CNY）")} inputMode="decimal" pattern="[0-9]+([.][0-9]{1,8})?" value={purchaseValue} onChange={setPurchaseValue} />
              <p className="text-sm text-muted-foreground sm:col-span-2">{t("留空表示成本未知，明确填写0表示赠品。不含运费、PCB和加工费。")}</p>
              <p className="text-sm sm:col-span-2" aria-live="polite">{t("本次商品金额（CNY）")}: {(() => { try { return purchaseAmount(purchaseValue, movement.quantity, purchaseMode) ?? t("成本未知"); } catch { return t("请核对金额与数量"); } })()}</p>
            </> : null}
            {component.inventory_managed ? <LocationSelect label={t("操作库位")} required locations={movementLocations} allocations={component.allocations} value={movement.location_id} onChange={(value) => setMovement({ ...movement, location_id: value })} /> : null}
            <TextField label={t("原因")} required value={movement.reason} onChange={(value) => setMovement({ ...movement, reason: value })} />
            <TextField label={t("备注（可选）")} className="sm:col-span-2" value={movement.note} onChange={(value) => setMovement({ ...movement, note: value })} />
            <p className="rounded-xl bg-white p-3 text-sm font-medium sm:col-span-2" aria-live="polite">{t("总库存预计：")}{formatCount(component.quantity, locale)} → {formatCount(projectedQuantity, locale)}</p>
            <Button className="min-h-11 sm:col-span-2" disabled={busy !== null || recoverableMovement !== null || (component.inventory_managed && movementLocations.length === 0)}>{busy?.startsWith("movement:") ? <RefreshCw className="mr-2 h-4 w-4 animate-spin" /> : null}{recoverableMovement ? t("请先处理上次操作") : `${t("确认")}${movement.movement_type === "inbound" ? t("入库") : t("出库")}`}</Button>
          </form>
        </CardContent></Card> : null}
    </div>
  );
}

function Field({ label, labelFor, className, children }: { label: string; labelFor: string; className?: string; children: React.ReactNode }) {
  return <div className={`space-y-1.5 ${className ?? ""}`}><label htmlFor={labelFor} className="block text-sm font-medium">{label}</label>{children}</div>;
}

function TextField({ label, value, onChange, className, ...input }: { label: string; value: string; onChange: (value: string) => void; className?: string } & Omit<React.InputHTMLAttributes<HTMLInputElement>, "value" | "onChange">) {
  const id = useId();
  return <Field label={label} labelFor={id} className={className}><Input id={id} value={value} onChange={(event) => onChange(event.target.value)} {...input} /></Field>;
}

function LocationSelect({ label, locations, allocations = [], value, onChange, required }: { label: string; locations: AdminStorageLocation[]; allocations?: Array<{ location_id: string; quantity: number }>; value: string; onChange: (value: string) => void; required?: boolean }) {
  const { t, locale } = useI18n();
  const quantities = new Map(allocations.map((item) => [item.location_id, item.quantity]));
  const id = useId();
  return <Field label={label} labelFor={id}><select id={id} required={required} className="h-11 w-full rounded-xl border bg-white px-3 text-sm" value={value} onChange={(event) => onChange(event.target.value)}><option value="">{t("请选择库位")}</option>{locations.map((location) => <option key={location.id} value={location.id}>{location.name}{quantities.has(location.id) ? ` (${formatCount(quantities.get(location.id)!, locale)})` : ""}</option>)}</select></Field>;
}
