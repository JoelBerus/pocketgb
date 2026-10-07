import Foundation

/// N6 · exclusión por huella (§3.3; equivalente iOS de `FingerprintOwnership` de Android): quién está escribiendo la
/// partida de un juego. La sesión de juego (abierta, en pausa o aparcada en segundo plano) la tiene desde que se crea
/// hasta que su hilo para **y** termina la escritura del espejo; las operaciones del detalle que tocan el `.sav`
/// (recuperar la partida de un momento o de «Antes de cargar») la piden en exclusiva y se rechazan si ya tiene dueño.
/// Así nunca hay dos escritores de la misma partida a la vez.
final class FingerprintOwnership: @unchecked Sendable {
    /// Una posesión: se suelta una sola vez con `release()`.
    final class Lease: @unchecked Sendable {
        let fingerprint: String
        let owner: String
        private weak var registry: FingerprintOwnership?
        private let released = NSLock()
        private var done = false

        fileprivate init(fingerprint: String, owner: String, registry: FingerprintOwnership) {
            self.fingerprint = fingerprint
            self.owner = owner
            self.registry = registry
        }

        func release() {
            released.lock()
            let first = !done
            done = true
            released.unlock()
            if first { registry?.release(self) }
        }
    }

    struct Busy: Error, LocalizedError, Equatable {
        let owner: String
        var errorDescription: String? {
            "El juego está abierto o aún se está guardando. Ciérralo y espera un momento antes de cambiar su partida."
        }
    }

    private let lock = NSLock()
    private var owners: [String: Lease] = [:]

    func tryAcquire(_ fingerprint: String, owner: String) -> Lease? {
        lock.lock(); defer { lock.unlock() }
        guard owners[fingerprint] == nil else { return nil }
        let lease = Lease(fingerprint: fingerprint, owner: owner, registry: self)
        owners[fingerprint] = lease
        return lease
    }

    /// La sesión de juego que se abre toma la huella aunque la tenga la sesión anterior que aún termina de escribir su
    /// espejo (reabrir el mismo juego): las escrituras de las dos sesiones van en serie por la misma ruta de guardado, y
    /// el detalle sigue excluido porque la huella sigue teniendo dueño.
    func takeForSession(_ fingerprint: String) -> Lease {
        lock.lock(); defer { lock.unlock() }
        let lease = Lease(fingerprint: fingerprint, owner: "sesión", registry: self)
        owners[fingerprint] = lease
        return lease
    }

    func isOwned(_ fingerprint: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return owners[fingerprint] != nil
    }

    func owner(of fingerprint: String) -> String? {
        lock.lock(); defer { lock.unlock() }
        return owners[fingerprint]?.owner
    }

    /// Ejecuta `body` con la huella en exclusiva; lanza `Busy` si ya tiene dueño.
    func withExclusive<T>(_ fingerprint: String, owner: String, _ body: () throws -> T) throws -> T {
        guard let lease = tryAcquire(fingerprint, owner: owner) else {
            throw Busy(owner: self.owner(of: fingerprint) ?? "?")
        }
        defer { lease.release() }
        return try body()
    }

    fileprivate func release(_ lease: Lease) {
        lock.lock(); defer { lock.unlock() }
        if owners[lease.fingerprint] === lease { owners[lease.fingerprint] = nil }
    }
}
