import CoreTransferable
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// Una imagen elegida en Fotos, como archivo temporal (nunca se carga entera en memoria antes de mirar su
/// tamaño). `PhotosPicker` no pide permiso de acceso a la fototeca.
struct PickedImageFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .image) { received in
            let copy = FileManager.default.temporaryDirectory
                .appendingPathComponent("cover-\(UUID().uuidString)")
                .appendingPathExtension(received.file.pathExtension)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedImageFile(url: copy)
        }
    }
}

/// N5 · Centro de ajustes › Portada: qué se elige, qué se ve, importar (Fotos o Archivos, sin permisos ni
/// red), quitar la importada y soltar la captura fijada. Solo con la huella confirmada (la portada va por ella).
struct CoverCenterView: View {
    @Environment(AppState.self) private var state
    let entryID: String
    let fingerprint: String
    @State private var photo: PhotosPickerItem?
    @State private var importingFile = false
    @State private var importing = false
    @State private var importFailed = false

    private var entry: RomEntry? { state.library.entries.first { $0.id == entryID } }
    private var covers: CoverStore { state.covers }

    var body: some View {
        Form {
            if importFailed {
                Section {
                    Label("No se pudo usar esa imagen. Elige un PNG, JPG, WebP o HEIC de menos de 15 MB.",
                          systemImage: "exclamationmark.triangle")
                        .foregroundStyle(PocketColor.danger)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("cover-import-failed")
                }
            }
            if let entry {
                previewSection(entry)
                choiceSection(entry)
                imageSection(entry)
                captureSection()
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Portada")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importingFile, allowedContentTypes: [.png, .jpeg, .webP, .heic]) { result in
            guard case .success(let url) = result else { return }
            Task { await importFile(url, securityScoped: true) }
        }
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                let file = try? await item.loadTransferable(type: PickedImageFile.self)
                guard let file else {
                    importFailed = true
                    return
                }
                await importFile(file.url, securityScoped: false)
                try? FileManager.default.removeItem(at: file.url)
            }
        }
        .onAppear {
            #if DEBUG
            if DebugArguments.arguments.contains("-coverImportFailed") { importFailed = true }
            #endif
        }
        .accessibilityIdentifier("cover-center")
    }

    private func importFile(_ url: URL, securityScoped: Bool) async {
        importing = true
        defer { importing = false }
        let scoped = securityScoped && url.startAccessingSecurityScopedResource()
        let data = await Task.detached(priority: .userInitiated) { CoverDecoder.readLimited(url) }.value
        if scoped { url.stopAccessingSecurityScopedResource() }
        guard let data else {
            importFailed = true
            return
        }
        importFailed = !(await covers.importImage(data, fingerprint: fingerprint))
    }

    // MARK: Secciones

    private func previewSection(_ entry: RomEntry) -> some View {
        let shown = covers.shown(entry, fingerprint: fingerprint)
        return Section {
            HStack {
                Spacer(minLength: 0)
                GameArtworkView(entry: entry, cornerRadius: PocketRadius.thumbnail, style: .console)
                    .frame(width: 180)
                    .accessibilityHidden(true)
                Spacer(minLength: 0)
            }
            LabeledContent("Se ve") {
                Text(shown.kind.title)
            }
            .accessibilityIdentifier("cover-shown")
        }
    }

    private func choiceSection(_ entry: RomEntry) -> some View {
        let current = covers.choice(for: fingerprint)
        let available = covers.availability(entry, fingerprint: fingerprint)
        return Section {
            ForEach(CoverChoice.allCases) { choice in
                Button {
                    covers.setChoice(choice, for: fingerprint)
                } label: {
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.title)
                                .foregroundStyle(.primary)
                            Text(summary(choice, available))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: PocketSpacing.sm)
                        if choice == current {
                            Image(systemName: "checkmark")
                                .foregroundStyle(PocketColor.accent)
                                .accessibilityHidden(true)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .tint(.primary)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(choice == current ? .isSelected : [])
                .accessibilityIdentifier("cover-choice-\(choice.rawValue)")
            }
        } header: {
            Text("Elegir")
        } footer: {
            Text("Si eliges Imagen o Captura y este juego no la tiene, se ve la generada.")
        }
    }

    private func summary(_ choice: CoverChoice, _ available: CoverAvailability) -> String {
        let missing = "No hay: se verá la generada"
        switch choice {
        case .auto:
            return "Según Ajustes › Biblioteca (\(covers.settings.preference.title.lowercased()))"
        case .image:
            if available.imported { return CoverKind.imported.title }
            return available.sidecar ? CoverKind.sidecar.title : missing
        case .capture:
            if covers.pinned.has(fingerprint) { return "Captura fijada" }
            return available.capture ? "Última escena jugada" : missing
        case .generated:
            return "Color y dibujo propios del juego"
        }
    }

    private func imageSection(_ entry: RomEntry) -> some View {
        Section {
            PhotosPicker(selection: $photo, matching: .images, preferredItemEncoding: .current) {
                Label("Elegir de Fotos", systemImage: "photo.on.rectangle")
            }
            .disabled(importing)
            .accessibilityIdentifier("cover-pick-photo")
            Button("Elegir archivo", systemImage: "folder") { importingFile = true }
                .disabled(importing)
                .accessibilityIdentifier("cover-pick-file")
            if covers.imported.has(fingerprint) {
                Button("Quitar imagen importada", systemImage: "trash", role: .destructive) {
                    covers.removeImported(entry, fingerprint: fingerprint)
                }
                .accessibilityIdentifier("cover-remove-imported")
            }
            if importing {
                HStack(spacing: PocketSpacing.sm) {
                    ProgressView()
                    Text("Preparando la imagen…").foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text("Imagen")
        } footer: {
            Text("PocketGB guarda una copia reducida en este iPhone; tu imagen no cambia. También puedes poner la imagen junto al ROM con el mismo nombre (por ejemplo, «Juego.png»).")
        }
    }

    @ViewBuilder private func captureSection() -> some View {
        Section {
            if covers.pinned.has(fingerprint) {
                Button("Soltar captura fijada", systemImage: "pin.slash") {
                    covers.unpinCapture(fingerprint: fingerprint)
                }
                .accessibilityIdentifier("cover-unpin")
            } else {
                Text("Sin captura fijada")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Captura")
        } footer: {
            Text("Para elegir una escena, en la pausa del juego toca «Usar como portada». Si no, se usa la última escena al salir.")
        }
    }
}
