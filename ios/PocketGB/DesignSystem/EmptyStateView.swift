import SwiftUI

/// Estado vacío o de error con explicación y una acción clara (SPEC §8, `EmptyStateView`).
/// Se apoya en `ContentUnavailableView` del sistema; la acción principal usa vidrio prominente.
struct EmptyStateView: View {
    let title: String
    let systemImage: String
    let message: String
    var primaryTitle: String?
    var primaryAction: (@MainActor () -> Void)?
    var secondaryTitle: String?
    var secondaryAction: (@MainActor () -> Void)?

    var body: some View {
        ContentUnavailableView {
            Label(title, systemImage: systemImage)
        } description: {
            Text(message)
        } actions: {
            if let primaryTitle, let primaryAction {
                Button(primaryTitle) { primaryAction() }
                    .buttonStyle(.glassProminent)
                    .controlSize(.large)
            }
            if let secondaryTitle, let secondaryAction {
                Button(secondaryTitle) { secondaryAction() }
                    .buttonStyle(.glass)
            }
        }
    }
}
