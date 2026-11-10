import { formatCount, useI18n } from "@/lib/i18n";
import { ChevronRight, Search, SlidersHorizontal } from "lucide-react";
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
  type ReactNode,
} from "react";
import { useSearchParams } from "react-router-dom";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ComponentPhoto } from "@/components/component-photo";
import { InventoryBulkDelete } from "@/components/inventory-bulk-delete";
import { InventoryActions } from "@/components/inventory-actions";
import { OrderMetadataPanel } from "@/components/order-metadata-panel";
import { SpecEnrichmentPanel } from "@/components/spec-enrichment-panel";
import { CatalogMetadataPanel } from "@/components/catalog-metadata-panel";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from "@/components/ui/table";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { formatDateTime } from "@/lib/format";
import { displayCategory } from "@/lib/category-display";
import { displayParameterLabel, parseComponentParameters } from "@/lib/component-parameters";
import type {
  AdminComponentDetail,
  AdminComponentListResponse,
  AdminSettingsResponse,
  AdminStorageLocation,
} from "@/lib/types";

export function InventoryPage() {
  const { t, locale } = useI18n();
  const mobile = useMobileInventoryLayout();
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const requestedFilter = searchParams.get("stock") ?? "all";
  const stockFilter = ["all", "low", "healthy"].includes(requestedFilter)
    ? requestedFilter
    : "all";
  const requestedPage = Number.parseInt(searchParams.get("page") ?? "1", 10);
  const page = Number.isFinite(requestedPage) && requestedPage > 0 ? requestedPage : 1;
  const [input, setInput] = useState(query);
  const [selecting, setSelecting] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const searchInputId = useId();
  const stockFilterId = useId();
  const toolsTitleId = useId();
  const searchInput = useRef<HTMLInputElement>(null);
  const toolsDialog = useRef<HTMLDialogElement>(null);
  const resultsHeading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const detailTrigger = useRef<HTMLButtonElement | null>(null);
  const path = useMemo(() => {
    const params = new URLSearchParams({ page: String(page), page_size: "20" });
    if (query) params.set("q", query);
    if (stockFilter !== "all") params.set("low_stock", String(stockFilter === "low"));
    return `/admin-api/components?${params.toString()}`;
  }, [page, query, stockFilter]);
  const { data, error, loading, reload } =
    useAdminResource<AdminComponentListResponse>(path);
  const detail = useAdminResource<AdminComponentDetail>(
    selectedId ? `/admin-api/components/${encodeURIComponent(selectedId)}` : null,
  );
  const componentParameters = useMemo(
    () => parseComponentParameters(detail.data?.description),
    [detail.data?.description],
  );
  const settings = useAdminResource<AdminSettingsResponse>("/admin-api/settings");
  const locations = useAdminResource<AdminStorageLocation[]>("/admin-api/storage-locations");
  const inventoryEnabled = settings.data?.web_inventory_enabled === true;
  const locationLabel = (id: string) => {
    const location = locations.data?.find((item) => item.id === id);
    return location?.name ?? id;
  };

  useEffect(() => setInput(query), [query]);
  useEffect(() => setSelectedIds(new Set()), [path]);
  useEffect(() => {
    if (!data) return;
    setSelectedIds((previous) => new Set(data.items.filter((item) => previous.has(item.id)).map((item) => item.id)));
  }, [data]);

  function finishSelection() {
    setSelecting(false);
    setSelectedIds(new Set());
  }

  function toggleSelection(id: string) {
    setSelectedIds((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }
  useEffect(() => {
    if (!detail.data) return;
    if (!mobile) {
      detailHeading.current?.scrollIntoView({ block: "start" });
    }
    detailHeading.current?.focus({ preventScroll: true });
  }, [detail.data, mobile]);

  function selectComponent(event: MouseEvent<HTMLButtonElement>, id: string) {
    detailTrigger.current = event.currentTarget;
    setSelectedId(id);
  }

  function closeDetail() {
    setSelectedId(null);
    requestAnimationFrame(() => detailTrigger.current?.focus({ preventScroll: true }));
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    if (mobile) searchInput.current?.blur();
    setSelectedId(null);
    setSearchParams(buildPageParams(input.trim(), stockFilter, 1));
  }

  function changePage(nextPage: number) {
    setSearchParams(buildPageParams(query, stockFilter, nextPage));
    setSelectedId(null);
    if (mobile) resultsHeading.current?.scrollIntoView({ block: "start" });
  }

  const toolsLabel = locale === "zh-CN" ? "库存工具" : "Inventory tools";
  const tools = <>
    <InventoryActions
      scope="create"
      enabled={inventoryEnabled}
      component={null}
      locations={locations.data ?? []}
      onLocationsChanged={() => void locations.reload()}
      onChanged={(value) => {
        if (value) {
          toolsDialog.current?.close();
          setSelectedId(value.id);
        }
        void reload();
        void detail.reload();
      }}
    />
    <OrderMetadataPanel enabled={inventoryEnabled} onChanged={() => { void reload(); void detail.reload(); }} />
    <CatalogMetadataPanel enabled={inventoryEnabled} onChanged={() => { void reload(); void detail.reload(); }} />
    <SpecEnrichmentPanel enabled={inventoryEnabled} onChanged={() => { void reload(); void detail.reload(); }} />
  </>;

  return (
    <section className="space-y-3 lg:space-y-6" aria-busy={loading}>
      <header className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-2xl font-semibold tracking-tight lg:text-3xl">{t("库存")}</h2>
          {settings.data?.web_inventory_enabled === false && <span className="rounded-md bg-muted px-2 py-1 text-xs font-medium text-muted-foreground lg:hidden">{locale === "zh-CN" ? "只读" : "Read only"}</span>}
        </div>
        <p className="hidden max-w-3xl text-sm text-slate-600 lg:block">
          {settings.data?.web_inventory_enabled === false
            ? (locale === "zh-CN" ? "当前为只读模式。需要编辑或删除时，请管理员在部署设置中启用 Web 库存操作。" : "Inventory is read-only. Ask an administrator to enable Web inventory writes in deployment settings.")
            : (locale === "zh-CN" ? "搜索当前账户的元器件，查看库存或处理出入库。" : "Search this account’s components and manage stock.")}
        </p>
      </header>

      {settings.error ? <Alert variant="destructive"><AlertTitle>{t("无法确认库存操作开关")}</AlertTitle><AlertDescription className="flex flex-wrap items-center justify-between gap-3"><span>{settings.error}</span><Button variant="outline" size="sm" onClick={() => void settings.reload()}>{t("重试")}</Button></AlertDescription></Alert> : null}
      {inventoryEnabled && locations.error ? <Alert variant="destructive"><AlertTitle>{t("无法读取库位")}</AlertTitle><AlertDescription className="flex flex-wrap items-center justify-between gap-3"><span>{locations.error}</span><Button variant="outline" size="sm" onClick={() => void locations.reload()}>{t("重试")}</Button></AlertDescription></Alert> : null}

      <div className="lg:rounded-2xl lg:border lg:bg-white/90 lg:p-5">
        <form className="grid gap-2 lg:grid-cols-[minmax(0,1fr)_180px_auto] lg:items-end lg:gap-3" onSubmit={submitSearch}>
          <div className="flex gap-2 lg:contents">
            <div className="relative min-w-0 flex-1 lg:col-start-1 lg:row-start-1">
              <label htmlFor={searchInputId} className="sr-only text-sm font-medium lg:not-sr-only lg:mb-2 lg:block">{t("料号、名称、品牌、参数或库位")}</label>
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground lg:hidden" aria-hidden="true" />
              <Input ref={searchInput} id={searchInputId} type="search" className="pl-9 lg:pl-3" value={input} onChange={(event) => setInput(event.target.value)} placeholder={mobile ? (locale === "zh-CN" ? "料号、名称或参数" : "SKU, name, or spec") : t("例如 C30926、连接器或 A-01")} />
            </div>
            <Button className="min-h-11 shrink-0 lg:col-start-3 lg:row-start-1" type="submit">{t("搜索")}</Button>
          </div>
          <div className="flex gap-2 lg:contents">
            <div className="min-w-0 flex-1 lg:col-start-2 lg:row-start-1">
              <label htmlFor={stockFilterId} className="sr-only text-sm font-medium lg:not-sr-only lg:mb-2 lg:block">{t("库存状态")}</label>
              <select
                id={stockFilterId}
                className="min-h-11 w-full rounded-xl border border-input bg-card px-3 text-base lg:bg-background lg:text-sm"
                value={stockFilter}
                onChange={(event) => {
                  setSelectedId(null);
                  setSearchParams(buildPageParams(query, event.target.value, 1));
                }}
              >
                <option value="all">{t("全部")}</option>
                <option value="low">{t("低库存")}</option>
                <option value="healthy">{t("库存充足")}</option>
              </select>
            </div>
            <Button type="button" variant="outline" className="min-h-11 shrink-0 gap-1.5 lg:hidden" onClick={() => toolsDialog.current?.showModal()}>
              <SlidersHorizontal className="h-4 w-4" aria-hidden="true" />{toolsLabel}
            </Button>
          </div>
        </form>
      </div>

      {mobile ? <dialog ref={toolsDialog} aria-labelledby={toolsTitleId}
        className="fixed inset-0 m-0 h-dvh max-h-none w-full max-w-none overflow-y-auto overscroll-contain bg-background text-foreground backdrop:bg-background">
        <header className="sticky top-0 z-20 flex items-center justify-between gap-2 border-b bg-card px-3 py-1">
          <h2 id={toolsTitleId} className="font-semibold">{toolsLabel}</h2>
          <Button variant="ghost" className="min-h-11" onClick={() => toolsDialog.current?.close()}>{locale === "zh-CN" ? "返回库存" : "Back to inventory"}</Button>
        </header>
        <div className="space-y-3 p-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]">{tools}</div>
      </dialog> : <details className="rounded-xl border bg-white/90 px-4">
        <summary className="min-h-11 cursor-pointer py-3 text-sm font-semibold">{toolsLabel}</summary>
        <div className="space-y-3 pb-4">{tools}</div>
      </details>}

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>{t("无法读取库存")}</AlertTitle>
          <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
            <span>{error}</span>
            <Button size="sm" variant="outline" onClick={() => void reload()}>{t("重试")}</Button>
          </AlertDescription>
        </Alert>
      ) : null}

      <Card className="border-0 bg-transparent shadow-none lg:border lg:bg-white/90">
        <CardHeader className="flex-row flex-wrap items-center justify-between gap-2 space-y-0 p-0 pb-2">
          <div className="flex min-w-0 flex-wrap items-baseline gap-x-2">
            <CardTitle ref={resultsHeading} className="text-base lg:text-lg">{t("元器件列表")}</CardTitle>
            <span className="text-xs text-muted-foreground lg:text-sm" aria-live="polite">
              {loading ? t("正在加载…") : `${t("共")}${formatCount(data?.total ?? 0, locale)}${t(" 项")}`}
            </span>
          </div>
          {inventoryEnabled && !selecting ? <Button variant="outline" size="sm" className="min-h-11" onClick={() => setSelecting(true)}>{locale === "zh-CN" ? "多选" : "Select multiple"}</Button> : null}
        </CardHeader>
        <CardContent className="px-0 pb-0">
          {!loading && data?.items.length === 0 ? (
            <div className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
              {t("没有符合条件的元器件。请调整搜索词或库存状态。")}
            </div>
          ) : data ? (<>
            {mobile ? <ul className="divide-y rounded-xl border bg-card" aria-label={t("库存结果")}>
              {data.items.map((item) => {
                const rowClass = "flex min-h-[4.5rem] w-full items-center gap-3 px-3 py-2.5 text-left focus-visible:outline focus-visible:outline-2 focus-visible:outline-inset focus-visible:outline-ring";
                const content = <>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-semibold" title={item.name}>{item.name}</span>
                    <span className="block truncate text-xs text-muted-foreground" title={item.sku}>{item.sku}</span>
                    <span className="block truncate text-xs text-muted-foreground">{[locationLabel(item.location), locale === "zh-CN" ? displayCategory(item.category) : item.category].filter(Boolean).join(" · ")}</span>
                  </span>
                  <span className="max-w-[33%] shrink-0 text-right">
                    <span className="block break-all text-lg font-semibold leading-6 tabular-nums">{formatCount(item.quantity, locale)}</span>
                    {item.low_stock ? <span className="block text-xs font-medium text-amber-800">{t("低库存")}</span> : null}
                  </span>
                </>;
                return <li key={item.id}>
                  {inventoryEnabled && selecting ? <label className={rowClass}>
                    <input type="checkbox" className="h-5 w-5 shrink-0 accent-primary" aria-label={`${t("选择元器件")} ${item.sku}`} disabled={loading} checked={selectedIds.has(item.id)} onChange={() => toggleSelection(item.id)} />
                    {content}
                  </label> : <button type="button" className={rowClass} aria-label={`${t("元器件详情")} · ${item.name} · ${item.sku} · ${t("库存")} ${formatCount(item.quantity, locale)}`} onClick={(event) => selectComponent(event, item.id)}>
                    {content}
                    <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  </button>}
                </li>;
              })}
            </ul> : <div className="overflow-x-auto" tabIndex={0} aria-label={t("库存结果")}>
              <Table>
                <TableHeader><TableRow>
                  {inventoryEnabled && selecting ? <TableHead className="w-12"><span className="sr-only">{t("选择元器件")}</span></TableHead> : null}
                  <TableHead>{t("料号")}</TableHead><TableHead>{t("名称")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("分类")}</TableHead>
                  <TableHead>{t("库位")}</TableHead><TableHead>{t("库存 / 最低")}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t("更新时间")}</TableHead>
                </TableRow></TableHeader>
                <TableBody>{data.items.map((item) => (
                  <TableRow key={item.id} data-state={selectedIds.has(item.id) || selectedId === item.id ? "selected" : undefined}>
                    {inventoryEnabled && selecting ? <TableCell className="p-0"><label className="flex min-h-11 min-w-11 items-center justify-center"><input type="checkbox" className="h-4 w-4 accent-red-600" aria-label={`${t("选择元器件")} ${item.sku}`} disabled={loading} checked={selectedIds.has(item.id)} onChange={() => toggleSelection(item.id)} /></label></TableCell> : null}
                    <TableCell><button className="font-medium text-primary underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={(event) => selectComponent(event, item.id)}>{item.sku}</button></TableCell>
                    <TableCell>{item.name}<ComponentPhoto photoKey={item.photo_key} name={item.name} compact /></TableCell>
                    <TableCell className="hidden md:table-cell">{locale === "zh-CN" ? displayCategory(item.category) : item.category}</TableCell>
                    <TableCell>{locationLabel(item.location)}</TableCell>
                    <TableCell className={item.low_stock ? "font-semibold text-amber-700" : ""}>
                      {formatCount(item.quantity, locale)} / {formatCount(item.min_stock, locale)}
                      {item.low_stock ? <span className="ml-2 whitespace-nowrap">{t("低库存")}</span> : null}
                    </TableCell>
                    <TableCell className="hidden lg:table-cell">{formatDateTime(item.updated_at, locale)}</TableCell>
                  </TableRow>
                ))}</TableBody>
              </Table>
            </div>}
          </>) : <div className="h-48 animate-pulse rounded-xl bg-slate-100" aria-label={t("正在加载库存")} />}

          {data && data.page_count > 0 ? (
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">{t("第")}{formatCount(data.page, locale)} / {formatCount(data.page_count, locale)}{t(" 页")}</p>
              <div className="flex gap-2">
                <Button variant="outline" className="min-h-11" disabled={loading || data.page <= 1} onClick={() => changePage(page - 1)}>{t("上一页")}</Button>
                <Button variant="outline" className="min-h-11" disabled={loading || data.page >= data.page_count} onClick={() => changePage(page + 1)}>{t("下一页")}</Button>
              </div>
            </div>
          ) : null}
          {inventoryEnabled ? <InventoryBulkDelete
            selecting={selecting}
            onFinishSelection={finishSelection}
            selected={data?.items.filter((item) => selectedIds.has(item.id)) ?? []}
            disabled={loading}
            allSelected={!!data?.items.length && data.items.every((item) => selectedIds.has(item.id))}
            onSelectAll={() => setSelectedIds(new Set(data?.items.map((item) => item.id) ?? []))}
            onClear={() => setSelectedIds(new Set())}
            onRefresh={() => { setSelectedIds(new Set()); void reload(); }}
            onDeleted={(ids) => {
              finishSelection();
              if (selectedId && ids.includes(selectedId)) setSelectedId(null);
              if (data && ids.length === data.items.length && page > 1) {
                setSearchParams(buildPageParams(query, stockFilter, page - 1));
              } else void reload();
            }}
          /> : null}
        </CardContent>
      </Card>

      {selectedId ? (
        <ResponsiveInventoryDetail mobile={mobile} onClose={closeDetail} title={t("元器件详情")}>
          <Card className="scroll-mt-20 rounded-none border-0 bg-card shadow-none lg:border lg:bg-white/90" aria-busy={detail.loading}>
            <CardHeader className="sticky top-0 z-10 flex-row items-center justify-between gap-3 space-y-0 border-b bg-card px-3 py-1 lg:static lg:border-0 lg:bg-transparent">
              <CardTitle ref={detailHeading} tabIndex={-1} className="scroll-mt-20 focus:outline-none">
                {t("元器件详情")}
              </CardTitle>
              <Button variant="outline" size="sm" className="min-h-11" onClick={closeDetail}>{t("关闭详情")}</Button>
            </CardHeader>
            <CardContent className="pt-3 lg:pt-0">
              {detail.error ? <Alert variant="destructive"><AlertTitle>{t("无法读取详情")}</AlertTitle><AlertDescription>{detail.error}</AlertDescription></Alert> : null}
              {detail.data ? (
                <><dl className="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
                  <Detail label={t("料号")} value={detail.data.sku} /><Detail label={t("名称")} value={detail.data.name} />
                  <div className="sm:col-span-2 lg:col-span-3"><ComponentPhoto photoKey={detail.data.photo_key} name={detail.data.name} /></div>
                  <Detail label={t("分类")} value={locale === "zh-CN" ? displayCategory(detail.data.category) : detail.data.category} /><Detail label={t("封装")} value={detail.data.package_name} />
                  <div className="sm:col-span-2 lg:col-span-3">
                    <dt className="text-muted-foreground">{t("元件参数")}</dt>
                    <dd className="mt-2">
                      {componentParameters.length ? (
                        <dl className="grid gap-3 rounded-xl border bg-slate-50 p-4 sm:grid-cols-2 lg:grid-cols-3">
                          {componentParameters.map((parameter, index) => (
                            <Detail key={`${parameter.label}-${parameter.value}-${index}`} label={displayParameterLabel(parameter.label, locale)} value={parameter.value} />
                          ))}
                        </dl>
                      ) : <p className="text-sm text-muted-foreground">{t("尚未获取明确参数；可在说明中核对原始资料。")}</p>}
                    </dd>
                  </div>
                  <Detail label={t("默认库位")} value={locationLabel(detail.data.location)} /><Detail label={t("库存 / 最低库存")} value={`${formatCount(detail.data.quantity, locale)} / ${formatCount(detail.data.min_stock, locale)}`} />
                  <Detail label={t("库存模式")} value={detail.data.inventory_managed ? t("独立库位库存") : t("旧版标量库存")} />
                  <Detail label={t("库存平均单价（CNY）")} value={detail.data.quantity === 0 ? "—" : detail.data.inventory_unit_cost ?? t("成本未知")} />
                  <Detail label={t("说明")} value={detail.data.description || "—"} />
                  <Detail label={t("库位分配")} value={detail.data.allocations.length ? detail.data.allocations.map((item) => `${locations.data?.find((location) => location.id === item.location_id)?.name ?? item.location_id}: ${formatCount(item.quantity, locale)}`).join(locale === "zh-CN" ? "；" : "; ") : t("未提供独立库位分配")} />
                  <Detail label={t("更新时间")} value={formatDateTime(detail.data.updated_at, locale)} />
                </dl></>
              ) : detail.loading ? <p className="text-sm text-muted-foreground" aria-live="polite">{t("正在加载详情…")}</p> : null}
              <InventoryActions
                scope="component"
                enabled={inventoryEnabled}
                component={detail.data}
                locations={locations.data ?? []}
                onLocationsChanged={() => void locations.reload()}
                onChanged={(value) => {
                  if (value) setSelectedId(value.id);
                  void reload();
                  void detail.reload();
                }}
                onDeleted={() => {
                  setSelectedId(null);
                  void reload();
                }}
              />
            </CardContent>
          </Card>
        </ResponsiveInventoryDetail>
      ) : null}
    </section>
  );
}

