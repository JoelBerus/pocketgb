#if DEBUG
import SwiftUI
import UIKit

struct DebugHUD: View {
    let session: EmulatorSession

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { _ in
            Text(String(format: "U %d  ring %d  emu %.2f ms",
                        session.audioRing.underruns,
                        session.audioRing.availableFrames,
                        session.averageFrameMilliseconds))
                .font(.system(size: 10, design: .monospaced))
                .foregroundStyle(.white)
                .padding(4)
                .background(.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 3))
        }
    }
}

/// Instala el recognizer en la ventana para ver los toques aunque los controles
/// estén encima de Metal, pero solo alterna si el toque cae sobre la imagen.
struct ThreeFingerTapInstaller: UIViewRepresentable {
    let action: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(action: action) }

    func makeUIView(context: Context) -> AnchorView {
        let view = AnchorView()
        view.isUserInteractionEnabled = false
        view.coordinator = context.coordinator
        return view
    }

    func updateUIView(_ view: AnchorView, context: Context) {
        context.coordinator.action = action
        context.coordinator.install(on: view)
    }

    static func dismantleUIView(_ view: AnchorView, coordinator: Coordinator) {
        coordinator.remove()
    }

    final class AnchorView: UIView {
        weak var coordinator: Coordinator?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            coordinator?.install(on: self)
        }
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var action: () -> Void
        private weak var anchor: UIView?
        private var recognizer: UITapGestureRecognizer?

        init(action: @escaping () -> Void) { self.action = action }

        func install(on anchor: UIView) {
            self.anchor = anchor
            guard let window = anchor.window, recognizer?.view !== window else { return }
            remove()
            let recognizer = UITapGestureRecognizer(target: self, action: #selector(tapped(_:)))
            recognizer.numberOfTouchesRequired = 3
            recognizer.cancelsTouchesInView = false
            recognizer.delegate = self
            window.addGestureRecognizer(recognizer)
            self.recognizer = recognizer
        }

        func remove() {
            if let recognizer { recognizer.view?.removeGestureRecognizer(recognizer) }
            recognizer = nil
        }

        @objc private func tapped(_ recognizer: UITapGestureRecognizer) {
            guard recognizer.state == .ended, let anchor, let window = anchor.window else { return }
            let imageFrame = anchor.convert(anchor.bounds, to: window)
            if imageFrame.contains(recognizer.location(in: window)) { action() }
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                               shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
            true
        }
    }
}
#endif
