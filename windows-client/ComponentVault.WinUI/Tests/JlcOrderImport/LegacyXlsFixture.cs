using System.Text;

namespace ComponentVault.WinUI.OrderImportTests;

// A deliberately small BIFF8 writer for synthetic test data. No customer workbook is embedded.
internal static class LegacyXlsFixture
{
    public static MemoryStream Create(params object?[][] rows)
    {
        var strings = rows.SelectMany(row => row).OfType<string>().Distinct().ToArray();
        var indexes = strings.Select((value, index) => (value, index)).ToDictionary(x => x.value, x => x.index);
        using var globals = new MemoryStream();
        using var writer = new BinaryWriter(globals, Encoding.Unicode, true);
        Record(writer, 0x0809, body => { body.Write((ushort)0x0600); body.Write((ushort)0x0005); body.Write(0x00000DBB); body.Write(0x00000041); body.Write(0x00000006); });
        Record(writer, 0x0042, body => body.Write((ushort)1200));
        Record(writer, 0x00E0, body => body.Write(new byte[20]));
        Record(writer, 0x00FC, body =>
        {
            body.Write(strings.Length); body.Write(strings.Length);
            foreach (var value in strings) { body.Write((ushort)value.Length); body.Write((byte)1); body.Write(Encoding.Unicode.GetBytes(value)); }
        });
        var boundSheetRecord = globals.Position;
        Record(writer, 0x0085, body => { body.Write(0); body.Write((ushort)0); body.Write((byte)5); body.Write((byte)0); body.Write(Encoding.ASCII.GetBytes("Order")); });
        Record(writer, 0x000A, _ => { });
        var worksheetStart = (int)globals.Length;
        globals.Position = boundSheetRecord + 4;
        writer.Write(worksheetStart);
        globals.Position = worksheetStart;
        Record(writer, 0x0809, body => { body.Write((ushort)0x0600); body.Write((ushort)0x0010); body.Write(0x00000DBB); body.Write(0x00000041); body.Write(0x00000006); });
        Record(writer, 0x0200, body => { body.Write(0); body.Write(rows.Length); body.Write((ushort)0); body.Write((ushort)rows.Max(row => row.Length)); body.Write((ushort)0); });
        for (var row = 0; row < rows.Length; row++)
        for (var column = 0; column < rows[row].Length; column++)
        {
            var value = rows[row][column];
            if (value is null) continue;
            var r = row; var c = column;
            Record(writer, value is string ? (ushort)0x00FD : (ushort)0x0203, body =>
            {
                body.Write((ushort)r); body.Write((ushort)c); body.Write((ushort)0);
                if (value is string text) body.Write(indexes[text]);
                else body.Write(Convert.ToDouble(value, System.Globalization.CultureInfo.InvariantCulture));
            });
        }
        Record(writer, 0x000A, _ => { });
        return CompoundFile(globals.ToArray());
    }

    private static void Record(BinaryWriter writer, ushort id, Action<BinaryWriter> write)
    {
        using var payload = new MemoryStream();
        using (var body = new BinaryWriter(payload, Encoding.Unicode, true)) write(body);
        if (payload.Length > 8224) throw new InvalidOperationException("Test fixture needs a smaller shared string table.");
        writer.Write(id); writer.Write((ushort)payload.Length); writer.Write(payload.ToArray());
    }

    private static MemoryStream CompoundFile(byte[] workbook)
    {
        const int sectorSize = 512;
        var sectorCount = Math.Max(8, (workbook.Length + sectorSize - 1) / sectorSize);
        if (sectorCount > 125) throw new InvalidOperationException("Test fixture exceeds its single FAT sector.");
        var result = new MemoryStream(new byte[(3 + sectorCount) * sectorSize], true);
        using var writer = new BinaryWriter(result, Encoding.Unicode, true);
        writer.Write(new byte[] { 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 });
        result.Position = 24; writer.Write((ushort)0x003E); writer.Write((ushort)3); writer.Write((ushort)0xFFFE); writer.Write((ushort)9); writer.Write((ushort)6);
        result.Position = 44; writer.Write(1); writer.Write(1); writer.Write(0); writer.Write(4096); writer.Write(-2); writer.Write(0); writer.Write(-2); writer.Write(0);
        writer.Write(0);
        for (var i = 1; i < 109; i++) writer.Write(-1);
        result.Position = sectorSize;
        for (var i = 0; i < 128; i++) writer.Write(i switch { 0 => -3, 1 => -2, _ when i < sectorCount + 1 => i + 1, _ when i == sectorCount + 1 => -2, _ => -1 });
        result.Position = sectorSize * 2;
        DirectoryEntry(writer, "Root Entry", 5, 1, -2, 0);
        DirectoryEntry(writer, "Workbook", 2, -1, 2, sectorCount * sectorSize);
        result.Position = sectorSize * 3; writer.Write(workbook);
        result.Position = 0;
        return result;
    }

    private static void DirectoryEntry(BinaryWriter writer, string name, byte type, int child, int sector, long size)
    {
        var start = writer.BaseStream.Position;
        writer.Write(Encoding.Unicode.GetBytes(name + "\0"));
        writer.BaseStream.Position = start + 64;
        writer.Write((ushort)((name.Length + 1) * 2)); writer.Write(type); writer.Write((byte)1);
        writer.Write(-1); writer.Write(-1); writer.Write(child);
        writer.BaseStream.Position = start + 116;
        writer.Write(sector); writer.Write(size);
        writer.BaseStream.Position = start + 128;
    }
}