function useMobileInventoryLayout() {
  const desktopQuery = "(min-width: 1024px)";
  const [mobile, setMobile] = useState(() => !window.matchMedia(desktopQuery).matches);

  useEffect(() => {
    const media = window.matchMedia(desktopQuery);
    const onChange = () => setMobile(!media.matches);
    media.addEventListener("change", onChange);
    return () => media.removeEventListener("change", onChange);
  }, []);
  return mobile;
}

function ResponsiveInventoryDetail({ mobile, title, onClose, children }: { mobile: boolean; title: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const element = dialog.current;
    if (!mobile || !element) return;
    element.showModal();
    return () => { if (element.open) element.close(); };
  }, [mobile]);

  if (!mobile) return <>{children}</>;
  return <dialog
    ref={dialog}
    aria-label={title}
    className="fixed inset-0 m-0 h-dvh max-h-none w-full max-w-none overflow-y-auto overscroll-contain bg-card pb-[env(safe-area-inset-bottom)] text-foreground backdrop:bg-background"
    onCancel={(event) => { event.preventDefault(); onClose(); }}
  >{children}</dialog>;
}

function Detail({ label, value }: { label: string; value: string }) {
  return <div><dt className="text-muted-foreground">{label}</dt><dd className="mt-1 break-words font-medium">{value}</dd></div>;
}

function buildPageParams(query: string, stock: string, page: number) {
  const params = new URLSearchParams();
  if (query) params.set("q", query);
  if (stock !== "all") params.set("stock", stock);
  if (page > 1) params.set("page", String(page));
  return params;
}
