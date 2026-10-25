using ComponentVault.WinUI.Services.BatchInbound;
using Xunit;

namespace ComponentVault.WinUI.OrderImportTests;

public sealed class BatchInboundRangeSelectionTests
{
    [Fact]
    public void ContextClick_PlainClickOnFReplacesTheExistingABCSelection()
    {
        bool[] original = [true, true, true, false, false, false];

        var selected = BatchInboundRangeSelection.ContextClick(original, 0, 5, control: false, shift: false);

        Assert.Equal([false, false, false, false, false, true], selected);
        Assert.Equal([true, true, true, false, false, false], original);
    }

    [Fact]
    public void ContextClick_ControlClickOnFAddsItToTheExistingABCSelection()
    {
        bool[] original = [true, true, true, false, false, false];

        var selected = BatchInboundRangeSelection.ContextClick(original, 0, 5, control: true, shift: false);

        Assert.Equal([true, true, true, false, false, true], selected);
        Assert.Equal([true, true, true, false, false, false], original);
    }

    [Fact]
    public void ContextClick_PlainClickOnSelectedBPreservesTheEntireABCSelection()
    {
        bool[] original = [true, true, true, false, false, false];

        var selected = BatchInboundRangeSelection.ContextClick(original, 0, 1, control: false, shift: false);

        Assert.Equal([true, true, true, false, false, false], selected);
        Assert.NotSame(original, selected);
    }

    [Fact]
    public void ContextClick_ControlClickOnSelectedBClearsOnlyB()
    {
        bool[] original = [true, true, true, false, false, false];

        var selected = BatchInboundRangeSelection.ContextClick(original, 0, 1, control: true, shift: false);

        Assert.Equal([true, false, true, false, false, false], selected);
        Assert.Equal([true, true, true, false, false, false], original);
    }

    [Theory]
    [InlineData(1, 4)]
    [InlineData(4, 1)]
    public void ContextClick_ShiftReplacesSelectionWithTheInclusiveForwardOrBackwardRange(int anchor, int current)
    {
        bool[] original = [true, false, false, false, false, true];

        var selected = BatchInboundRangeSelection.ContextClick(original, anchor, current, control: false, shift: true);

        Assert.Equal([false, true, true, true, true, false], selected);
        Assert.Equal([true, false, false, false, false, true], original);
    }

    [Fact]
    public void ContextClick_ShiftOnAnAlreadySelectedRowStillExtendsFromTheSuppliedAnchor()
    {
        bool[] original = [true, true, true, false, false, false];

        var selected = BatchInboundRangeSelection.ContextClick(original, 4, 1, control: false, shift: true);

        Assert.Equal([false, true, true, true, true, false], selected);
        Assert.Equal([true, true, true, false, false, false], original);
    }

    [Theory]
    [InlineData(2, 4)]
    [InlineData(4, 2)]
    public void ContextClick_ControlShiftAddsTheRangeWhileKeepingChecksOutsideIt(int anchor, int current)
    {
        bool[] original = [true, false, false, false, false, true];

        var selected = BatchInboundRangeSelection.ContextClick(original, anchor, current, control: true, shift: true);

        Assert.Equal([true, false, true, true, true, true], selected);
        Assert.Equal([true, false, false, false, false, true], original);
    }

