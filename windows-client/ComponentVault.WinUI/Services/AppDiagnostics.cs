using System.Text;

namespace ComponentVault.WinUI.Services;

public enum DiagnosticEvent { CatalogDomestic, CatalogInternational, CatalogEda, Network }
public enum DiagnosticOutcome { Started, Success, Dns, Tls, Timeout, Http, Parser, Blocked, Failed }

public static class AppDiagnostics
{
    private const int MaxEntries=100,MaxCharacters=16*1024;
    private static readonly object Gate=new();
    private static readonly Queue<string> Entries=new();
    public static void Record(DiagnosticEvent activity,DiagnosticOutcome outcome,int? statusCode=null,Type? exceptionType=null)
    {
        var code=statusCode is >=100 and <=599?statusCode:null;
        var type=exceptionType is null?null:new string(exceptionType.Name.Where(c=>char.IsAsciiLetterOrDigit(c)||c=='_').Take(80).ToArray());
        var line=$"{DateTimeOffset.UtcNow:O} event={activity} outcome={outcome}"+(code is null?"":$" http={code}")+(string.IsNullOrEmpty(type)?"":$" exception={type}");
        lock(Gate){Entries.Enqueue(line);while(Entries.Count>MaxEntries||Encoding.UTF8.GetByteCount(string.Join("\n",Entries))>MaxCharacters)Entries.Dequeue();}
    }
    public static string Snapshot(){lock(Gate)return string.Join("\n",Entries);}
    public static void Clear(){lock(Gate)Entries.Clear();}
}

public sealed record FeedbackInput(string Title,string Reproduction,string Expected,string Actual,bool IncludeDiagnostics,string EditableDiagnostics,string Version,string Os,string DeviceModel);
public sealed record FeedbackReport(string FullText,Uri GitHubUri,bool RequiresCopy,string Notice);

public static class FeedbackReportBuilder
{
    public const int MaxEncodedUrlBytes=7000;
    private const string NewIssueUrl="https://github.com/Rafael-Ikaros/Component_Manager/issues/new";
    public static FeedbackReport Build(FeedbackInput input)
    {
        var title=string.IsNullOrWhiteSpace(input.Title)?"Windows 应用反馈":input.Title.Trim();
        var body=new StringBuilder().AppendLine("## 复现步骤").AppendLine(input.Reproduction.Trim()).AppendLine().AppendLine("## 期待结果").AppendLine(input.Expected.Trim()).AppendLine().AppendLine("## 实际结果").AppendLine(input.Actual.Trim());
        if(input.IncludeDiagnostics)body.AppendLine().AppendLine("## 诊断信息（提交前已由用户预览）").AppendLine("```text").AppendLine(input.EditableDiagnostics.Trim()).AppendLine("```");
        var full=$"标题：{title}\n\n{body}";var encoded=IssueUrl(title,body.ToString());
        if(Encoding.UTF8.GetByteCount(encoded)<=MaxEncodedUrlBytes)return new(full,new Uri(encoded),false,"GitHub Issue 是公开内容，请提交前检查并删除不希望公开的信息。");
        var titleOnly=IssueUrl(title,null);
        if(Encoding.UTF8.GetByteCount(titleOnly)<=MaxEncodedUrlBytes)return new(full,new Uri(titleOnly),true,"完整报告超过 GitHub 预填链接限制。请先复制或导出完整报告，再在网页中粘贴正文；当前链接只预填标题。");
        return new(full,new Uri(NewIssueUrl),true,"标题和正文均超过 GitHub 预填链接限制。请先复制或导出完整报告，再在空白 Issue 页面中粘贴标题与正文。");
    }
    private static string IssueUrl(string title,string? body)=>NewIssueUrl+"?title="+Uri.EscapeDataString(title)+(body is null?"":"&body="+Uri.EscapeDataString(body));
}
