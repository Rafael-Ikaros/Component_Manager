namespace ComponentVault.WinUI.Services.BatchInbound;

/// <summary>Calculates each drag frame from its original snapshot, so shrinking a range restores prior choices.</summary>
public static class BatchInboundRangeSelection
{
    public static bool[] ContextClick(IReadOnlyList<bool> original, int anchor, int current,
        bool control, bool shift)
    {
        ArgumentNullException.ThrowIfNull(original);
        if ((uint)anchor >= (uint)original.Count) throw new ArgumentOutOfRangeException(nameof(anchor));
        if ((uint)current >= (uint)original.Count) throw new ArgumentOutOfRangeException(nameof(current));
        if (shift) return Calculate(original, anchor, current, preserveExisting: control);

        var result = original.ToArray();
        if (control) result[current] = !result[current];
        else if (!result[current])
        {
            Array.Clear(result);
            result[current] = true;
        }
        return result;
    }

    public static bool[] ToggleAll(IReadOnlyList<bool> original)
    {
        ArgumentNullException.ThrowIfNull(original);
        return Enumerable.Repeat(!original.All(value => value), original.Count).ToArray();
    }

    public static bool[] Invert(IReadOnlyList<bool> original)
    {
        ArgumentNullException.ThrowIfNull(original);
        return original.Select(value => !value).ToArray();
    }

    public static bool[] Calculate(IReadOnlyList<bool> original, int anchor, int current,
        bool preserveExisting, bool selectRange = true)
    {
        ArgumentNullException.ThrowIfNull(original);
        if ((uint)anchor >= (uint)original.Count) throw new ArgumentOutOfRangeException(nameof(anchor));
        if ((uint)current >= (uint)original.Count) throw new ArgumentOutOfRangeException(nameof(current));

        var result = preserveExisting ? original.ToArray() : new bool[original.Count];
        for (var index = Math.Min(anchor, current); index <= Math.Max(anchor, current); index++)
            result[index] = selectRange;
        return result;
    }
}
