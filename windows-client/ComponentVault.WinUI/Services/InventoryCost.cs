using System.Globalization;
using System.Numerics;
using System.Text.RegularExpressions;

namespace ComponentVault.WinUI.Services;

/// <summary>Exact decimal text arithmetic. Inventory averages use banker's rounding at 16 places.</summary>
public static class InventoryCost
{
    private static readonly BigInteger Scale = BigInteger.Pow(10, 16);
    private static BigInteger Parse(string value, int maxScale = 16)
    {
        value = value.Trim();
        if (!Regex.IsMatch(value, @"^\d+(?:\.\d+)?$", RegexOptions.CultureInvariant))
            throw new InvalidOperationException("金额必须是非负十进制数。");
        var parts = value.Split('.');
        var fraction = parts.Length == 2 ? parts[1] : "";
        if (fraction.Length > maxScale) throw new InvalidOperationException($"金额最多支持 {maxScale} 位小数。");
        return BigInteger.Parse(parts[0], CultureInfo.InvariantCulture) * Scale
            + BigInteger.Parse("0" + fraction.PadRight(16, '0'), CultureInfo.InvariantCulture);
    }
    private static string Format(BigInteger value)
    {
        var integer = BigInteger.DivRem(value, Scale, out var fraction);
        return fraction.IsZero ? integer.ToString(CultureInfo.InvariantCulture)
            : integer.ToString(CultureInfo.InvariantCulture) + "." + fraction.ToString("D16", CultureInfo.InvariantCulture).TrimEnd('0');
    }
    private static BigInteger Divide(BigInteger numerator, BigInteger denominator)
    {
        var quotient = BigInteger.DivRem(numerator, denominator, out var remainder);
        var comparison = (remainder * 2).CompareTo(denominator);
        return comparison > 0 || comparison == 0 && !quotient.IsEven ? quotient + 1 : quotient;
    }
    public static string Difference(string left, string right)
    {
        var delta = Parse(left) - Parse(right);
        return delta.Sign < 0 ? "-" + Format(BigInteger.Abs(delta)) : Format(delta);
    }
    public static string? Input(string? value) => string.IsNullOrWhiteSpace(value) ? null : Format(Parse(value, 8));
    public static string? Multiply(string? value, int quantity) => value is null ? null : Format(Parse(value) * quantity);
    public static string? Add(string? left, string? right) => left is null || right is null ? null : Format(Parse(left) + Parse(right));
    public static string? Average(string? amount, int quantity) => amount is null || quantity == 0 ? null : Format(Divide(Parse(amount), quantity));
    public static string? Inbound(int oldQuantity, string? oldAverage, int added, string? purchaseAmount)
    {
        if (added <= 0) throw new InvalidOperationException("入库数量必须为正整数。");
        if (purchaseAmount is null || oldQuantity > 0 && oldAverage is null) return null;
        return Format(Divide((oldQuantity == 0 ? BigInteger.Zero : Parse(oldAverage!) * oldQuantity) + Parse(purchaseAmount), (BigInteger)oldQuantity + added));
    }
}
