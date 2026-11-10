import { useI18n, type LanguagePreference } from "@/lib/i18n";

export function LanguageSelect({ compact = false }: { compact?: boolean }) {
  const { preference, setPreference, t } = useI18n();
  return <label className="flex min-h-11 min-w-0 flex-wrap items-center gap-2 text-sm font-medium text-slate-700 sm:gap-3">
    <span>{t("语言")}</span>
    <select
      aria-label={t("语言")}
      className={`h-11 min-w-0 rounded-md border border-input bg-background px-3 text-base sm:h-10 sm:text-sm ${compact ? "max-w-40" : "max-w-full sm:min-w-48"}`}
      value={preference}
      onChange={(event) => setPreference(event.target.value as LanguagePreference)}
    >
      <option value="system">{t("跟随系统")}</option>
      <option value="zh-CN">{t("简体中文")}</option>
      <option value="en">English</option>
    </select>
  </label>;
}
