import SwiftUI

/// Tab Biblioteca (D2): la carpeta elegida, sus juegos y sus estados (iCloud, errores,
/// escaneo). La presentación en grid/lista con portadas llega en D3.
struct LibraryView: View {
    @Environment(AppState.self) private var state
    @State private var problemEntry: RomEntry?

    private var library: LibraryStore { state.library }

    var body: some View {
        NavigationStack {
            content
                .background(PocketColor.backgroundBase.ignoresSafeArea())
                .navigationTitle("Biblioteca")
                .toolbar {
                    if case .ready = library.phase {
                        ToolbarItem(placement: .topBarTrailing) {
                            Menu {
                                Button("Volver a escanear", systemImage: "arrow.clockwise") { library.refresh() }
                                Button("Cambiar carpeta", systemImage: "folder") { state.chooseFolder() }
                            } label: {
                                Label("Más opciones", systemImage: "ellipsis")
                            }
                        }
                    }
                }
                .overlay(alignment: .bottom) {
                    if let summary = library.summary {
                        ToastView(text: summary, systemImage: "sparkles")
                            .padding(.bottom, PocketSpacing.md)
                            .transition(.opacity)
                    }
                }
                .alert(problemEntry.map { "No se puede abrir “\($0.fileName)”" } ?? "",
                       isPresented: Binding(get: { problemEntry != nil }, set: { if !$0 { problemEntry = nil } })) {
                    Button("OK", role: .cancel) {}
                } message: {
                    if let entry = problemEntry {
                        Text(problemText(entry))
                    }
                }
        }
    }

    @ViewBuilder private var content: some View {
        switch library.phase {
        case .noFolder:
            centered {
                EmptyStateView(
                    title: "Elige tu carpeta de juegos",
                    systemImage: "folder.badge.plus",
                    message: "PocketGB lee los juegos .gb y .gbc de una carpeta de iCloud Drive o de Archivos. No copia ni modifica tus ROMs.",
                    primaryTitle: "Elegir carpeta",
                    primaryAction: { state.chooseFolder() })
            }
        case .unavailable(let folderName):
            centered {
                EmptyStateView(
                    title: "No se puede abrir la carpeta",
                    systemImage: "folder.badge.questionmark",
                    message: "PocketGB ya no tiene acceso a \(folderName.map { "“\($0)”" } ?? "la carpeta de juegos"). Puede que se haya movido o renombrado. Tus partidas siguen guardadas en este iPhone.",
                    primaryTitle: "Elegir de nuevo",
                    primaryAction: { state.chooseFolder() })
            }
        case .ready(let folderName):
            if library.entries.isEmpty && !library.isScanning {
                centered {
                    EmptyStateView(
                        title: "No hay juegos en esta carpeta",
                        systemImage: "folder",
                        message: "“\(folderName)” no tiene archivos .gb ni .gbc. Añádelos desde Archivos y vuelve a escanear, o elige otra carpeta.",
                        primaryTitle: "Volver a escanear",
                        primaryAction: { library.refresh() },
                        secondaryTitle: "Cambiar carpeta",
                        secondaryAction: { state.chooseFolder() })
                }
            } else {
                gameList(folderName: folderName)
            }
        }
    }

