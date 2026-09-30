import UIKit

/// Háptica de los controles (SPEC §7.6): una vez al entrar en pulsado, nunca por frame
/// ni mientras el botón sigue pulsado. Respeta el ajuste "Háptica".
@MainActor
final class ControlsHaptics {
    var enabled = true
    private let impact = UIImpactFeedbackGenerator(style: .light)
    private let selection = UISelectionFeedbackGenerator()

    func prepare() {
        guard enabled else { return }
        impact.prepare()
        selection.prepare()
    }

    /// A, B, Start o Select recién pulsados.
    func buttonDown() {
        guard enabled else { return }
        impact.impactOccurred()
    }

    /// Cambio de sector del D-pad (incluido entrar desde la zona muerta).
    func dpadChanged() {
        guard enabled else { return }
        selection.selectionChanged()
    }
}
