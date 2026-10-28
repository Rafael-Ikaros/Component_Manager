using ComponentVault.WinUI.Services.Updates;
using Xunit;

namespace ComponentVault.WinUI.CoreTests;

public sealed class StartupUpdateCheckTests
{
    [Theory]
    [InlineData("0.7.7", "v0.7.8", true)]
    [InlineData("0.7.8", "v0.7.8", false)]
    [InlineData("0.7.8.0", "v0.7.8", false)]
    [InlineData("0.7.9", "v0.7.8", false)]
    [InlineData("0.7.8-dev.1", "v0.7.8-dev.2", true)]
    [InlineData("0.7.8-dev.2", "v0.7.8", true)]
    [InlineData("unknown", "v0.7.8", false)]
    public async Task OnlyNewerRecognizedReleaseProducesNotification(string local, string remote, bool notify)
    {
        var release = new GitHubReleaseInfo(remote, GitHubReleaseParser.ParseNumericVersion(remote)!,
            null, "notes", new Uri("https://github.com/Rafael-Ikaros/Component_Manager/releases"), null);
        var result = new UpdateCheckResult(true, "checked", GitHubReleaseParser.Compare(local, remote), release);
        var actual = await new StartupUpdateCheck().CheckOnceAsync(local, UpdateChannel.Dev,
            (_, _, _) => Task.FromResult(result), CancellationToken.None);
        Assert.Equal(notify, actual is not null);
    }

    [Fact]
    public async Task FailureIsSilentAndDoesNotRetryInSameSession()
    {
        var session = new StartupUpdateCheck();
        var calls = 0;
        Task<UpdateCheckResult> Check(string version, UpdateChannel channel, CancellationToken token)
        {
            calls++;
            Assert.Equal("0.7.8", version);
            Assert.Equal(UpdateChannel.Stable, channel);
            throw new HttpRequestException("offline");
        }
        Assert.Null(await session.CheckOnceAsync("0.7.8", UpdateChannel.Stable, Check, CancellationToken.None));
        Assert.Null(await session.CheckOnceAsync("0.7.8", UpdateChannel.Stable, Check, CancellationToken.None));
        Assert.Equal(1, calls);
    }

    [Theory]
    [InlineData(false, true)]
    [InlineData(true, false)]
    public async Task FailureOrMissingReleaseDoesNotNotify(bool success, bool hasRelease)
    {
        var release = hasRelease ? new GitHubReleaseInfo("v0.7.8", new(0, 7, 8, null), null, "notes",
            new Uri("https://github.com/Rafael-Ikaros/Component_Manager/releases"), null) : null;
        var result = new UpdateCheckResult(success, "checked", UpdateComparison.UpdateAvailable, release);
        Assert.Null(await new StartupUpdateCheck().CheckOnceAsync("0.7.7", UpdateChannel.Stable,
            (_, _, _) => Task.FromResult(result), CancellationToken.None));
    }

    [Fact]
    public async Task RepeatedLoadedWhileCheckIsPendingDoesNotStartAnotherRequest()
    {
        var session = new StartupUpdateCheck();
        var pending = new TaskCompletionSource<UpdateCheckResult>();
        var repeatedCalls = 0;
        var first = session.CheckOnceAsync("0.7.8", UpdateChannel.Dev, (_, _, _) => pending.Task, CancellationToken.None);
        Assert.Null(await session.CheckOnceAsync("0.7.8", UpdateChannel.Dev,
            (_, _, _) =>
            {
                repeatedCalls++;
                return Task.FromResult(new UpdateCheckResult(false, "offline", UpdateComparison.UnknownVersion, null));
            }, CancellationToken.None));
        Assert.Equal(0, repeatedCalls);
        pending.SetResult(new(false, "offline", UpdateComparison.UnknownVersion, null));
        Assert.Null(await first);
    }
}
