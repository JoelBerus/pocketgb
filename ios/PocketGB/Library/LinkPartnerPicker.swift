import SwiftUI

/// Los dos juegos de un cable pedido por el usuario: el primero es el del detalle desde el que
/// se conecta (lado 0, arranca activo).
struct LinkRequest: Identifiable, Equatable {
    let first: RomEntry
    let second: RomEntry

    var id: String { "\(first.id)|\(second.id)" }
}

/// Candidatos para conectar con un juego (función pura, M9 §2.2).
enum LinkPartners {
    /// Fuera el mismo juego, los de Game Boy Advance, los ocultos, los que tienen problema y los que
    /// no están descargados. No se filtra por «con partida»: solo se ordena. Orden: jugados (el más
    /// reciente primero), luego con `.sav` junto al ROM, luego por título.
    static func candidates(for source: RomEntry, in entries: [RomEntry], prefs: LibraryPreferencesData) -> [RomEntry] {
        entries
            .filter { $0.id != source.id && $0.console == .gameBoy && $0.problem == nil
                && $0.cloud == .current && !prefs.isHidden($0) }
            .sorted { a, b in
                let da = prefs.lastPlayedDate(a), db = prefs.lastPlayedDate(b)
                if (da != nil) != (db != nil) { return da != nil }
                if let da, let db, da != db { return da > db }
                if (a.mirrorSaveDate != nil) != (b.mirrorSaveDate != nil) { return a.mirrorSaveDate != nil }
                return prefs.displayTitle(a).localizedStandardCompare(prefs.displayTitle(b)) == .orderedAscending
            }
    }
}

/// Sheet para elegir el segundo juego del cable (SPEC §10.6, `link-partner-picker`). Tocar una
/// fila guarda la petición y cierra la sheet; la apertura se lanza en el `onDismiss` de la sheet
/// para no chocar con otras presentaciones (`AppState.startPendingLink`).
struct LinkPartnerPicker: View {
    @Environment(AppState.self) private var state
    let source: RomEntry

    var body: some View {
        let title = state.libraryPrefs.displayTitle(source)
        let candidates = LinkPartners.candidates(for: source, in: state.library.entries,
                                                 prefs: state.libraryPrefs.data)
        NavigationStack {
            List {
                if candidates.isEmpty {
                    ContentUnavailableView("No hay otros juegos", systemImage: "cable.connector",
                                           description: Text("Hace falta otro juego de Game Boy o Game Boy Color descargado en la biblioteca."))
                        .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(candidates) { entry in
                            Button {
                                state.pendingLinkRequest = LinkRequest(first: source, second: entry)
                                state.linkPartnerSource = nil
                            } label: {
                                GameListItem(entry: entry, zoom: nil)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("link-partner-\(entry.id)")
                        }
                    } footer: {
                        Text("Los dos juegos se conectan con un cable virtual y se alternan con un botón en pantalla. Cada uno guarda su propia partida, como con un cable de verdad.")
                    }
                }
            }
            .navigationTitle("Conectar “\(title)” con…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { state.linkPartnerSource = nil }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// Alerta de continuación (D8.1): algún juego tiene un estado automático válido y el cable no lo
/// carga. Cancelar no toca nada.
struct LinkContinueAlert: ViewModifier {
    @Environment(AppState.self) private var state

    func body(content: Content) -> some View {
        let request = state.linkContinueRequest
        content.alert("¿Conectar sin continuar?",
                      isPresented: Binding(get: { state.linkContinueRequest != nil },
                                           set: { if !$0 { state.linkContinueRequest = nil } })) {
            Button("Conectar igualmente") {
                if let request { state.openLink(request, confirmedContinuation: true) }
            }
            Button("Cancelar", role: .cancel) {}
        } message: {
            if let request {
                Text(Self.message(request, prefs: state.libraryPrefs))
            }
        }
    }

    @MainActor static func message(_ request: LinkRequest, prefs: LibraryPreferences) -> String {
        let titles = [request.first, request.second].map { "“\(prefs.displayTitle($0))”" }
        return "Alguno de los dos juegos (\(titles[0]) y \(titles[1])) tiene un punto de continuación que el cable link no carga: empiezas desde la última partida guardada, que no se modifica. Ese punto se descarta si la partida cambia."
    }
}
