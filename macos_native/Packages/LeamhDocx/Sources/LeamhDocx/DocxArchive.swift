import Foundation
import ZIPFoundation

/// In-memory view of a DOCX (a ZIP archive). Reads every entry into an ordered
/// name → data collection, preserving insertion order so a full-rewrite keeps
/// entries in their original order (zip/docx central directory order matters
/// for reproducibility). Mirrors DocxArchive.kt.
///
/// Lookups (`data(named:)`, `contains`) are O(1) via a name → data dictionary;
/// a separate `order` array preserves original entry order for `names` and for
/// `toMutableEntries()`'s output ordering. Previously this was a linear
/// `[(name, data)]` array scanned with `first(where:)`, which grew O(n) per
/// lookup and O(annotations × entries) overall with ink PNG presence checks.
public struct DocxArchive {
    private var order: [String]
    private var storage: [String: Data]
    /// Original compression method per entry, as read from the source ZIP.
    /// Used by `write` to avoid re-deflating entries that don't need it.
    private var methods: [String: CompressionMethod]

    private init(order: [String], storage: [String: Data], methods: [String: CompressionMethod]) {
        self.order = order
        self.storage = storage
        self.methods = methods
    }

    public func data(named name: String) -> Data? {
        storage[name]
    }

    public func text(named name: String) -> String? {
        guard let d = data(named: name) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    public func contains(_ name: String) -> Bool {
        storage[name] != nil
    }

    public var names: [String] { order }

    /// Original compression method for `name`, if this archive was read from a source ZIP.
    func method(for name: String) -> CompressionMethod? {
        methods[name]
    }

    /// A mutable copy for a full rewrite.
    public func toMutableEntries() -> MutableDocxEntries {
        MutableDocxEntries(order.map { (name: $0, data: storage[$0]!) })
    }

    public static func read(_ docxData: Data) throws -> DocxArchive {
        guard let archive = Archive(data: docxData, accessMode: .read) else {
            throw DocxError.invalidArchive
        }
        var order: [String] = []
        var storage: [String: Data] = [:]
        var methods: [String: CompressionMethod] = [:]
        for entry in archive where entry.type == .file {
            var entryData = Data()
            _ = try archive.extract(entry) { chunk in entryData.append(chunk) }
            order.append(entry.path)
            storage[entry.path] = entryData
            // ZIPFoundation's `Entry` only exposes `isCompressed` publicly (not the raw
            // compression-method code), so derive the two methods this engine cares about
            // (STORED vs DEFLATED) from that.
            methods[entry.path] = entry.isCompressed ? .deflate : .none
        }
        return DocxArchive(order: order, storage: storage, methods: methods)
    }

    /// Writes `mutable`'s entries to a ZIP. If `source` is given (the archive the entries
    /// were read from), an entry whose bytes are unchanged from `source` keeps its original
    /// compression method instead of always re-deflating — at minimum this means an
    /// already-STORED entry (already-compressed media such as ink PNGs) is written STORED
    /// again rather than paying deflate cost for content that won't shrink further.
    ///
    /// DEVIATION from a full symmetric raw-copy (as implemented in Android's DocxArchive.kt):
    /// ZIPFoundation's public `addEntry` API always drives entry bytes through its own
    /// compressor/checksum machinery — it has no API to inject pre-compressed bytes plus a
    /// known CRC for a DEFLATED entry. So unchanged DEFLATED entries still get re-deflated
    /// here (same output bytes, but not free of the CPU cost); only unchanged/originally-STORED
    /// entries skip compression work. Going further would require a hand-rolled ZIP writer
    /// bypassing ZIPFoundation, which conflicts with this package's use of ZIPFoundation as
    /// the vendored engine (see project CLAUDE.md).
    public static func write(_ mutable: MutableDocxEntries, source: DocxArchive? = nil) throws -> Data {
        guard let archive = Archive(accessMode: .create) else {
            throw DocxError.writeFailure
        }
        for (name, data) in mutable.entries {
            let count = data.count
            // Preserve the original compression method whenever it's known (both for
            // unchanged entries, and for changed-but-originally-STORED media like an edited
            // ink PNG — re-deflating incompressible content wastes CPU for no size win).
            // Default (.none / STORED) matches this engine's prior behavior for entries with
            // no known source method (new entries, or archives built from scratch).
            let method: CompressionMethod = source?.method(for: name) ?? .none
            try archive.addEntry(
                with: name,
                type: .file,
                uncompressedSize: Int64(count),
                compressionMethod: method,
                provider: { position, size in
                    let pos = Int(position)
                    return data.subdata(in: pos..<(pos + size))
                }
            )
        }
        guard let result = archive.data else { throw DocxError.writeFailure }
        return result
    }
}

/// Mutable, order-preserving entry collection for a full DOCX rewrite.
public struct MutableDocxEntries {
    var entries: [(name: String, data: Data)]
    private var index: [String: Int]

    init(_ entries: [(name: String, data: Data)]) {
        self.entries = entries
        self.index = Dictionary(uniqueKeysWithValues: entries.enumerated().map { ($1.name, $0) })
    }

    public subscript(name: String) -> Data? {
        get {
            guard let i = index[name] else { return nil }
            return entries[i].data
        }
        set {
            if let i = index[name] {
                if let v = newValue {
                    entries[i] = (name, v)
                } else {
                    entries.remove(at: i)
                    index.removeValue(forKey: name)
                    // Reindex entries after the removed one — their positions shifted.
                    for j in i..<entries.count { index[entries[j].name] = j }
                }
            } else if let v = newValue {
                index[name] = entries.count
                entries.append((name, v))
            }
        }
    }

    public func contains(_ name: String) -> Bool {
        index[name] != nil
    }

    public mutating func removeAll(where predicate: (String) -> Bool) {
        entries.removeAll(where: { predicate($0.name) })
        index = Dictionary(uniqueKeysWithValues: entries.enumerated().map { ($1.name, $0) })
    }
}

public enum DocxError: Error {
    case invalidArchive
    case writeFailure
}
