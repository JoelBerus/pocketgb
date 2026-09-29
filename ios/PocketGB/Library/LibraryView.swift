import SwiftUI

/// Tab Biblioteca. En D1 solo muestra los estados sin carpeta y carpeta vacía; la
/// carpeta real (bookmark, escaneo, iCloud) llega en D2 y el grid/lista en D3.
struct LibraryView: View {
    @Environment(AppState.self) private var state

    var body: some View {
        NavigationStack {
            ScrollView {
                content
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, PocketSpacing.md)
                    .padding(.top, PocketSpacing.xxl)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background(PocketColor.backgroundBase.ignoresSafeArea())
            .navigationTitle("Biblioteca")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Abrir un archivo…", systemImage: "doc") {
                            state.pickingROM = true
                        }
                    } label: {
                        Label("Más opciones", systemImage: "ellipsis")
                    }
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch state.library {
        case .noFolder:
            EmptyStateView(
                title: "Elige tu carpeta de juegos",
                systemImage: "folder.badge.plus",
                message: "PocketGB lee los juegos .gb y .gbc de una carpeta de iCloud Drive o de Archivos. No copia ni modifica tus ROMs.",
                primaryTitle: "Elegir carpeta",
                primaryAction: { state.chooseFolder() })
        case .empty(let folderName):
            EmptyStateView(
                title: "No hay juegos en esta carpeta",
                systemImage: "folder",
                message: "“\(folderName)” no tiene archivos .gb ni .gbc. Añádelos desde Archivos y vuelve a escanear, o elige otra carpeta.",
                primaryTitle: "Volver a escanear",
                primaryAction: { state.chooseFolder() },
                secondaryTitle: "Cambiar carpeta",
                secondaryAction: { state.chooseFolder() })
        }
    }
}
