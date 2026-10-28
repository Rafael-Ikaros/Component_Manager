using System.Reflection;

namespace ComponentVault.WinUI.Services;

public static class AppVersion
{
    public static string Current
    {
        get
        {
            var informational = Assembly.GetExecutingAssembly()
                .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
            if (!string.IsNullOrWhiteSpace(informational)) return informational.Split('+')[0];
            try
            {
                var version = Windows.ApplicationModel.Package.Current.Id.Version;
                return $"{version.Major}.{version.Minor}.{version.Build}.{version.Revision}";
            }
            catch
            {
                return Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "未知";
            }
        }
    }
}
