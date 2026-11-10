import { useEffect, useRef, useState, type MouseEvent } from "react";
import { ArrowLeft, ChevronRight } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useAdminResource } from "@/hooks/use-admin-resource";
import { useI18n } from "@/lib/i18n";
import { formatDateTime } from "@/lib/format";

type PresetLine = {
  id: string; sku: string | null; name: string | null; model: string | null;
  package_name: string | null; quantity_per_set: number; selection: string | null; excluded: boolean;
  source_rows: { sheet_name: string; row_number: number; fields: Record<string, string> }[];
};
type Preset = {
  id: string; name: string; updated_at: string; deleted: boolean;
  content: { project_name: string; production_sets: number; source_name: string;
    selected_sheet: { name: string }; warnings: string[]; lines: PresetLine[] } | null;
};
type PresetResponse = { presets: Preset[] };
const pageSize = 25;

export function BomPresetsPage() {
  const { t, locale } = useI18n();
  const { data, error, loading, reload } = useAdminResource<PresetResponse>("/admin-api/bom-presets");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [linePage, setLinePage] = useState(1);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const detailTrigger = useRef<HTMLButtonElement | null>(null);
  const presets = data?.presets.filter((preset) => !preset.deleted && preset.content) ?? [];
  const selected = presets.find((preset) => preset.id === selectedId);
  const content = selected?.content;
  const pageCount = Math.max(1, Math.ceil(presets.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visiblePresets = presets.slice((currentPage - 1) * pageSize, currentPage * pageSize);
  const linePageCount = Math.max(1, Math.ceil((content?.lines.length ?? 0) / pageSize));
  const currentLinePage = Math.min(linePage, linePageCount);
  const visibleLines = content?.lines.slice((currentLinePage - 1) * pageSize, currentLinePage * pageSize) ?? [];

  useEffect(() => {
    if (!selected || !window.matchMedia("(max-width: 1023px)").matches) return;
    detailHeading.current?.focus();
  }, [selected?.id]);

  function openPreset(event: MouseEvent<HTMLButtonElement>, id: string) {
    detailTrigger.current = event.currentTarget;
    setSelectedId(id);
    setLinePage(1);
  }

  function closePreset() {
    setSelectedId(null);
    requestAnimationFrame(() => detailTrigger.current?.focus());
  }

  const references = (line: PresetLine) => line.source_rows.flatMap((row) =>
    Object.entries(row.fields).filter(([key]) => /reference|designator|refdes|位号/i.test(key)).map(([, value]) => value),
  ).filter(Boolean).join(", ");

  const lineSelection = (line: PresetLine) =>
    line.selection === null ? t("自动匹配") : line.selection === "" ? t("明确未匹配") : `${t("已选元件 ID")}: ${line.selection}`;

  return <section className="space-y-4 lg:space-y-6">
    <header className={selected ? "hidden space-y-2 lg:block" : "space-y-2"}>
      <h2 className="text-2xl font-semibold tracking-tight lg:text-3xl">{t("BOM 预设")}</h2>
      <p className="max-w-3xl text-sm text-slate-600">{t("查看当前账户已同步的 BOM 需求和选择。阅读预设不会扣减库存；请在原生客户端核对匹配并配套。")}</p>
    </header>
    {error ? <Alert variant="destructive"><AlertTitle>{t("无法读取 BOM 预设")}</AlertTitle><AlertDescription className="flex flex-wrap items-center justify-between gap-3"><span className="min-w-0 [overflow-wrap:anywhere]">{error}</span><Button variant="outline" size="sm" onClick={() => void reload()}>{t("Retry")}</Button></AlertDescription></Alert> : null}
    {loading ? <p role="status" className="text-sm text-muted-foreground">{t("正在读取 BOM 预设…")}</p> : null}
    {data ? <Card className={selected ? "hidden bg-white/90 lg:block" : "bg-white/90"}>
      <CardHeader className="hidden lg:flex">
        <CardTitle>{t("BOM 预设")}</CardTitle>
        <CardDescription>{t("仅显示有效预设，删除记录保留用于同步。")}</CardDescription>
      </CardHeader>
      <CardContent className="pt-4 lg:pt-0">
        {presets.length === 0 ? <p className="text-sm text-muted-foreground">{t("暂无已同步的 BOM 预设。请先在 Android 或 Windows 客户端保存并同步预设。")}</p> : <>
          <ul className="divide-y lg:hidden">
            {visiblePresets.map((preset) => (
              <li key={preset.id}>
                <button type="button" className="flex min-h-11 w-full items-center justify-between gap-2 rounded-md py-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={(event) => openPreset(event, preset.id)}>
                  <span className="min-w-0 space-y-1 text-sm [overflow-wrap:anywhere]">
                    <span className="block font-semibold">{preset.name}</span>
                    <span className="block text-muted-foreground">{preset.content?.project_name} · {t("生产套数")}: {preset.content?.production_sets}</span>
                    <span className="block text-xs text-muted-foreground">{preset.content?.source_name || "—"} · {formatDateTime(preset.updated_at, locale)}</span>
                  </span>
                  <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                </button>
              </li>
            ))}
          </ul>
          <div className="hidden lg:block">
            <Table>
              <TableHeader><TableRow><TableHead>{t("名称")}</TableHead><TableHead>{t("项目")}</TableHead><TableHead>{t("生产套数")}</TableHead><TableHead>{t("来源文件")}</TableHead><TableHead>{t("更新时间")}</TableHead><TableHead>{t("详情")}</TableHead></TableRow></TableHeader>
              <TableBody>
                {visiblePresets.map((preset) => <TableRow key={preset.id}>
                  <TableCell className="font-medium break-words">{preset.name}</TableCell>
                  <TableCell>{preset.content?.project_name}</TableCell>
                  <TableCell>{preset.content?.production_sets}</TableCell>
                  <TableCell className="break-all">{preset.content?.source_name || "—"}</TableCell>
                  <TableCell>{formatDateTime(preset.updated_at, locale)}</TableCell>
                  <TableCell><Button size="sm" variant={selectedId === preset.id ? "secondary" : "outline"} onClick={(event) => openPreset(event, preset.id)}>{t("查看")}</Button></TableCell>
                </TableRow>)}
              </TableBody>
            </Table>
          </div>
          <Pagination className="mt-4" current={currentPage} count={pageCount} onChange={setPage} />
        </>}
      </CardContent>
    </Card> : null}
    {content && selected ? <Card className="bg-white/90">
      <CardHeader>
        <Button variant="ghost" className="-ml-2 min-h-11 self-start px-2 lg:hidden" onClick={closePreset}>
          <ArrowLeft className="mr-1 h-4 w-4" aria-hidden="true" />{t("BOM 预设")}
        </Button>
        <CardTitle ref={detailHeading} tabIndex={-1} className="scroll-mt-20 [overflow-wrap:anywhere]">{selected.name}</CardTitle>
        <CardDescription className="[overflow-wrap:anywhere]">{content.project_name} · {t("生产套数")}: {content.production_sets} · {t("来源文件")}: {content.source_name || "—"} · {t("工作表")}: {content.selected_sheet.name}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">{t("选择状态为保存时的设置，不代表当前库存可用或已配套；缺失的元件映射需在原生客户端修复。")}</p>
        {content.warnings.length ? <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground [overflow-wrap:anywhere]">{content.warnings.map((warning, index) => <li key={index}>{warning}</li>)}</ul> : null}
        {content.lines.length === 0 ? <p className="text-sm text-muted-foreground">{t("此预设没有需求行。")}</p> : <>
          <ul className="divide-y lg:hidden">
            {visibleLines.map((line) => (
              <li key={line.id} className="space-y-2 py-3 text-sm first:pt-0 last:pb-0 [overflow-wrap:anywhere]">
                <p className="font-semibold">{line.sku || line.name || line.model || line.id}</p>
                <p className="text-muted-foreground">{[line.name, line.model, line.package_name].filter(Boolean).join(" · ")}</p>
                <dl className="grid grid-cols-2 gap-2 text-xs">
                  <div><dt className="text-muted-foreground">{t("每套数量")}</dt><dd className="tabular-nums">{line.quantity_per_set}</dd></div>
                  <div><dt className="text-muted-foreground">{t("总需求")}</dt><dd className="tabular-nums">{line.quantity_per_set * content.production_sets}</dd></div>
                  <div className="col-span-2"><dt className="text-muted-foreground">{t("选择状态")}</dt><dd>{lineSelection(line)}{line.excluded ? ` · ${t("跳过")}` : ""}</dd></div>
                  <div className="col-span-2"><dt className="text-muted-foreground">{t("参考位号")}</dt><dd>{references(line) || "—"}</dd></div>
                </dl>
              </li>
            ))}
          </ul>
          <div className="hidden lg:block">
            <Table>
              <TableHeader><TableRow><TableHead>{t("Component")}</TableHead><TableHead>{t("每套数量")}</TableHead><TableHead>{t("总需求")}</TableHead><TableHead>{t("选择状态")}</TableHead><TableHead>{t("跳过")}</TableHead><TableHead>{t("参考位号")}</TableHead></TableRow></TableHeader>
              <TableBody>
                {visibleLines.map((line) => <TableRow key={line.id}>
                  <TableCell className="max-w-xs break-words"><div className="font-medium">{line.sku || line.name || line.model || line.id}</div><div className="text-xs text-muted-foreground">{[line.name, line.model, line.package_name].filter(Boolean).join(" · ")}</div></TableCell>
                  <TableCell>{line.quantity_per_set}</TableCell>
                  <TableCell>{line.quantity_per_set * content.production_sets}</TableCell>
                  <TableCell className="max-w-xs break-all">{lineSelection(line)}</TableCell>
                  <TableCell>{line.excluded ? t("是") : t("否")}</TableCell>
                  <TableCell className="max-w-xs break-words">{references(line) || "—"}</TableCell>
                </TableRow>)}
              </TableBody>
            </Table>
          </div>
        </>}
        {linePageCount > 1 ? <Pagination current={currentLinePage} count={linePageCount} onChange={setLinePage} /> : null}
      </CardContent>
    </Card> : null}
  </section>;
}

function Pagination({ current, count, onChange, className = "" }: { current: number; count: number; onChange: (page: number) => void; className?: string }) {
  const { t } = useI18n();
  return <div className={`flex flex-wrap items-center justify-between gap-2 lg:justify-end lg:gap-3 ${className}`}>
    <Button variant="outline" size="sm" className="min-h-11 lg:min-h-0" disabled={current <= 1} onClick={() => onChange(current - 1)}>{t("上一页")}</Button>
    <span className="text-sm tabular-nums">{current} / {count}</span>
    <Button variant="outline" size="sm" className="min-h-11 lg:min-h-0" disabled={current >= count} onClick={() => onChange(current + 1)}>{t("下一页")}</Button>
  </div>;
}
