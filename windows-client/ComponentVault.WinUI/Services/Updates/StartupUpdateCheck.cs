namespace ComponentVault.WinUI.Services.Updates;

/// <summary>One optional check per application session; manual checks remain independent.</summary>
public sealed class StartupUpdateCheck
{
    private bool _started;

    public async Task<UpdateCheckResult?> CheckOnceAsync(
        string version,
        UpdateChannel channel,
        Func<string, UpdateChannel, CancellationToken, Task<UpdateCheckResult>> check,
        CancellationToken cancellationToken)
    {
        if (_started) return null;
        _started = true;
        try
        {
            var result = await check(version, channel, cancellationToken);
            return result.IsSuccess && result.Comparison == UpdateComparison.UpdateAvailable
                && result.Release is not null ? result : null;
        }
        catch (OperationCanceledException) { return null; }
        catch (Exception) { return null; }
    }
}
