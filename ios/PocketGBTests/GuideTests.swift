import Foundation
import Testing
@testable import PocketGB

/// N9 · Ajustes › Guía: contenido empaquetado (sin red), lector de Markdown y búsqueda.
struct GuideTests {
    // MARK: Contenido del bundle

    @Test func everySectionIsBundledAndNotEmpty() throws {
        let library = GuideLibrary(bundle: .main)
        #expect(GuideLibrary.sections.count == 9)
        #expect(Set(GuideLibrary.sections.map(\.id)).count == GuideLibrary.sections.count)
        for section in GuideLibrary.sections {
            let url = try #require(Bundle.main.url(forResource: "guia-\(section.id)", withExtension: "md"),
                                   "Falta guia-\(section.id).md en el bundle")
            let text = try String(contentsOf: url, encoding: .utf8)
            #expect(text.hasPrefix("<!-- Generado por tools/ios-guide-sync.py"), "\(section.id) no viene de docs/guia")
            let blocks = library.blocks(section.id)
            #expect(blocks.count > 5, "\(section.id) casi vacía")
            #expect(blocks.contains { if case .heading = $0 { true } else { false } }, "\(section.id) sin apartados")
        }
    }

    /// Regla dura 5: nada remoto. Los únicos enlaces son `guia:<id>` a otra sección que existe; ni
    /// direcciones web ni rutas de documentos del repo ni guías de Android.
    @Test func bundledGuideHasOnlyInternalLinks() throws {
        let link = /\]\(([^)\s]*)\)/
        for section in GuideLibrary.sections {
            let url = try #require(Bundle.main.url(forResource: "guia-\(section.id)", withExtension: "md"))
            let text = try String(contentsOf: url, encoding: .utf8)
            #expect(!text.localizedCaseInsensitiveContains("http"), "\(section.id) menciona una dirección web")
            #expect(!text.contains("-android.md") && !text.contains("../"), "\(section.id) enlaza fuera de la guía")
            for match in text.matches(of: link) {
                let target = String(match.output.1)
                #expect(target.hasPrefix("guia:"), "\(section.id): enlace \(target)")
                #expect(GuideLibrary.section(String(target.dropFirst(5))) != nil, "\(section.id): sección \(target)")
            }
        }
    }

    // MARK: Lector

    @Test func parsesHeadingsParagraphsAndComments() {
        let blocks = GuideParser.parse("""
        <!-- comentario -->
        ## Uno
        Primera línea
        sigue aquí.

        ### Dos
        > Nota breve.
        """)
        #expect(blocks == [
            .heading(level: 2, text: "Uno"),
            .paragraph("Primera línea sigue aquí."),
            .heading(level: 3, text: "Dos"),
            .note("Nota breve."),
        ])
    }

    @Test func parsesNestedAndOrderedLists() {
        let blocks = GuideParser.parse("""
        - **Chincheta**: fijarla.
          - Sub punto
        - Otro

        1. Abre.
        2. Elige
           una.
        """)
        #expect(blocks == [
            .list(ordered: false, items: [
                GuideListItem(level: 0, marker: "•", text: "**Chincheta**: fijarla."),
                GuideListItem(level: 1, marker: "•", text: "Sub punto"),
                GuideListItem(level: 0, marker: "•", text: "Otro"),
            ]),
            .list(ordered: true, items: [
                GuideListItem(level: 0, marker: "1.", text: "Abre."),
                GuideListItem(level: 0, marker: "2.", text: "Elige una."),
            ]),
        ])
    }

    @Test func parsesTablesWithoutTheSeparatorRow() {
        let blocks = GuideParser.parse("""
        | Opción | Qué hace |
        |---|:---:|
        | **Reducidas** | La diagonal casi en la esquina. |
        | Normales | Ocho sectores. |
        Después
        """)
        #expect(blocks == [
            .table(header: ["Opción", "Qué hace"], rows: [["**Reducidas**", "La diagonal casi en la esquina."],
                                                          ["Normales", "Ocho sectores."]]),
            .paragraph("Después"),
        ])
    }

    @Test func keepsCodeBlocksVerbatim() {
        let blocks = GuideParser.parse("""
        Antes
        ```
        Roms/
          Pokémon/   ← categoría
        ```
        """)
        #expect(blocks == [.paragraph("Antes"), .code("Roms/\n  Pokémon/   ← categoría")])
        // Un bloque sin cerrar no se pierde.
        #expect(GuideParser.parse("```\nabc") == [.code("abc")])
    }

    @Test func plainTextDropsMarkdown() {
        #expect(GuideText.plain("Ver **[Portadas](guia:portadas)** y `.sav`.") == "Ver Portadas y .sav.")
        #expect(GuideText.plain("sin cierre [x") == "sin cierre [x")
    }

    // MARK: Búsqueda

    private var sample: GuideLibrary {
        GuideLibrary(documents: [
            "categorias": GuideParser.parse("## Mostrar en categoría\nPara ver un juego en otra categoría **sin mover el archivo**."),
            "momentos": GuideParser.parse("## Cargar un momento\nCargar un momento cambia también la partida."),
        ])
    }

    @Test func searchIgnoresCaseAndAccents() {
        let hits = sample.search("CATEGORIA archivo")
        #expect(hits.contains { $0.sectionID == "categorias" && $0.block == 1 && $0.heading == "Mostrar en categoría" })
        #expect(!hits.contains { $0.sectionID == "momentos" })
    }

    @Test func searchNeedsEveryWord() {
        #expect(sample.search("momento partida").map(\.sectionID).contains("momentos"))
        #expect(sample.search("momento archivo").isEmpty)
        #expect(sample.search("   ").isEmpty)
    }

    /// Un título que coincide no se repite si su apartado ya sale; solo, sale sin fragmento.
    @Test func headingHitsAreNotDuplicated() {
        let hits = sample.search("categoria")
        // (El primer resultado, sin bloque, es el título de la sección «Categorías y etiquetas».)
        #expect(hits.filter { $0.sectionID == "categorias" }.map(\.block) == [nil, 1])
        let only = GuideLibrary(documents: ["viajar": GuideParser.parse("## Exportar\nOtra cosa.")]).search("exportar")
        #expect(only.filter { $0.block != nil }.map(\.block) == [0])
        #expect(only.first { $0.block == 0 }?.snippet == "")
    }

    @Test func searchMatchesSectionTitles() {
        let hits = sample.search("portadas")
        #expect(hits.contains { $0.sectionID == "portadas" && $0.block == nil })
    }

    @Test func realGuideFindsKeyTopics() {
        let library = GuideLibrary(bundle: .main)
        for (query, section) in [("antes de cargar", "momentos"), ("pgbm", "viajar"), ("separacion", "controles"),
                                 ("gba_bios", "gba"), ("_Revisar", "carpetas"), ("volver a su carpeta", "categorias")] {
            #expect(library.search(query).contains { $0.sectionID == section }, "«\(query)» no encuentra \(section)")
        }
    }

    @Test func snippetIsShortAndCentred() {
        let long = String(repeating: "relleno ", count: 40) + "Pokémon" + String(repeating: " más", count: 40)
        let snippet = GuideLibrary.snippet(long, around: "pokemon")
        #expect(snippet.count <= 142)
        #expect(snippet.contains("Pokémon"))
        #expect(snippet.hasPrefix("…") && snippet.hasSuffix("…"))
        #expect(GuideLibrary.snippet("corto", around: "x") == "corto")
    }
}
