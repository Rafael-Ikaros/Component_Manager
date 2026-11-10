import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { formatCount, useI18n } from "@/lib/i18n";

interface StatCardProps {
  title: string;
  value: number;
  subtitle: string;
}

export function StatCard({ subtitle, title, value }: StatCardProps) {
  const { locale } = useI18n();
  return (
    <Card className="min-w-0 bg-white/90">
      <CardHeader className="px-3 pb-1 pt-3 sm:px-6 sm:pb-3 sm:pt-6">
        <CardTitle className="text-sm font-medium text-slate-600">{title}</CardTitle>
      </CardHeader>
      <CardContent className="px-3 pb-3 sm:px-6 sm:pb-6">
        <div className="text-2xl font-semibold tabular-nums tracking-tight text-slate-950 [overflow-wrap:anywhere] sm:text-3xl">{formatCount(value, locale)}</div>
        <p className="mt-2 hidden text-sm text-muted-foreground sm:block">{subtitle}</p>
      </CardContent>
    </Card>
  );
}
