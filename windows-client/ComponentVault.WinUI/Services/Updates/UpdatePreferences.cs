namespace ComponentVault.WinUI.Services.Updates;

public static class UpdatePreferences
{
    private static string ChannelPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "ComponentVault", "update-channel.txt");

    public static UpdateChannel LoadChannel()
    {
        try { return File.ReadAllText(ChannelPath).Trim() == "dev" ? UpdateChannel.Dev : UpdateChannel.Stable; }
        catch { return UpdateChannel.Stable; }
    }

    public static void SaveChannel(UpdateChannel channel)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(ChannelPath)!);
            File.WriteAllText(ChannelPath, channel == UpdateChannel.Dev ? "dev" : "stable");
        }
        catch { }
    }
}
