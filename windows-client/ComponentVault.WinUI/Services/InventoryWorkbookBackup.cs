using System.Globalization;
using System.IO.Compression;
using System.Xml.Linq;
using Microsoft.Data.Sqlite;
using ComponentVault.WinUI.Models;

namespace ComponentVault.WinUI.Services;

public sealed record InventoryRestorePreview(string SourceFormat, int Components, int Locations, int Allocations, int Movements, IReadOnlyList<string> Issues, IReadOnlyList<string>? Warnings = null, string Fingerprint = "", string DatabaseVersion = "", int NewComponents = 0, int ConflictingComponents = 0)
{
    public bool CanImport => Issues.Count == 0 && (Components > 0 || Locations > 0);
}

public sealed class InventoryWorkbookBackup
{
    private readonly InventoryStore _store;
    public InventoryWorkbookBackup(InventoryStore store) => _store = store;

    public void Export(string path)
    {
        var sheets = ReadDatabase();
        var imageRoot = Path.Combine(Path.GetDirectoryName(_store.DatabasePath)!, "images");
        var images = sheets.Components.Select((row, index) =>
        {
            var imagePath = PrivateProductImageStore.Resolve(imageRoot, Text(row[6]));
            return imagePath is null ? null : new WorkbookImage("components", index + 1, 12, File.ReadAllBytes(imagePath));
        }).Where(image => image is not null).Cast<WorkbookImage>().ToArray();
        SimpleXlsx.Write(path, new Dictionary<string, IReadOnlyList<object?[]>>
        {
            ["meta"] = [
                ["format", "component-vault"], ["schemaVersion", "2"],
                ["exportedAt", DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture)]
            ],
            ["components"] = WithHeader(["id","sku","name","category","package_name","location","description","quantity","min_stock","updated_at","deleted","base_updated_at","image_preview","inventory_unit_cost"], sheets.Components),
            ["storage_locations"] = WithHeader(["id","name","updated_at","deleted"], sheets.Locations),
            ["allocations"] = WithHeader(["component_id","location_id","quantity"], sheets.Allocations),
            ["stock_movements"] = WithHeader(["id","component_id","movement_type","quantity","reason","note","happened_at","updated_at","deleted","location_id","destination_location_id","purchase_amount","cost_amount"], sheets.Movements),
        }, images);
    }

    public InventoryRestorePreview Preview(string path)
    {
        try
        {
            var workbook = SimpleXlsx.Read(path);
            var parsed = Parse(workbook);
            ValidateImages(path, parsed);
            var state = CurrentState();
            var conflicts = parsed.Components.Count(row => state.Ids.Contains(Text(row[0])) || state.Skus.Contains(Text(row[1])));
            return new(parsed.Format, parsed.Components.Count, parsed.Locations.Count, parsed.Allocations.Count, parsed.Movements.Count, Validate(parsed), [], FileFingerprint(path), state.Version, parsed.Components.Count-conflicts, conflicts);
        }
        catch (Exception exception) { return new("unknown", 0, 0, 0, 0, [exception.Message]); }
    }

    public OperationResult ImportNewOnly(string path, InventoryRestorePreview? approvedPreview = null)
    {
        if (approvedPreview is not null && (approvedPreview.Fingerprint != FileFingerprint(path) || approvedPreview.DatabaseVersion != CurrentState().Version))
            return OperationResult.Failure("文件或当前库存已在预览后变化，请重新生成预览。");
        var workbook = SimpleXlsx.Read(path);
        var data = Parse(workbook);
        var issues = Validate(data);
        if (issues.Count > 0) return OperationResult.Failure(string.Join("；", issues));
        ValidateImages(path, data);
        var embeddedImages = ReadComponentImages(path, data);
        var imageRoot = Path.Combine(Path.GetDirectoryName(_store.DatabasePath)!, "images");
        var createdImages = new List<string>();
        using var connection = new SqliteConnection($"Data Source={_store.DatabasePath}");
        connection.Open();
        using var transaction = connection.BeginTransaction();
        var existingIds = ScalarSet(connection, "SELECT id FROM components");
        var existingSkus = ScalarSet(connection, "SELECT sku FROM components WHERE deleted=0");
        var importedIds = new HashSet<string>(StringComparer.Ordinal);
        var imported = 0;
        foreach (var location in data.Locations)
        {
            using var command = connection.CreateCommand();
            command.CommandText = "INSERT OR IGNORE INTO storage_locations(id,name,updated_at,deleted) VALUES($id,$name,$at,$deleted)";
            command.Parameters.AddWithValue("$id", location[0]!); command.Parameters.AddWithValue("$name", location[1]!);
            command.Parameters.AddWithValue("$at", location[2]!); command.Parameters.AddWithValue("$deleted", Bool(location[3]) ? 1 : 0); if(command.ExecuteNonQuery()>0) Queue(connection,"storage_location",Text(location[0]),Text(location[2]));
        }
        try
        {
        for (var componentIndex = 0; componentIndex < data.Components.Count; componentIndex++)
        {
            var component = data.Components[componentIndex];
            var id = Text(component[0]); var sku = Text(component[1]);
            if (existingIds.Contains(id) || existingSkus.Contains(sku)) continue;
            if (embeddedImages.TryGetValue(componentIndex + 1, out var imageBytes))
            {
                var existingImageKeys = Directory.Exists(imageRoot)
                    ? Directory.GetFiles(imageRoot).Select(Path.GetFileName).ToHashSet(StringComparer.OrdinalIgnoreCase)
                    : [];
                var key = PrivateProductImageStore.Persist(imageRoot, imageBytes);
                var persistedPath = Path.Combine(imageRoot, key);
                if (!existingImageKeys.Contains(key)) createdImages.Add(persistedPath);
                component[6] = PrivateProductImageStore.AppendMarker(Text(component[6]), key);
            }
            using var command = connection.CreateCommand();
            command.CommandText = "INSERT INTO components(id,sku,name,category,package_name,location,description,quantity,min_stock,updated_at,deleted,base_updated_at) VALUES($id,$sku,$name,$category,$package,$location,$description,$quantity,$min_stock,$updated_at,$deleted,$base)";
            string[] names = ["$id","$sku","$name","$category","$package","$location"];
            for (var i=0;i<names.Length;i++) command.Parameters.AddWithValue(names[i], component[i] ?? string.Empty);
            command.Parameters.AddWithValue("$description", component[6] ?? DBNull.Value); command.Parameters.AddWithValue("$quantity", Int(component[7])); command.Parameters.AddWithValue("$min_stock", Int(component[8]));
            command.Parameters.AddWithValue("$updated_at", component[9]!); command.Parameters.AddWithValue("$deleted", Bool(component[10]) ? 1 : 0); command.Parameters.AddWithValue("$base", component[11] ?? DBNull.Value); command.ExecuteNonQuery();
            using (var cost = connection.CreateCommand()) { cost.CommandText="UPDATE components SET inventory_unit_cost=$cost WHERE id=$id"; cost.Parameters.AddWithValue("$cost",component.ElementAtOrDefault(13) ?? DBNull.Value);cost.Parameters.AddWithValue("$id",id);cost.ExecuteNonQuery(); }
            existingIds.Add(id); existingSkus.Add(sku); importedIds.Add(id); imported++;
            Queue(connection,"component",id,Text(component[9]));
        }
        foreach (var allocation in data.Allocations.Where(row => importedIds.Contains(Text(row[0]))))
        {
            using var command = connection.CreateCommand();
            command.CommandText = "INSERT OR IGNORE INTO component_allocations(component_id,location_id,quantity) VALUES($component,$location,$quantity)";
            command.Parameters.AddWithValue("$component", allocation[0]!); command.Parameters.AddWithValue("$location", allocation[1]!); command.Parameters.AddWithValue("$quantity", Int(allocation[2])); command.ExecuteNonQuery();
        }
        foreach (var movement in data.Movements.Where(row => importedIds.Contains(Text(row[1])))) { InsertMovement(connection, movement); Queue(connection,"stock_movement",Text(movement[0]),Text(movement[7])); }
        if (data.IsForeign)
        {
            foreach (var component in data.Components.Where(row => !Bool(row[10]) && Int(row[7]) > 0 && importedIds.Contains(Text(row[0]))))
                InsertInitialMovement(connection, component);
        }
        transaction.Commit();
        return OperationResult.Success($"迁入完成，新增 {imported} 个元器件；已有 ID/SKU 未覆盖。");
        }
        catch
        {
            transaction.Rollback();
            foreach (var image in createdImages) if (File.Exists(image)) File.Delete(image);
            throw;
        }
    }

    private static Dictionary<int, byte[]> ReadComponentImages(string path, ParsedBackup data)
        => SimpleXlsx.ReadImages(path, "components", data.IsForeign ? new HashSet<int> { 10, 11 } : new HashSet<int> { 12 });

    private static void ValidateImages(string path, ParsedBackup data)
    {
        var images = ReadComponentImages(path, data);
        if (images.Values.Sum(bytes => (long)bytes.Length) > 25L * 1024 * 1024)
            throw new InvalidDataException("工作簿图片总量超过 25 MiB 限制。");
        var temporaryRoot = Path.Combine(Path.GetTempPath(), "component-vault-image-validation-" + Guid.NewGuid().ToString("N"));
        try { foreach (var bytes in images.Values) PrivateProductImageStore.Persist(temporaryRoot, bytes); }
        finally { if (Directory.Exists(temporaryRoot)) Directory.Delete(temporaryRoot, true); }
    }

    private BackupRows ReadDatabase()
    {
        using var c = new SqliteConnection($"Data Source={_store.DatabasePath}"); c.Open(); using var transaction=c.BeginTransaction();
        var result = new BackupRows(
            BooleanColumn(Query(c,"SELECT id,sku,name,category,package_name,location,description,quantity,min_stock,updated_at,deleted,base_updated_at,NULL,inventory_unit_cost FROM components"),10),
            BooleanColumn(Query(c,"SELECT id,name,updated_at,deleted FROM storage_locations"),3),
            Query(c,"SELECT component_id,location_id,quantity FROM component_allocations"),
            BooleanColumn(Query(c,"SELECT id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id,purchase_amount,cost_amount FROM stock_movements"),8)
        ); transaction.Commit(); return result;
    }

    private static ParsedBackup Parse(Dictionary<string,List<object?[]>> wb)
    {
        if (wb.TryGetValue("meta", out var meta) && meta.Any(row => Text(row.ElementAtOrDefault(0))=="format" && Text(row.ElementAtOrDefault(1))=="component-vault"))
        {
            if (!meta.Any(row => Text(row.ElementAtOrDefault(0))=="schemaVersion" && Text(row.ElementAtOrDefault(1)) is "1" or "2")) throw new InvalidDataException("不支持的 component-vault schemaVersion。");
            var version2 = meta.Any(row => Text(row.ElementAtOrDefault(0))=="schemaVersion" && Text(row.ElementAtOrDefault(1))=="2");
            RequireHeader(wb,"components",["id","sku","name","category","package_name","location","description","quantity","min_stock","updated_at","deleted","base_updated_at","image_preview","inventory_unit_cost"]);
            RequireHeader(wb,"storage_locations",["id","name","updated_at","deleted"]);
            RequireHeader(wb,"allocations",["component_id","location_id","quantity"]);
            RequireHeader(wb,"stock_movements",["id","component_id","movement_type","quantity","reason","note","happened_at","updated_at","deleted","location_id","destination_location_id","purchase_amount","cost_amount"]);
            if(version2 && (wb["components"][0].Length!=14 || wb["stock_movements"][0].Length!=13))throw new InvalidDataException("schema2 缺少成本列。");
            if(!version2 && (wb["components"][0].Length!=13 || wb["stock_movements"][0].Length!=11))throw new InvalidDataException("schema1 不应含成本列。");
            var components=Rows(wb,"components");var movements=Rows(wb,"stock_movements");
            foreach(var row in components) NormalizeCostCell(row,13);
            foreach(var row in movements) { NormalizeCostCell(row,11);NormalizeCostCell(row,12); }
            return new("component-vault", components, Rows(wb,"storage_locations"), Rows(wb,"allocations"), movements, false);
        }
        if (meta?.Any(row => Text(row.ElementAtOrDefault(0))=="schemaVersion" && Text(row.ElementAtOrDefault(1))=="1") == true
            && wb.ContainsKey("inventory_items")) return ConvertLegacy(wb);
        throw new InvalidDataException("工作簿缺少 format=component-vault/schemaVersion=1，且不是受支持的 LCSC schema1。 ");
    }

    private static ParsedBackup ConvertLegacy(Dictionary<string,List<object?[]>> wb)
    {
        var legacyLocations = Table(wb,"storage_locations"); var legacyComponents = Table(wb,"components"); var items = Table(wb,"inventory_items");
        var now = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        var locationMap = legacyLocations.ToDictionary(r=>Text(r["id"]),r=>Text(r["code"]),StringComparer.Ordinal);
        var locations = legacyLocations.Select(r => new object?[] { Text(r["code"]), Text(r["displayName"]) is { Length:>0 } n?n:Text(r["code"]), Epoch(r["createdAt"]), false }).ToList();
        var allocations = items.Select(r => new object?[] { "cmp-legacy-"+Text(r["componentId"]), locationMap.GetValueOrDefault(Text(r["locationId"]))??"", Int(r["quantity"]) }).ToList();
        var totals = allocations.GroupBy(r=>Text(r[0])).ToDictionary(g=>g.Key,g=>g.Sum(r=>Int(r[2])));
        var components = legacyComponents.Select(r => { var id="cmp-legacy-"+Text(r["id"]); var notes=string.Join("\n", new[]{Text(r["description"]), "品牌："+Text(r["brand"]), "规格："+Text(r["specJson"]), "来源："+Text(r["sourceUrl"])}.Where(x=>!string.IsNullOrWhiteSpace(x) && !x.EndsWith('：'))); return new object?[]{id,Text(r["partNumber"]),Text(r["name"]),Text(r["category"]),Text(r["packageName"]),allocations.FirstOrDefault(a=>Text(a[0])==id)?[1]??"__unassigned__",notes,totals.GetValueOrDefault(id),0,Epoch(r["updatedAt"]),false,null,null}; }).ToList();
        return new("lcsc-android-erp",components,locations,allocations,[],true);
    }

    private static List<string> Validate(ParsedBackup d)
    {
        var issues=new List<string>();
        Duplicate(d.Components,0,"组件 ID",issues); Duplicate(d.Components.Where(r=>!Bool(r[10])).ToList(),1,"活动组件 SKU",issues); Duplicate(d.Locations,0,"库位 ID",issues); Duplicate(d.Allocations,r=>Text(r[0])+"\0"+Text(r[1]),"分配键",issues); Duplicate(d.Movements,0,"流水 ID",issues);
        var componentIds=d.Components.Select(r=>Text(r[0])).ToHashSet(); var locationIds=d.Locations.Select(r=>Text(r[0])).ToHashSet();
        foreach(var r in d.Allocations) { if(!componentIds.Contains(Text(r[0]))||!locationIds.Contains(Text(r[1]))) issues.Add("分配存在孤立外键。"); TryNonNegative(r[2],"分配数量",issues); }
        foreach(var r in d.Components) { TryNonNegative(r[7],"组件数量",issues); TryNonNegative(r[8],"最低库存",issues); Timestamp(r[9],"组件 updated_at",issues); if(Text(r[11]).Length>0)Timestamp(r[11],"组件 base_updated_at",issues); }
        foreach(var r in d.Locations) { if(Text(r[0]).Length>120||Text(r[1]).Length is 0 or >200)issues.Add("库位编码或名称长度无效。"); Timestamp(r[2],"库位 updated_at",issues); }
        foreach(var r in d.Movements) { if(!componentIds.Contains(Text(r[1]))) issues.Add("流水存在孤立组件外键。"); if(Text(r[9]).Length>0&&!locationIds.Contains(Text(r[9]))||Text(r[10]).Length>0&&!locationIds.Contains(Text(r[10])))issues.Add("流水存在孤立库位外键。"); Timestamp(r[6],"流水 happened_at",issues);Timestamp(r[7],"流水 updated_at",issues);try { var q=Int(r[3]); if(Text(r[2])=="transfer" && (q<=0 || Text(r[9])==Text(r[10]))) issues.Add("调拨流水的数量或库位无效。"); } catch { issues.Add("流水数量必须是无小数的 32 位整数。"); } }
        foreach(var g in d.Allocations.GroupBy(r=>Text(r[0]))) { var c=d.Components.FirstOrDefault(r=>Text(r[0])==g.Key); if(c!=null && g.Sum(r=>(long)Int(r[2]))!=Int(c[7])) issues.Add($"组件 {g.Key} 分配合计不等于总库存。"); }
        if(d.Components.Any(r=>!d.Allocations.Any(a=>Text(a[0])==Text(r[0])))) issues.Add("每个组件至少需要一条库位分配（零库存也需要）。");
        foreach(var row in d.Components) { ValidateMoney(row.ElementAtOrDefault(13),false,16,issues);if(Int(row[7])==0 && row.ElementAtOrDefault(13) is not null)issues.Add("零库存均价必须为空。"); }
        foreach(var row in d.Movements) { ValidateMoney(row.ElementAtOrDefault(11),false,8,issues);ValidateMoney(row.ElementAtOrDefault(12),true,16,issues);if(Text(row[2])!="inbound" && row.ElementAtOrDefault(11) is not null)issues.Add("非采购入库流水不能包含采购金额。"); }
        return issues.Distinct().ToList();
    }

    private static void NormalizeCostCell(object?[] row,int index)
    {
        if(index<row.Length && string.IsNullOrWhiteSpace(Text(row[index])))row[index]=null;
    }
    private static void ValidateMoney(object? value,bool signed,int precision,List<string> issues)
    {
        if(value is null || string.IsNullOrWhiteSpace(Text(value)))return;
        if(value is not string) { issues.Add("成本必须以文本单元格保存。");return; }
        var text=Text(value);
        if(!System.Text.RegularExpressions.Regex.IsMatch(text,(signed?"^-?":"^")+@"\d+(?:\.\d{1,"+precision+"})?$"))issues.Add("成本必须为有效十进制金额文本。");
    }
    private static void InsertMovement(SqliteConnection c, object?[] r) { using var q=c.CreateCommand(); q.CommandText="INSERT OR IGNORE INTO stock_movements(id,component_id,movement_type,quantity,reason,note,happened_at,updated_at,deleted,location_id,destination_location_id) VALUES($a,$b,$c,$d,$e,$f,$g,$h,$i,$j,$k)"; for(int i=0;i<11;i++) q.Parameters.AddWithValue("$"+(char)('a'+i), r[i]??DBNull.Value); if(q.ExecuteNonQuery()==0)return; using var cost=c.CreateCommand();cost.CommandText="UPDATE stock_movements SET purchase_amount=$purchase,cost_amount=$cost WHERE id=$id";cost.Parameters.AddWithValue("$id",r[0]!);cost.Parameters.AddWithValue("$purchase",r.ElementAtOrDefault(11)??DBNull.Value);cost.Parameters.AddWithValue("$cost",r.ElementAtOrDefault(12)??DBNull.Value);cost.ExecuteNonQuery(); }
    private static void InsertInitialMovement(SqliteConnection c, object?[] component) { var r=new object?[]{"mov-import-"+Guid.NewGuid().ToString("N"),component[0],"inbound",component[7],"LCSC schema1 initial inventory","源文件无历史流水，仅记录迁入时初始库存",component[9],component[9],false,component[5],null}; InsertMovement(c,r); Queue(c,"stock_movement",Text(r[0]),Text(r[7])); }
    private static void Queue(SqliteConnection c,string type,string id,string at){using var q=c.CreateCommand();q.CommandText="INSERT INTO sync_queue(entity_type,entity_id,entity_updated_at,created_at) VALUES($t,$id,$at,$now) ON CONFLICT(entity_type,entity_id) DO UPDATE SET entity_updated_at=excluded.entity_updated_at,created_at=excluded.created_at";q.Parameters.AddWithValue("$t",type);q.Parameters.AddWithValue("$id",id);q.Parameters.AddWithValue("$at",at);q.Parameters.AddWithValue("$now",DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'",CultureInfo.InvariantCulture));q.ExecuteNonQuery();}
    private static HashSet<string> ScalarSet(SqliteConnection c,string sql) { using var q=c.CreateCommand();q.CommandText=sql;using var r=q.ExecuteReader();var s=new HashSet<string>(StringComparer.OrdinalIgnoreCase);while(r.Read())s.Add(r.GetString(0));return s; }
    private static List<object?[]> Query(SqliteConnection c,string sql) { using var q=c.CreateCommand();q.CommandText=sql;using var r=q.ExecuteReader();var rows=new List<object?[]>();while(r.Read()){var a=new object?[r.FieldCount];r.GetValues(a);for(int i=0;i<a.Length;i++)if(a[i] is DBNull)a[i]=null;rows.Add(a);}return rows; }
    private static List<object?[]> BooleanColumn(List<object?[]> rows,int column){foreach(var row in rows)row[column]=Convert.ToInt64(row[column],CultureInfo.InvariantCulture)!=0;return rows;}
    private static IReadOnlyList<object?[]> WithHeader(object?[] h,List<object?[]> rows)=>new[]{h}.Concat(rows).ToArray();
    private static List<object?[]> Rows(Dictionary<string,List<object?[]>> wb,string name)=>wb.TryGetValue(name,out var rows)&&rows.Count>0?rows.Skip(1).ToList():[];
    private static Dictionary<string,object?>[] Table(Dictionary<string,List<object?[]>> wb,string name){if(!wb.TryGetValue(name,out var rows)||rows.Count==0)return[];var h=rows[0].Select(Text).ToArray();return rows.Skip(1).Select(r=>h.Select((x,i)=>(x,r.ElementAtOrDefault(i))).ToDictionary(x=>x.x,x=>x.Item2)).ToArray();}
    private static void RequireHeader(Dictionary<string,List<object?[]>> wb,string name,string[] expected){if(!wb.TryGetValue(name,out var rows)||rows.Count==0||!rows[0].Select(Text).SequenceEqual(expected,StringComparer.Ordinal) && !(name is "components" or "stock_movements" && rows[0].Select(Text).SequenceEqual(expected.Take(name=="components"?13:11),StringComparer.Ordinal)))throw new InvalidDataException($"工作表 {name} 的表头或顺序不符合 schemaVersion=1。");}
    private static void Duplicate(List<object?[]> rows,int col,string label,List<string> issues)=>Duplicate(rows,r=>Text(r[col]),label,issues);
    private static void Duplicate(List<object?[]> rows,Func<object?[],string> key,string label,List<string> issues){if(rows.GroupBy(key,StringComparer.OrdinalIgnoreCase).Any(g=>string.IsNullOrWhiteSpace(g.Key)||g.Count()>1))issues.Add($"{label} 为空或重复。");}
    private static void TryNonNegative(object? v,string label,List<string> issues){try{if(Int(v)<0)issues.Add(label+"不能为负数。");}catch{issues.Add(label+"必须是无小数的 32 位整数。");}}
    private static void Timestamp(object? v,string label,List<string> issues){var text=Text(v);if(!text.EndsWith('Z')||!DateTimeOffset.TryParse(text,CultureInfo.InvariantCulture,DateTimeStyles.AssumeUniversal|DateTimeStyles.AdjustToUniversal,out _))issues.Add(label+" 必须是 UTC ISO-8601 格式。");}
    private static int Int(object? v)=>v switch { bool _=>throw new FormatException(),int i=>i,long l when l is>=int.MinValue and<=int.MaxValue=>(int)l,decimal d when d==decimal.Truncate(d)&&d is>=int.MinValue and<=int.MaxValue=>(int)d,double d when d==Math.Truncate(d)&&d is>=int.MinValue and<=int.MaxValue=>(int)d,_=>decimal.TryParse(Text(v),NumberStyles.Float,CultureInfo.InvariantCulture,out var d)&&d==decimal.Truncate(d)&&d is>=int.MinValue and<=int.MaxValue?(int)d:throw new FormatException()};
    private static bool Bool(object? v)=>v switch { bool b=>b, byte n=>n!=0,short n=>n!=0,int n=>n!=0,long n=>n!=0,double n=>n!=0, _=>bool.Parse(Text(v)) }; private static string Text(object? v)=>Convert.ToString(v,CultureInfo.InvariantCulture)?.Trim()??"";
    private static string Epoch(object? v)=>DateTimeOffset.FromUnixTimeMilliseconds(long.Parse(Text(v),CultureInfo.InvariantCulture)).ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'",CultureInfo.InvariantCulture);
    private sealed record BackupRows(List<object?[]> Components,List<object?[]> Locations,List<object?[]> Allocations,List<object?[]> Movements);
    private sealed record ParsedBackup(string Format,List<object?[]> Components,List<object?[]> Locations,List<object?[]> Allocations,List<object?[]> Movements,bool IsForeign);
    private (HashSet<string> Ids,HashSet<string> Skus,string Version) CurrentState(){using var c=new SqliteConnection($"Data Source={_store.DatabasePath}");c.Open();using var transaction=c.BeginTransaction();var ids=ScalarSet(c,"SELECT id FROM components");var skus=ScalarSet(c,"SELECT sku FROM components WHERE deleted=0");using var q=c.CreateCommand();q.CommandText="SELECT (SELECT COUNT(*)||':'||COALESCE(MAX(updated_at),'') FROM components)||'|'||(SELECT COUNT(*)||':'||COALESCE(MAX(updated_at),'') FROM storage_locations)||'|'||(SELECT COUNT(*)||':'||COALESCE(MAX(updated_at),'') FROM stock_movements)";var version=Convert.ToString(q.ExecuteScalar(),CultureInfo.InvariantCulture)??"";transaction.Commit();return(ids,skus,version);}
    private static string FileFingerprint(string path)=>Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(File.ReadAllBytes(path))).ToLowerInvariant();
}

