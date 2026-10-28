import { formatCount, useI18n } from "@/lib/i18n";
import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
} from "react";
import { useSearchParams } from "react-router-dom";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { ComponentPhoto } from "@/components/component-photo";
import { InventoryActions } from "@/components/inventory-actions";
import { OrderMetadataPanel } from "@/components/order-metadata-panel";
import { SpecEnrichmentPanel } from "@/components/spec-enrichment-panel";
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
  const [searchParams, setSearchParams] = useSearchParams();
  const query = searchParams.get("q") ?? "";
  const requestedFilter = searchParams.get("stock") ?? "all";
  const stockFilter = ["all", "low", "healthy"].includes(requestedFilter)
    ? requestedFilter
    : "all";
  const requestedPage = Number.parseInt(searchParams.get("page") ?? "1", 10);
  const page = Number.isFinite(requestedPage) && requestedPage > 0 ? requestedPage : 1;
  const [input, setInput] = useState(query);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const searchInputId = useId();
  const stockFilterId = useId();
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
    return location ? `${location.name}（${location.id}）` : id;
  };

  useEffect(() => setInput(query), [query]);
  useEffect(() => {
    if (!detail.data) return;
    detailHeading.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    detailHeading.current?.focus({ preventScroll: true });
  }, [detail.data]);

  function selectComponent(event: MouseEvent<HTMLButtonElement>, id: string) {
    detailTrigger.current = event.currentTarget;
    setSelectedId(id);
  }

  function closeDetail() {
    setSelectedId(null);
    requestAnimationFrame(() => detailTrigger.current?.focus());
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault();
    setSelectedId(null);
    setSearchParams(buildPageParams(input.trim(), stockFilter, 1));
  }

  return (
    <section className="space-y-6" aria-busy={loading}>
      <header className="space-y-2">
        <h2 className="text-3xl font-semibold tracking-tight">{t("库存核对")}</h2>
        <p className="max-w-3xl text-sm text-slate-600">
          {t("搜索服务器上的有效元器件。服务端开启 Web 库存操作后，可创建库位和元件，并执行可靠的入出库。")}
        </p>
      </header>

      {settings.error ? <Alert variant="destructive"><AlertTitle>{t("无法确认库存操作开关")}</AlertTitle><AlertDescription className="flex flex-wrap items-center justify-between gap-3"><span>{settings.error}</span><Button variant="outline" size="sm" onClick={() => void settings.reload()}>{t("重试")}</Button></AlertDescription></Alert> : null}
      {inventoryEnabled && locations.error ? <Alert variant="destructive"><AlertTitle>{t("无法读取库位")}</AlertTitle><AlertDescription className="flex flex-wrap items-center justify-between gap-3"><span>{locations.error}</span><Button variant="outline" size="sm" onClick={() => void locations.reload()}>{t("重试")}</Button></AlertDescription></Alert> : null}

      <InventoryActions
        scope="create"
        enabled={inventoryEnabled}
        component={null}
        locations={locations.data ?? []}
        onLocationsChanged={() => void locations.reload()}
        onChanged={(value) => {
          if (value) setSelectedId(value.id);
          void reload();
          void detail.reload();
        }}
      />

      <OrderMetadataPanel enabled={inventoryEnabled} onChanged={() => { void reload(); void detail.reload(); }} />

      <SpecEnrichmentPanel enabled={inventoryEnabled} onChanged={() => { void reload(); void detail.reload(); }} />

      <Card className="bg-white/90">
        <CardContent className="pt-6">
          <form className="grid gap-3 md:grid-cols-[minmax(0,1fr),180px,auto]" onSubmit={submitSearch}>
            <div className="space-y-2">
              <label htmlFor={searchInputId} className="block text-sm font-medium">{t("料号、名称、品牌、参数或库位")}</label>
              <Input id={searchInputId} value={input} onChange={(event) => setInput(event.target.value)} placeholder={t("例如 C30926、连接器或 A-01")} />
            </div>
            <div className="space-y-2">
              <label htmlFor={stockFilterId} className="block text-sm font-medium">{t("库存状态")}</label>
              <select
                id={stockFilterId}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
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
            <Button className="self-end" type="submit">{t("搜索")}</Button>
          </form>
        </CardContent>
      </Card>

      {error ? (
        <Alert variant="destructive">
          <AlertTitle>{t("无法读取库存")}</AlertTitle>
          <AlertDescription className="flex flex-wrap items-center justify-between gap-3">
            <span>{error}</span>
            <Button size="sm" variant="outline" onClick={() => void reload()}>{t("重试")}</Button>
          </AlertDescription>
        </Alert>
      ) : null}

      <Card className="bg-white/90">
        <CardHeader>
          <CardTitle className="flex flex-wrap items-center justify-between gap-2">
            <span>{t("元器件列表")}</span>
            <span className="text-sm font-normal text-muted-foreground" aria-live="polite">
              {loading ? t("正在加载…") : `${t("共")}${formatCount(data?.total ?? 0, locale)}${t(" 项")}`}
            </span>
          </CardTitle>
        </CardHeader>
        <CardContent>
          {!loading && data?.items.length === 0 ? (
            <div className="rounded-xl border border-dashed p-8 text-center text-sm text-muted-foreground">
              {t("没有符合条件的元器件。请调整搜索词或库存状态。")}
            </div>
          ) : data ? (<>
            <div className="grid gap-3 md:hidden" aria-label={t("库存结果")}>
              {data.items.map((item) => <article key={item.id} className="rounded-2xl border bg-white p-4 shadow-sm">
                <div className="flex items-start justify-between gap-3">
                  <div><button className="min-h-11 text-left font-semibold text-primary underline-offset-4 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={(event) => selectComponent(event, item.id)}>{item.sku}</button><p className="text-sm text-slate-700">{item.name}</p><ComponentPhoto photoKey={item.photo_key} name={item.name} compact /></div>
                  <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${item.low_stock ? "bg-amber-100 text-amber-800" : "bg-emerald-100 text-emerald-800"}`}>{item.low_stock ? t("低库存") : t("库存充足")}</span>
                </div>
                <dl className="mt-4 grid grid-cols-2 gap-3 text-sm"><Detail label={t("分类")} value={locale === "zh-CN" ? displayCategory(item.category) : item.category} /><Detail label={t("库位")} value={locationLabel(item.location)} /><Detail label={t("库存 / 最低")} value={`${formatCount(item.quantity, locale)} / ${formatCount(item.min_stock, locale)}`} /><Detail label={t("更新时间")} value={formatDateTime(item.updated_at, locale)} /></dl>
              </article>)}
            </div>
            <div className="hidden overflow-x-auto md:block" tabIndex={0} aria-label={t("库存结果")}>
              <Table>
                <TableHeader><TableRow>
                  <TableHead>{t("料号")}</TableHead><TableHead>{t("名称")}</TableHead>
                  <TableHead className="hidden md:table-cell">{t("分类")}</TableHead>
                  <TableHead>{t("库位")}</TableHead><TableHead>{t("库存 / 最低")}</TableHead>
                  <TableHead className="hidden lg:table-cell">{t("更新时间")}</TableHead>
                </TableRow></TableHeader>
                <TableBody>{data.items.map((item) => (
                  <TableRow key={item.id} data-state={selectedId === item.id ? "selected" : undefined}>
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
            </div>
          </>) : <div className="h-48 animate-pulse rounded-xl bg-slate-100" aria-label={t("正在加载库存")} />}

          {data && data.page_count > 0 ? (
            <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">{t("第")}{formatCount(data.page, locale)} / {formatCount(data.page_count, locale)}{t(" 页")}</p>
              <div className="flex gap-2">
                <Button variant="outline" disabled={loading || data.page <= 1} onClick={() => { setSearchParams(buildPageParams(query, stockFilter, page - 1)); setSelectedId(null); }}>{t("上一页")}</Button>
                <Button variant="outline" disabled={loading || data.page >= data.page_count} onClick={() => { setSearchParams(buildPageParams(query, stockFilter, page + 1)); setSelectedId(null); }}>{t("下一页")}</Button>
              </div>
            </div>
          ) : null}
        </CardContent>
      </Card>

      {selectedId ? (
        <Card className="scroll-mt-20 bg-white/90" aria-busy={detail.loading}>
          <CardHeader className="flex flex-row items-start justify-between gap-3">
            <CardTitle ref={detailHeading} tabIndex={-1} className="scroll-mt-20 focus:outline-none">
              {t("元器件详情")}
            </CardTitle>
            <Button variant="outline" size="sm" onClick={closeDetail}>{t("关闭详情")}</Button>
          </CardHeader>
          <CardContent>
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
      ) : null}
    </section>
  );
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
