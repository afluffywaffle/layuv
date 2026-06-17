import Foundation
import ZIPFoundation

/// In-memory view of a DOCX (a ZIP archive). Reads every entry into an ordered
/// name → data array, preserving insertion order so a full-rewrite keeps entries
/// in their original order. Mirrors DocxArchive.kt.
public struct DocxArchive {
    // Ordered pairs preserve ZIP entry order for round-trip compatibility.
    private var entries: [(name: String, data: Data)]

    private init(_ entries: [(name: String, data: Data)]) {
        self.entries = entries
    }

    public func data(named name: String) -> Data? {
        entries.first(where: { $0.name == name })?.data
    }

    public func text(named name: String) -> String? {
        guard let d = data(named: name) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    public func contains(_ name: String) -> Bool {
        entries.contains(where: { $0.name == name })
    }

    public var names: [String] { entries.map(\.name) }

    /// A mutable copy for a full rewrite.
    public func toMutableEntries() -> MutableDocxEntries {
        MutableDocxEntries(entries)
    }

    public static func read(_ docxData: Data) throws -> DocxArchive {
        guard let archive = Archive(data: docxData, accessMode: .read) else {
            throw DocxError.invalidArchive
        }
        var pairs: [(name: String, data: Data)] = []
        for entry in archive where entry.type == .file {
            var entryData = Data()
            _ = try archive.extract(entry) { chunk in entryData.append(chunk) }
            pairs.append((name: entry.path, data: entryData))
        }
        return DocxArchive(pairs)
    }

    public static func write(_ mutable: MutableDocxEntries) throws -> Data {
        guard let archive = Archive(accessMode: .create) else {
            throw DocxError.writeFailure
        }
        for (name, data) in mutable.entries {
            let count = data.count
            try archive.addEntry(
                with: name,
                type: .file,
                uncompressedSize: Int64(count),
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

    init(_ entries: [(name: String, data: Data)]) {
        self.entries = entries
    }

    public subscript(name: String) -> Data? {
        get { entries.first(where: { $0.name == name })?.data }
        set {
            if let idx = entries.firstIndex(where: { $0.name == name }) {
                if let v = newValue { entries[idx] = (name, v) }
                else { entries.remove(at: idx) }
            } else if let v = newValue {
                entries.append((name, v))
            }
        }
    }

    public func contains(_ name: String) -> Bool {
        entries.contains(where: { $0.name == name })
    }

    public mutating func removeAll(where predicate: (String) -> Bool) {
        entries.removeAll(where: { predicate($0.name) })
    }
}

public enum DocxError: Error {
    case invalidArchive
    case writeFailure
}
