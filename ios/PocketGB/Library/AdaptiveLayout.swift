import CoreGraphics
import Foundation

/// Disposición del detalle del juego (N3a): se decide por el espacio disponible (el que deja el
/// área segura con las barras), nunca por el modelo de iPhone.
/// - Dos columnas si el ancho supera al alto o mide ≥ 600 pt: la imagen a la izquierda, entera y
///   limitada en altura, y la información con su propio scroll a la derecha.
/// - Una columna en vertical: la imagen ocupa como máximo el 45 % del alto.
/// La imagen conserva siempre la proporción de la consola (10:9 GB/GBC, 3:2 GBA).
struct DetailLayout: Equatable, Sendable {
    enum Kind: Equatable, Sendable { case singleColumn, twoColumns }

    let kind: Kind
    /// Tamaño de la portada.
    let artwork: CGSize

    static let wideMinimumWidth: CGFloat = 600
    static let portraitArtworkFraction: CGFloat = 0.45
    /// En dos columnas, la imagen no pasa de la mitad del ancho útil: la información necesita sitio.
    static let maxArtworkWidthFraction: CGFloat = 0.5
    static let margin = PocketSpacing.md
    static let columnSpacing = PocketSpacing.lg
    /// Aire por encima y por debajo de la imagen en dos columnas.
    static let verticalPadding = PocketSpacing.xs + PocketSpacing.md

    static func isWide(_ available: CGSize) -> Bool {
        available.width > available.height || available.width >= wideMinimumWidth
    }

    /// - Parameters:
    ///   - available: tamaño del área segura del detalle (sin barras ni recortes).
    ///   - aspectRatio: ancho / alto de la pantalla de la consola.
    static func make(available: CGSize, aspectRatio: CGFloat) -> DetailLayout {
        let ratio = max(aspectRatio, 0.1)
        let contentWidth = max(available.width - 2 * margin, 0)
        guard available.width > 0, available.height > 0 else {
            return DetailLayout(kind: .singleColumn, artwork: CGSize(width: contentWidth, height: contentWidth / ratio))
        }
        if isWide(available) {
            let maxWidth = (contentWidth - columnSpacing) * maxArtworkWidthFraction
            let maxHeight = max(available.height - verticalPadding, 0)
            let width = min(maxWidth, maxHeight * ratio)
            return DetailLayout(kind: .twoColumns, artwork: CGSize(width: width, height: width / ratio))
        }
        let maxHeight = available.height * portraitArtworkFraction
        let width = min(contentWidth, maxHeight * ratio)
        return DetailLayout(kind: .singleColumn, artwork: CGSize(width: width, height: width / ratio))
    }
}

/// Cómo se encuadra una captura o portada (N3a, corrige la captura GBA estirada a 10:9).
enum ArtworkStyle: Sendable {
    /// Marco uniforme 10:9 en cuadrícula, lista y carriles: la captura lo rellena centrada sin
    /// deformarse (una de GBA, 3:2, pierde un poco de los lados). Es el «Encaje» de N5.
    case card
    /// La proporción de la consola, entera (detalle, vista previa y estados).
    case console

    /// Ancho / alto del marco.
    func frameAspectRatio(for console: Console) -> CGFloat {
        switch self {
        case .card: CGFloat(ScreenSize.gameBoy.aspectRatio)
        case .console: CGFloat(console.screen.aspectRatio)
        }
    }

    /// Proporción de una miniatura por su tamaño en píxeles (una GBA de 240×160 es 3:2); si no se
    /// conoce, la de la consola.
    static func aspectRatio(of pixelSize: CGSize?, fallback console: Console) -> CGFloat {
        if let pixelSize, pixelSize.width > 0, pixelSize.height > 0 { return pixelSize.width / pixelSize.height }
        return CGFloat(console.screen.aspectRatio)
    }
}

/// Categoría de la biblioteca (N3b): la carpeta de primer nivel de `folderPath` (plan §3.2).
/// N4 añadirá subcategorías y categorías virtuales.
enum LibraryCategory: Hashable, Sendable, Identifiable {
    case all
    /// Juegos en la raíz de la carpeta.
    case uncategorized
    case folder(String)

    var id: String {
        switch self {
        case .all: "*all"
        case .uncategorized: "*root"
        case .folder(let name): "folder:\(name)"
        }
    }

    var title: String {
        switch self {
        case .all: "Todas"
        case .uncategorized: "Sin categoría"
        case .folder(let name): name
        }
    }

    var systemImage: String {
        switch self {
        case .all: "square.stack"
        case .uncategorized: "tray"
        case .folder: "folder"
        }
    }

    func contains(_ entry: RomEntry) -> Bool {
        switch self {
        case .all: true
        case .uncategorized: entry.folderPath.isEmpty
        case .folder(let name): entry.folderPath.first == name
        }
    }

    /// La misma categoría si sigue existiendo; si no (otra carpeta, juegos movidos u ocultos),
    /// «Todas» (auditoría N3, H7).
    func validated(in available: [LibraryCategory]) -> LibraryCategory {
        self == .all || available.contains(self) ? self : .all
    }

    /// Las carpetas de primer nivel con juegos, por nombre, y «Sin categoría» al final si hay
    /// juegos en la raíz. «Todas» no se incluye (la vista la añade arriba).
    static func available(in entries: [RomEntry]) -> [LibraryCategory] {
        var names: [String] = []
        var seen = Set<String>()
        var hasRoot = false
        for entry in entries {
            guard let first = entry.folderPath.first else { hasRoot = true; continue }
            if seen.insert(first).inserted { names.append(first) }
        }
        names.sort { $0.localizedStandardCompare($1) == .orderedAscending }
        return names.map(LibraryCategory.folder) + (hasRoot ? [.uncategorized] : [])
    }
}

extension LibraryQuery {
    /// Como `visible(_:prefs:filter:query:)`, solo con los juegos de una categoría (N3b).
    static func visible(_ entries: [RomEntry], prefs: LibraryPreferencesData, filter: LibraryFilter,
                        query: String, category: LibraryCategory) -> [RomEntry] {
        let inCategory = category == .all ? entries : entries.filter(category.contains)
        return visible(inCategory, prefs: prefs, filter: filter, query: query)
    }
}

extension LibraryPreferences {
    func visible(_ entries: [RomEntry], filter: LibraryFilter, query: String,
                 category: LibraryCategory) -> [RomEntry] {
        LibraryQuery.visible(entries, prefs: data, filter: filter, query: query, category: category)
    }

    /// Categorías con juegos visibles (sin ocultos), para el filtro «Categorías» (N3b).
    func categories(_ entries: [RomEntry]) -> [LibraryCategory] {
        LibraryCategory.available(in: entries.filter { !data.isHidden($0) })
    }
}
