import Foundation
import Testing
@testable import PocketGB

/// D5: ranuras de save state en disco (D-README §7).
struct StateStoreTests {
    let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    @Test func fourManualSlotsAndOneAuto() {
        #expect(StateSlot.manual.count == 4)
        #expect(StateSlot.allCases.filter { $0 == .auto }.count == 1)
        #expect(Set(StateSlot.allCases.map(\.fileStem)).count == 5)
    }

    @Test func saveListLoadAndDelete() throws {
        let store = StateStore(root: dir, fingerprint: "abc")
        let state = Data("PGBS".utf8) + Data(repeating: 7, count: 100)
        try store.save(state, thumbnail: Data([0x89, 0x50]), to: .manual2)
        let entries = store.entries()
        #expect(entries.keys.sorted { $0.fileStem < $1.fileStem } == [.manual2])
        #expect(entries[.manual2]?.corrupt == false)
        #expect(entries[.manual2]?.thumbnail == Data([0x89, 0x50]))
        #expect(try store.load(.manual2) == state)
        // Sin temporales sueltos: la escritura es tmp + rename.
        let files = try FileManager.default.contentsOfDirectory(atPath: store.directory.path)
        #expect(!files.contains { $0.hasSuffix(".tmp") })
        try store.delete(.manual2)
        #expect(store.entries().isEmpty)
    }

    @Test func replacingASlotKeepsOnlyTheNewState() throws {
        let store = StateStore(root: dir, fingerprint: "abc")
        try store.save(Data("PGBS-1".utf8), thumbnail: nil, to: .auto)
        try store.save(Data("PGBS-2".utf8), thumbnail: nil, to: .auto)
        #expect(try store.load(.auto) == Data("PGBS-2".utf8))
    }

    @Test func foreignFileIsListedAsCorrupt() throws {
        let store = StateStore(root: dir, fingerprint: "abc")
        try store.save(Data("basura".utf8), thumbnail: nil, to: .manual4)
        #expect(store.entries()[.manual4]?.corrupt == true)
    }
}
