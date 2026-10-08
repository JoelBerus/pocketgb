import SwiftUI
import UniformTypeIdentifiers

/// N7b · la partida del juego en el detalle: exportar el `.sav` crudo o el paquete `.pgbm`, e importar uno u otro.
/// Importar siempre respalda lo actual y se rechaza con el juego abierto (exclusión por huella).
struct GameSaveTravelSection: View {
    @Environment(AppState.self) private var state
    let entry: RomEntry
    let fingerprint: String
    @State private var importing = false
    @State private var exportDocument: SaveFileDocument?
    @State private var exportName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: PocketSpacing.xs) {
            Label("Partida", systemImage: "externaldrive")
                .font(.headline)
            GlassEffectContainer(spacing: PocketSpacing.xs) {
                VStack(spacing: PocketSpacing.xs) {
                    ShareLink(item: ExportedSaveFile(name: SaveExport.fileName(title, ext: "sav"), contentType: .gameBoySave) {
                        try await MainActor.run { try state.exportRawSave(entry) }
                    }, preview: SharePreview("\(title).sav")) {
                        row("Exportar .sav", "square.and.arrow.up")
                    }
                    .accessibilityHint("Comparte la partida tal cual; sirve en otros emuladores")
                    .accessibilityIdentifier("game-save-export-sav")
                    Button {
                        Task { await savePackageToFiles() }
                    } label: {
                        row("Guardar paquete en Archivos…", "folder")
                    }
                    .accessibilityIdentifier("game-save-export-pgbm")
                    Button {
                        importing = true
                    } label: {
                        row("Importar partida…", "square.and.arrow.down")
                    }
                    .accessibilityHint("Un .pgbm o un .sav. La partida actual se guarda antes como copia")
                    .accessibilityIdentifier("game-save-import")
                }
                .font(.subheadline)
                .pocketGlassButton()
            }
            .disabled(state.travelBusy)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pocketGBPackage, .gameBoySave, .data]) { result in
            switch result {
            case .success(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size <= SaveImport.maxFileBytes else { throw SaveImport.Rejection.tooLarge }
                    state.importSave(try Data(contentsOf: url), into: entry)
                } catch {
                    state.notify("No se pudo importar", error.localizedDescription)
                }
            case .failure(let error):
                state.notify("No se pudo importar", error.localizedDescription)
            }
        }
        .fileExporter(isPresented: Binding(get: { exportDocument != nil }, set: { if !$0 { exportDocument = nil } }),
                      document: exportDocument, contentType: .pocketGBPackage, defaultFilename: exportName) { result in
            if case .failure(let error) = result { state.notify("No se pudo guardar", error.localizedDescription) }
        }
    }

    private var title: String { state.libraryPrefs.displayTitle(entry) }

    private func row(_ text: String, _ icon: String) -> some View {
        Label(text, systemImage: icon)
            .frame(maxWidth: .infinity, minHeight: PocketSpacing.minTouch)
    }

    private func savePackageToFiles() async {
        do {
            let data = try await state.exportPackage(entry)
            exportName = SaveExport.fileName(title, ext: "pgbm")
            exportDocument = SaveFileDocument(data: data)
        } catch {
            state.notify("No se pudo exportar", error.localizedDescription)
        }
    }
}