internal sealed record WorkbookImage(string SheetName, int Row, int Column, byte[] Bytes);

internal static partial class SimpleXlsx
{
    private static readonly XNamespace N="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public static void Write(string path,IReadOnlyDictionary<string,IReadOnlyList<object?[]>> sheets,IReadOnlyList<WorkbookImage>? images=null)
    {
        images??=[];if(images.Sum(image=>(long)image.Bytes.Length)>25L*1024*1024)throw new InvalidDataException("工作簿图片总量超过 25 MiB 限制。");
        var validationRoot=Path.Combine(Path.GetTempPath(),"component-vault-export-images-"+Guid.NewGuid().ToString("N"));
        try{foreach(var image in images)PrivateProductImageStore.Persist(validationRoot,image.Bytes);}finally{if(Directory.Exists(validationRoot))Directory.Delete(validationRoot,true);}
        var directory=Path.GetDirectoryName(Path.GetFullPath(path))!;Directory.CreateDirectory(directory);
        var temporary=Path.Combine(directory,$".{Path.GetFileName(path)}.{Guid.NewGuid():N}.tmp");
        try { WriteCore(temporary,sheets); if(images is {Count:>0})AppendImages(temporary,sheets.Keys.ToArray(),images); _=Read(temporary); File.Move(temporary,path,true); }
        finally { if(File.Exists(temporary))File.Delete(temporary); }
    }
    private static void WriteCore(string path,IReadOnlyDictionary<string,IReadOnlyList<object?[]>> sheets)
    {
        using var stream=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.None); using var zip=new ZipArchive(stream,ZipArchiveMode.Create); Entry(zip,"[Content_Types].xml",new XDocument(new XElement(XNamespace.Get("http://schemas.openxmlformats.org/package/2006/content-types")+"Types",new XElement(XNamespace.Get("http://schemas.openxmlformats.org/package/2006/content-types")+"Default",new XAttribute("Extension","rels"),new XAttribute("ContentType","application/vnd.openxmlformats-package.relationships+xml")),new XElement(XNamespace.Get("http://schemas.openxmlformats.org/package/2006/content-types")+"Default",new XAttribute("Extension","xml"),new XAttribute("ContentType","application/xml")),new XElement(XNamespace.Get("http://schemas.openxmlformats.org/package/2006/content-types")+"Override",new XAttribute("PartName","/xl/workbook.xml"),new XAttribute("ContentType","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")),sheets.Keys.Select((_,i)=>new XElement(XNamespace.Get("http://schemas.openxmlformats.org/package/2006/content-types")+"Override",new XAttribute("PartName",$"/xl/worksheets/sheet{i+1}.xml"),new XAttribute("ContentType","application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"))))));
        Entry(zip,"_rels/.rels",Rels([("rId1","xl/workbook.xml","http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument")]));
        XNamespace r="http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        var sheetNodes = sheets.Keys.Select((name,i) => new XElement(N+"sheet",
            new XAttribute("name",name), new XAttribute("sheetId",i+1), new XAttribute(r+"id",$"rId{i+1}")));
        Entry(zip,"xl/workbook.xml",new XDocument(new XElement(N+"workbook",
            new XAttribute(XNamespace.Xmlns+"r",r), new XElement(N+"sheets",sheetNodes))));
        Entry(zip,"xl/_rels/workbook.xml.rels",Rels(sheets.Keys.Select((_,i)=>($"rId{i+1}",$"worksheets/sheet{i+1}.xml","http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"))));
        int s=0;foreach(var rows in sheets.Values)Entry(zip,$"xl/worksheets/sheet{++s}.xml",new XDocument(new XElement(N+"worksheet",new XElement(N+"sheetData",rows.Select((row,ri)=>new XElement(N+"row",new XAttribute("r",ri+1),row.Select((v,ci)=>Cell(v,ci,ri))))))));
    }
    public static Dictionary<string,List<object?[]>> Read(string path){if(new FileInfo(path).Length>50*1024*1024)throw new InvalidDataException("XLSX 超过 50 MiB 限制。");using var z=ZipFile.OpenRead(path);ValidateArchive(z);var w=Load(z,"xl/workbook.xml");var rel=Load(z,"xl/_rels/workbook.xml.rels");XNamespace p="http://schemas.openxmlformats.org/package/2006/relationships";XNamespace r="http://schemas.openxmlformats.org/officeDocument/2006/relationships";var map=rel.Descendants(p+"Relationship").ToDictionary(x=>(string)x.Attribute("Id")!,x=>(string)x.Attribute("Target")!);var shared=Shared(z);return w.Descendants(N+"sheet").ToDictionary(x=>(string)x.Attribute("name")!,x=>Sheet(z,"xl/"+map[(string)x.Attribute(r+"id")!],shared));}
    private static List<object?[]> Sheet(ZipArchive z,string path,List<string> shared){var d=Load(z,path);var rows=new List<object?[]>();foreach(var row in d.Descendants(N+"row")){var cells=row.Elements(N+"c").ToList();var width=cells.Count==0?0:cells.Max(c=>Col((string?)c.Attribute("r")??"A"))+1;if(width>256)throw new InvalidDataException("工作表列索引超出支持范围。");var a=new object?[width];foreach(var c in cells){if(c.Element(N+"f") is not null)throw new InvalidDataException("备份工作簿不能包含公式单元格。");var i=Col((string)c.Attribute("r")!);var t=(string?)c.Attribute("t");var raw=(string?)c.Element(N+"v")??string.Concat(c.Descendants(N+"t").Select(x=>x.Value));a[i]=t switch{"b"=>raw=="1","n"=>decimal.Parse(raw,NumberStyles.Float,CultureInfo.InvariantCulture),"s"=>shared[int.Parse(raw,CultureInfo.InvariantCulture)],null when c.Element(N+"v") is not null=>decimal.Parse(raw,NumberStyles.Float,CultureInfo.InvariantCulture),_=>raw};}rows.Add(a);if(rows.Count>MaxRows)throw new InvalidDataException("工作表行数超限。");}return rows;}
    // The portable contract permits 100,000 data rows in addition to the header row.
    private const int MaxRows=100001;private static XElement Cell(object? v,int c,int r){var x=new XElement(N+"c",new XAttribute("r",Name(c)+(r+1)));if(v is null)return x;if(v is bool b){x.SetAttributeValue("t","b");x.Add(new XElement(N+"v",b?"1":"0"));}else if(v is sbyte or byte or short or ushort or int or uint or long or ulong or float or double or decimal){x.SetAttributeValue("t","n");x.Add(new XElement(N+"v",Convert.ToString(v,CultureInfo.InvariantCulture)));}else{x.SetAttributeValue("t","inlineStr");x.Add(new XElement(N+"is",new XElement(N+"t",Convert.ToString(v,CultureInfo.InvariantCulture))));}return x;}
    private static XDocument Rels(IEnumerable<(string id,string target,string type)> rs){XNamespace p="http://schemas.openxmlformats.org/package/2006/relationships";return new XDocument(new XElement(p+"Relationships",rs.Select(x=>new XElement(p+"Relationship",new XAttribute("Id",x.id),new XAttribute("Target",x.target),new XAttribute("Type",x.type)))));}
    private static void Entry(ZipArchive z,string p,XDocument d){var e=z.CreateEntry(p);using var s=e.Open();d.Save(s);}
    private static XDocument Load(ZipArchive z,string p){var e=z.GetEntry(p.Replace("xl/xl/","xl/"))??throw new InvalidDataException("XLSX 缺少 "+p);if(e.Length>20*1024*1024)throw new InvalidDataException("XLSX XML 条目超过限制。");using var s=e.Open();var settings=new System.Xml.XmlReaderSettings{DtdProcessing=System.Xml.DtdProcessing.Prohibit,XmlResolver=null,MaxCharactersInDocument=20*1024*1024};using var reader=System.Xml.XmlReader.Create(s,settings);return XDocument.Load(reader);}
    private static List<string> Shared(ZipArchive z){return z.GetEntry("xl/sharedStrings.xml") is null?[]:Load(z,"xl/sharedStrings.xml").Descendants(N+"si").Select(x=>string.Concat(x.Descendants(N+"t").Select(t=>t.Value))).ToList();}
    private static void ValidateArchive(ZipArchive z){if(z.Entries.Count>10000)throw new InvalidDataException("XLSX ZIP 条目数超过 10000。");long total=0;var buffer=new byte[81920];foreach(var e in z.Entries){using var input=e.Open();int read;while((read=input.Read(buffer))>0){total=checked(total+read);if(total>100L*1024*1024)throw new InvalidDataException("XLSX ZIP 总解压量超过 100 MiB。");}}}
    private static int Col(string r){var n=0;var letters=r.TakeWhile(char.IsLetter).ToArray();if(letters.Length is 0 or >3)throw new InvalidDataException("单元格列引用无效。");foreach(var c in letters)n=checked(n*26+char.ToUpperInvariant(c)-'A'+1);return n-1;}private static string Name(int i){var s="";for(i++;i>0;i=(i-1)/26)s=(char)('A'+(i-1)%26)+s;return s;}
}