    [Theory]
    [InlineData(false, false, true)]
    [InlineData(true, false, false)]
    [InlineData(false, true, true)]
    [InlineData(true, true, true)]
    public void ContextClick_HandlesTheSingleRowBoundary(bool control, bool shift, bool expected)
    {
        var selected = BatchInboundRangeSelection.ContextClick([true], 0, 0, control, shift);

        Assert.Equal(new[] { expected }, selected);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public void ContextClick_AllModifierCombinationsAcceptReadOnlyInputAndReturnIndependentStorage(bool control, bool shift)
    {
        var original = Array.AsReadOnly(new[] { true, false, false, true });

        var selected = BatchInboundRangeSelection.ContextClick(original, 1, 2, control, shift);
        selected[0] = false;
        selected[1] = true;

        Assert.Equal([true, false, false, true], original);
    }

    [Theory]
    [InlineData(0, 5)]
    [InlineData(5, 0)]
    public void ContextClick_ShiftCanReachBothCollectionEdges(int anchor, int current)
    {
        var selected = BatchInboundRangeSelection.ContextClick([false, false, false, false, false, false],
            anchor, current, control: false, shift: true);

        Assert.Equal([true, true, true, true, true, true], selected);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public void ContextClick_RejectsNullAndEmptySnapshotsForEveryModifierCombination(bool control, bool shift)
    {
        Assert.Throws<ArgumentNullException>("original", () => BatchInboundRangeSelection.ContextClick(null!, 0, 0, control, shift));
        Assert.Throws<ArgumentOutOfRangeException>("anchor", () => BatchInboundRangeSelection.ContextClick([], 0, 0, control, shift));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(3)]
    [InlineData(int.MinValue)]
    [InlineData(int.MaxValue)]
    public void ContextClick_RejectsInvalidAnchorsEvenWithoutShift(int anchor)
    {
        Assert.Throws<ArgumentOutOfRangeException>("anchor", () =>
            BatchInboundRangeSelection.ContextClick([false, true, false], anchor, 1, control: false, shift: false));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(3)]
    [InlineData(int.MinValue)]
    [InlineData(int.MaxValue)]
    public void ContextClick_RejectsInvalidCurrentRows(int current)
    {
        Assert.Throws<ArgumentOutOfRangeException>("current", () =>
            BatchInboundRangeSelection.ContextClick([false, true, false], 1, current, control: true, shift: false));
    }

    [Fact]
    public void ToggleAll_PartialSelectionChecksEveryRowAndNextToggleClearsThem()
    {
        bool[] original = [true, false, false, true];

        var all = BatchInboundRangeSelection.ToggleAll(original);
        var cleared = BatchInboundRangeSelection.ToggleAll(all);

        Assert.Equal([true, true, true, true], all);
        Assert.Equal([false, false, false, false], cleared);
        Assert.Equal([true, false, false, true], original);
    }

    [Fact]
    public void ToggleAll_NoCheckedRowsSelectsEveryRow()
    {
        var selected = BatchInboundRangeSelection.ToggleAll([false, false, false]);

        Assert.Equal([true, true, true], selected);
    }

    [Fact]
    public void ToggleAll_AllCheckedRowsClearsEveryRowWithoutChangingTheSnapshot()
    {
        bool[] original = [true, true, true];

        var cleared = BatchInboundRangeSelection.ToggleAll(original);

        Assert.Equal([false, false, false], cleared);
        Assert.Equal([true, true, true], original);
    }

    [Fact]
    public void Invert_PreservesRowOrderAndFlipsEachChoice()
    {
        bool[] original = [true, false, false, true, false];

        var inverted = BatchInboundRangeSelection.Invert(original);
        var restored = BatchInboundRangeSelection.Invert(inverted);

        Assert.Equal([false, true, true, false, true], inverted);
        Assert.Equal([true, false, false, true, false], restored);
        Assert.Equal([true, false, false, true, false], original);
    }

    [Fact]
    public void Invert_ChangesAllCheckedAndAllUncheckedSelections()
    {
        Assert.Equal([false, false, false], BatchInboundRangeSelection.Invert([true, true, true]));
        Assert.Equal([true, true, true], BatchInboundRangeSelection.Invert([false, false, false]));
    }

    [Fact]
    public void BulkOperations_OnEmptySnapshotReturnEmptySelections()
    {
        bool[] original = [];

        Assert.Empty(BatchInboundRangeSelection.ToggleAll(original));
        Assert.Empty(BatchInboundRangeSelection.Invert(original));
        Assert.Empty(original);
    }

    [Fact]
    public void BulkOperations_AcceptReadOnlySnapshotsAndReturnIndependentResults()
    {
        var original = Array.AsReadOnly(new[] { true, false, true });

        var all = BatchInboundRangeSelection.ToggleAll(original);
        var inverted = BatchInboundRangeSelection.Invert(original);
        all[0] = false;
        inverted[1] = false;

        Assert.Equal([true, false, true], original);
        Assert.Equal([true, true, true], BatchInboundRangeSelection.ToggleAll(original));
        Assert.Equal([false, true, false], BatchInboundRangeSelection.Invert(original));
    }

    [Fact]
    public void BulkOperations_RejectNullSnapshots()
    {
        Assert.Throws<ArgumentNullException>("original", () => BatchInboundRangeSelection.ToggleAll(null!));
        Assert.Throws<ArgumentNullException>("original", () => BatchInboundRangeSelection.Invert(null!));
    }

    [Theory]
    [InlineData(1, 3)]
    [InlineData(3, 1)]
    public void PlainRange_ReplacesExistingChecksAndIncludesBothEnds(int anchor, int current)
    {
        bool[] original = [true, false, false, false, true];

        var selected = BatchInboundRangeSelection.Calculate(original, anchor, current, preserveExisting: false);

        Assert.Equal([false, true, true, true, false], selected);
        Assert.Equal([true, false, false, false, true], original);
    }

    [Fact]
    public void ControlRange_AddsToChecksOutsideTheDraggedRange()
    {
        bool[] original = [true, false, false, false, true, false];

        var selected = BatchInboundRangeSelection.Calculate(original, 1, 3, preserveExisting: true);

        Assert.Equal([true, true, true, true, true, false], selected);
        Assert.Equal([true, false, false, false, true, false], original);
    }

    [Fact]
    public void ClearRange_UnchecksTheRangeWithoutUncheckingUnrelatedRows()
    {
        bool[] original = [true, true, false, true, true, true];

        var selected = BatchInboundRangeSelection.Calculate(original, 3, 1, preserveExisting: true, selectRange: false);

        Assert.Equal([true, false, false, false, true, true], selected);
        Assert.Equal([true, true, false, true, true, true], original);
    }

    [Fact]
    public void PlainDrag_ShrinksAndCrossesItsAnchorWithoutLeavingASelectionTrail()
    {
        bool[] original = [true, false, false, false, false, true];

        var extended = BatchInboundRangeSelection.Calculate(original, 2, 5, preserveExisting: false);
        var shrunk = BatchInboundRangeSelection.Calculate(original, 2, 3, preserveExisting: false);
        var reversed = BatchInboundRangeSelection.Calculate(original, 2, 0, preserveExisting: false);

        Assert.Equal([false, false, true, true, true, true], extended);
        Assert.Equal([false, false, true, true, false, false], shrunk);
        Assert.Equal([true, true, true, false, false, false], reversed);
    }

    [Fact]
    public void ControlDrag_ShrinkingRestoresTheOriginalCheckedAndUncheckedRows()
    {
        bool[] original = [true, false, false, false, true, false];

        var extended = BatchInboundRangeSelection.Calculate(original, 1, 5, preserveExisting: true);
        var shrunk = BatchInboundRangeSelection.Calculate(original, 1, 2, preserveExisting: true);
        var reversed = BatchInboundRangeSelection.Calculate(original, 1, 0, preserveExisting: true);

        Assert.Equal([true, true, true, true, true, true], extended);
        Assert.Equal([true, true, true, false, true, false], shrunk);
        Assert.Equal([true, true, false, false, true, false], reversed);
    }

    [Fact]
    public void ClearingDrag_ShrinkingAndReversingRestoresChecksOutsideTheNewRange()
    {
        bool[] original = [true, true, false, true, true, false, true];

        var extended = BatchInboundRangeSelection.Calculate(original, 3, 6, preserveExisting: true, selectRange: false);
        var shrunk = BatchInboundRangeSelection.Calculate(original, 3, 4, preserveExisting: true, selectRange: false);
        var reversed = BatchInboundRangeSelection.Calculate(original, 3, 0, preserveExisting: true, selectRange: false);

        Assert.Equal([true, true, false, false, false, false, false], extended);
        Assert.Equal([true, true, false, false, false, false, true], shrunk);
        Assert.Equal([false, false, false, false, true, false, true], reversed);
    }

    [Fact]
    public void SuppliedShiftAnchor_RemainsTheRangeOriginAcrossRepeatedExtensions()
    {
        bool[] original = [true, true, false, false, false, false, false];
        const int previousAnchor = 1;

        var firstExtension = BatchInboundRangeSelection.Calculate(original, previousAnchor, 4, preserveExisting: false);
        var secondExtension = BatchInboundRangeSelection.Calculate(firstExtension, previousAnchor, 6, preserveExisting: false);
        var contraction = BatchInboundRangeSelection.Calculate(secondExtension, previousAnchor, 2, preserveExisting: false);

        Assert.Equal([false, true, true, true, true, false, false], firstExtension);
        Assert.Equal([false, true, true, true, true, true, true], secondExtension);
        Assert.Equal([false, true, true, false, false, false, false], contraction);
    }

    [Fact]
    public void SuppliedShiftAnchor_ControlExtensionPreservesOtherChecks()
    {
        bool[] original = [true, false, true, false, false, false, true];

        var selected = BatchInboundRangeSelection.Calculate(original, 2, 4, preserveExisting: true);

        Assert.Equal([true, false, true, true, true, false, true], selected);
    }

    [Theory]
    [InlineData(0, 4)]
    [InlineData(4, 0)]
    public void Range_CanReachBothCollectionEdges(int anchor, int current)
    {
        var selected = BatchInboundRangeSelection.Calculate([false, false, false, false, false], anchor, current, preserveExisting: false);

        Assert.Equal([true, true, true, true, true], selected);
    }

    [Fact]
    public void SingleRowRange_ChangesOnlyTheAnchor()
    {
        var replaced = BatchInboundRangeSelection.Calculate([true, false, true], 1, 1, preserveExisting: false);
        var added = BatchInboundRangeSelection.Calculate([true, false, true], 1, 1, preserveExisting: true);
        var removed = BatchInboundRangeSelection.Calculate([true], 0, 0, preserveExisting: true, selectRange: false);

        Assert.Equal([false, true, false], replaced);
        Assert.Equal([true, true, true], added);
        Assert.Equal([false], removed);
    }

    [Fact]
    public void Range_ResultOwnsItsStorageAndAcceptsAReadOnlySnapshot()
    {
        var original = Array.AsReadOnly(new[] { true, false, false, true });

        var first = BatchInboundRangeSelection.Calculate(original, 1, 2, preserveExisting: true);
        first[0] = false;
        first[1] = false;
        var second = BatchInboundRangeSelection.Calculate(original, 1, 2, preserveExisting: true);

        Assert.Equal([true, false, false, true], original);
        Assert.Equal([true, true, true, true], second);
    }

    [Fact]
    public void Range_RejectsANullSnapshot()
    {
        Assert.Throws<ArgumentNullException>("original", () => BatchInboundRangeSelection.Calculate(null!, 0, 0, preserveExisting: false));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(3)]
    [InlineData(int.MinValue)]
    [InlineData(int.MaxValue)]
    public void Range_RejectsAnAnchorOutsideTheSnapshot(int anchor)
    {
        Assert.Throws<ArgumentOutOfRangeException>("anchor", () => BatchInboundRangeSelection.Calculate([false, true, false], anchor, 1, preserveExisting: false));
    }

    [Theory]
    [InlineData(-1)]
    [InlineData(3)]
    [InlineData(int.MinValue)]
    [InlineData(int.MaxValue)]
    public void Range_RejectsACurrentRowOutsideTheSnapshot(int current)
    {
        Assert.Throws<ArgumentOutOfRangeException>("current", () => BatchInboundRangeSelection.Calculate([false, true, false], 1, current, preserveExisting: true));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void EmptySnapshot_RejectsARangeBecauseItHasNoAnchor(bool preserveExisting)
    {
        Assert.Throws<ArgumentOutOfRangeException>("anchor", () => BatchInboundRangeSelection.Calculate([], 0, 0, preserveExisting));
    }
}