    private func centered(@ViewBuilder _ inner: () -> some View) -> some View {
        ScrollView {
            inner()
                .frame(maxWidth: .infinity)
                .padding(.horizontal, PocketSpacing.md)
                .padding(.top, PocketSpacing.xxl)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    private func gameList(folderName: String) -> some View {
        List {
            Section {
                if let progress = library.scanProgress {
                    ScanProgressRow(progress: progress)
                }
                ForEach(library.entries) { entry in
                    Button {
                        tap(entry)
                    } label: {
                        RomRow(entry: entry)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Label(folderName, systemImage: "folder")
            } footer: {
                if library.entries.contains(where: { $0.cloud == .downloading }) {
                    Text("Descargando de iCloud. Podrás jugar cuando termine la descarga.")
                } else if library.entries.contains(where: { $0.cloud == .notDownloaded }) {
                    Text("Los juegos con una nube solo están en iCloud: tócalos para descargarlos.")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .refreshable { library.refresh() }
    }

    private func tap(_ entry: RomEntry) {
        if entry.problem != nil {
            problemEntry = entry
        } else if entry.cloud == .notDownloaded {
            library.download(entry)
        } else if entry.cloud == .current {
            state.open(entry: entry)
        }
    }

    private func problemText(_ entry: RomEntry) -> String {
        let place = entry.subfolder.isEmpty ? "en la carpeta de juegos" : "en la subcarpeta “\(entry.subfolder)”"
        return "\(entry.problem?.message ?? "") Está \(place). PocketGB no lo abrirá ni lo modificará."
    }
}

/// Fila de un juego: título, archivo o motivo del error, sistema y estado.
private struct RomRow: View {
    let entry: RomEntry

    var body: some View {
        HStack(spacing: PocketSpacing.sm) {
            ConsoleChip(isColor: entry.isColor)
            VStack(alignment: .leading, spacing: PocketSpacing.xxs) {
                HStack(spacing: PocketSpacing.xs) {
                    Text(entry.title)
                        .font(.body)
                        .foregroundStyle(entry.problem == nil ? .primary : .secondary)
                        .lineLimit(2)
                    if entry.isNew {
                        Text("Nuevo")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(PocketColor.accent)
                    }
                }
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer(minLength: PocketSpacing.xs)
            trailing
        }
        .frame(minHeight: PocketSpacing.minTouch)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(accessibilityHint)
    }

    private var subtitle: String {
        if let problem = entry.problem { return problem.message }
        switch entry.cloud {
        case .notDownloaded: return "En iCloud · \(entry.fileName)"
        case .downloading: return "Descargando… · \(entry.fileName)"
        case .current:
            if let date = entry.mirrorSaveDate {
                return "Partida del \(date.formatted(date: .abbreviated, time: .shortened))"
            }
            return entry.fileName
        }
    }

    @ViewBuilder private var trailing: some View {
        if entry.problem != nil {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(PocketColor.danger)
                .accessibilityLabel("Error")
        } else {
            switch entry.cloud {
            case .notDownloaded:
                Image(systemName: "icloud.and.arrow.down")
                    .foregroundStyle(PocketColor.accent)
                    .accessibilityLabel("Solo en iCloud")
            case .downloading:
                ProgressView()
                    .accessibilityLabel("Descargando")
            case .current:
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
        }
    }

    private var accessibilityHint: String {
        if entry.problem != nil { return "Muestra por qué no se puede abrir" }
        switch entry.cloud {
        case .notDownloaded: return "Descarga el juego de iCloud"
        case .downloading: return "Espera a que termine la descarga"
        case .current: return "Abre el juego"
        }
    }
}

/// Progreso discreto del escaneo: la biblioteca sigue utilizable (SPEC §9, `library-scan-progress`).
private struct ScanProgressRow: View {
    let progress: LibraryStore.ScanProgress

    var body: some View {
        HStack(spacing: PocketSpacing.sm) {
            ProgressView()
            Text(progress.total > 0 ? "Buscando juegos… \(progress.done) de \(progress.total)" : "Buscando juegos…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Chip GB/GBC (SPEC §8, `ConsoleChip`): texto, nunca solo color.
struct ConsoleChip: View {
    let isColor: Bool

    var body: some View {
        Text(isColor ? "GBC" : "GB")
            .font(.caption2.monospaced().weight(.semibold))
            .frame(minWidth: 34)
            .padding(.vertical, PocketSpacing.xxs)
            .overlay(Capsule().strokeBorder(.secondary.opacity(0.6), lineWidth: 1))
            .accessibilityLabel(isColor ? "Game Boy Color" : "Game Boy")
    }
}
