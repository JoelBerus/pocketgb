import Foundation
import Testing
@testable import PocketGB

/// N4: árbol de categorías (estanterías, subcategorías, migas y conteos, con categorías virtuales),
/// categoría virtual aplicada y revertida (también con copias), etiquetas, búsqueda y filtro, ajustes
/// del inicio y escritura solo con la huella confirmada. Preferencias en memoria (`fileURL: nil`).
@MainActor
struct LibraryOrganizationTests {
    /// Un juego con su huella confirmada (`fingerprintVerified`), como tras el escaneo o el cálculo.
    static func game(_ path: String, _ fingerprint: String?, verified: Bool = true, color: Bool = false) -> RomEntry {
        var e = LibraryPreferencesTests.entry(path, (path as NSString).lastPathComponent.uppercased(), color: color)
        e.fingerprint = fingerprint
        e.fingerprintVerified = fingerprint != nil && verified
        return e
    }

    /// Árbol sintético:
    /// ```
    /// raiz.gb
    /// Pokémon/1ª generación/{rojo.gb, amarillo.gbc}
    /// Pokémon/2ª generación/oro.gbc
    /// Pokémon/2ª generación/Johto/cristal.gbc
    /// Kirby/kirby.gba
    /// Puzles/tetra.gb
    /// Copias/rojo.gb          ← la misma huella que Pokémon/1ª generación/rojo.gb
    /// ```
    static func tree() -> [RomEntry] {
        var entries = [
            game("raiz.gb", "fp-raiz"),
            game("Pokémon/1ª generación/rojo.gb", "fp-rojo"),
            game("Pokémon/1ª generación/amarillo.gbc", "fp-amarillo", color: true),
            game("Pokémon/2ª generación/oro.gbc", "fp-oro", color: true),
            game("Pokémon/2ª generación/Johto/cristal.gbc", "fp-cristal", color: true),
            game("Kirby/kirby.gba", "fp-kirby"),
            game("Puzles/tetra.gb", "fp-tetra"),
            game("Copias/rojo.gb", "fp-rojo"),
        ]
        LibraryIdentity.markDuplicates(&entries)
        return entries
    }

    private func named(_ entries: [RomEntry], _ path: String) -> RomEntry {
        entries.first { $0.id == path }!
    }

    private func ids(_ entries: [RomEntry]) -> [String] { entries.map(\.id).sorted() }

    // MARK: Árbol

    @Test func syntheticTreeGivesShelvesSubcategoriesBreadcrumbsAndCounts() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        let data = prefs.data
        let options = LibraryCategory.options(entries, prefs: data)
        #expect(options.map(\.category) == [.folder("Copias"), .folder("Kirby"), .folder("Pokémon"), .folder("Puzles"),
                                            .uncategorized])
        #expect(options.map(\.count) == [1, 1, 4, 1, 1])

        let home = LibraryHome.sections(entries, prefs: data)
        #expect(home.shelves.map(\.category.key) == ["Copias", "Kirby", "Pokémon", "Puzles", "."])
        #expect(home.shelves.map(\.total) == [1, 1, 4, 1, 1])
        #expect(home.shelves[2].games.count == 4)

        let shown = LibraryQuery.visible(entries, prefs: data, filter: .all, query: "")
        let pokemon = LibraryTree.subcategories(shown, of: ["Pokémon"], prefs: data)
        #expect(pokemon.map(\.name) == ["1ª generación", "2ª generación"])
        #expect(pokemon.map(\.count) == [2, 2])
        #expect(LibraryTree.subcategories(shown, of: ["Pokémon", "2ª generación"], prefs: data).map(\.path)
                == [["Pokémon", "2ª generación", "Johto"]])
        #expect(LibraryTree.subcategories(shown, of: [], prefs: data).isEmpty)   // la raíz no tiene subcategorías

        // Una categoría muestra sus juegos y los de todas sus subcarpetas; «Sin categoría», solo la raíz.
        #expect(ids(LibraryTree.games(shown, in: ["Pokémon"], prefs: data)).count == 4)
        #expect(ids(LibraryTree.games(shown, in: ["Pokémon", "2ª generación"], prefs: data))
                == ["Pokémon/2ª generación/Johto/cristal.gbc", "Pokémon/2ª generación/oro.gbc"])
        #expect(ids(LibraryTree.games(shown, in: [], prefs: data)) == ["raiz.gb"])
        // Por niveles, no por texto: «Poké» no es un prefijo de «Pokémon».
        #expect(LibraryTree.games(shown, in: ["Poké"], prefs: data).isEmpty)

        #expect(LibraryTree.breadcrumbs(["Pokémon", "2ª generación", "Johto"])
                == [["Pokémon"], ["Pokémon", "2ª generación"], ["Pokémon", "2ª generación", "Johto"]])
        #expect(CategoryPaths.display(["Pokémon", "2ª generación"]) == "Pokémon › 2ª generación")
        #expect(CategoryPaths.display([]) == "Sin categoría")

        // Lo que ofrece «Mostrar en categoría…»: carpetas con juegos y sus padres, en profundidad.
        #expect(LibraryTree.knownPaths(entries, prefs: data) == [
            ["Copias"], ["Kirby"], ["Pokémon"], ["Pokémon", "1ª generación"], ["Pokémon", "2ª generación"],
            ["Pokémon", "2ª generación", "Johto"], ["Puzles"],
        ])
    }

    @Test func hiddenGamesLeaveTheTreeAndCategoriesWithoutGamesDisappear() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.hide(named(entries, "Kirby/kirby.gba"))
        let options = LibraryCategory.options(entries, prefs: prefs.data)
        #expect(!options.contains { $0.category == .folder("Kirby") })
        #expect(!LibraryTree.knownPaths(entries, prefs: prefs.data).contains(["Kirby"]))
    }

    @Test func withoutFoldersThereAreNoShelvesButFavoritesStay() {
        let entries = [Self.game("a.gb", "fa"), Self.game("b.gb", "fb")]
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.toggleFavorite(entries[0])
        let home = LibraryHome.sections(entries, prefs: prefs.data)
        #expect(home.shelves.isEmpty)                       // repetirían «Todos los juegos» (N4A-2)
        #expect(home.favorites.map(\.id) == ["a.gb"])
    }

    @Test func theFavoritesRowShowsTenAndCountsEveryFavoriteOnce() {
        var entries = (1...11).map { Self.game("Saga/juego\($0).gb", "fp\($0)") }
        entries.append(Self.game("Copias/juego1.gb", "fp1"))   // copia: el mismo juego
        LibraryIdentity.markDuplicates(&entries)
        let prefs = LibraryPreferences(fileURL: nil)
        for entry in entries.prefix(11) { prefs.toggleFavorite(entry) }
        let home = LibraryHome.sections(entries, prefs: prefs.data)
        #expect(home.favorites.count == LibraryHome.shelfLimit)
        #expect(home.favoritesTotal == 11)                  // no 12: la copia no cuenta dos veces
        let saga = home.shelves.first { $0.category == .folder("Saga") }
        #expect(saga?.games.count == 10 && saga?.total == 11)
        prefs.updateHome { $0.showFavorites = false }
        #expect(LibraryHome.sections(entries, prefs: prefs.data).favorites.isEmpty)
    }

    // MARK: Categoría virtual (ND3)

    @Test func aVirtualCategoryIsAppliedAndReverted() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        let tetra = named(entries, "Puzles/tetra.gb")
        #expect(prefs.moveToCategory(["Para jugar"], entry: tetra))
        #expect(prefs.categoryPath(tetra) == ["Para jugar"])
        #expect(prefs.isMovedInApp(tetra))
        #expect(tetra.folderPath == ["Puzles"])             // el archivo no se mueve
        let data = prefs.data
        let options = LibraryCategory.options(entries, prefs: data)
        #expect(options.contains { $0.category == .folder("Para jugar") && $0.count == 1 })
        #expect(!options.contains { $0.category == .folder("Puzles") })   // se quedó sin juegos
        let shown = LibraryQuery.visible(entries, prefs: data, filter: .all, query: "")
        #expect(ids(LibraryTree.games(shown, in: ["Para jugar"], prefs: data)) == ["Puzles/tetra.gb"])
        #expect(LibraryTree.knownPaths(entries, prefs: data).contains(["Para jugar"]))
        // La búsqueda mira la categoría en la que se ve (N4A-6).
        #expect(ids(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "para jug")) == ["Puzles/tetra.gb"])
        #expect(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "puzles").isEmpty)
        #expect(LibraryHome.sections(entries, prefs: data).shelves.contains { $0.category == .folder("Para jugar") })

        #expect(prefs.returnToFolder(tetra))
        #expect(prefs.categoryPath(tetra) == ["Puzles"])
        #expect(!prefs.isMovedInApp(tetra))
        #expect(prefs.data.games["fp-tetra"] == nil)        // nada que recordar: no queda entrada vacía

        // Elegir su propia carpeta es lo mismo que volver; `[]` es «Sin categoría».
        #expect(prefs.moveToCategory([], entry: tetra))
        #expect(prefs.categoryPath(tetra).isEmpty && prefs.isMovedInApp(tetra))
        #expect(ids(LibraryTree.games(LibraryQuery.visible(entries, prefs: prefs.data, filter: .all, query: ""),
                                      in: [], prefs: prefs.data)) == ["Puzles/tetra.gb", "raiz.gb"])
        #expect(prefs.moveToCategory(["Puzles"], entry: tetra))
        #expect(prefs.virtualFolder(tetra) == nil)
        // Una ruta que no cumple las reglas de las carpetas no se guarda.
        #expect(!prefs.moveToCategory(["_Revisar"], entry: tetra))
        #expect(!prefs.moveToCategory(["A", "B", "C", "D", "E", "F"], entry: tetra))
        #expect(prefs.virtualFolder(tetra) == nil)
    }

    /// N4A-5: las copias (misma huella) comparten categoría virtual; volver devuelve cada una a la
    /// suya, y la copia que ya está en esa carpeta de verdad no lleva la insignia (H10 de Android).
    @Test func copiesShareTheVirtualCategory() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        let original = named(entries, "Pokémon/1ª generación/rojo.gb")
        let copy = named(entries, "Copias/rojo.gb")
        #expect(prefs.moveToCategory(["Pokémon", "Para jugar"], entry: copy))
        #expect(prefs.categoryPath(original) == ["Pokémon", "Para jugar"])
        #expect(prefs.categoryPath(copy) == ["Pokémon", "Para jugar"])
        let shown = LibraryQuery.visible(entries, prefs: prefs.data, filter: .all, query: "")
        #expect(ids(LibraryTree.games(shown, in: ["Pokémon", "Para jugar"], prefs: prefs.data))
                == ["Copias/rojo.gb", "Pokémon/1ª generación/rojo.gb"])
        #expect(LibraryTree.subcategories(shown, of: ["Pokémon"], prefs: prefs.data).map(\.name)
                == ["1ª generación", "2ª generación", "Para jugar"])
        #expect(LibraryTree.subcategories(shown, of: ["Pokémon"], prefs: prefs.data).first?.count == 1)

        #expect(prefs.moveToCategory(["Copias"], entry: original))
        #expect(prefs.isMovedInApp(original))
        #expect(!prefs.isMovedInApp(copy))                   // ya está en «Copias» de verdad

        #expect(prefs.returnToFolder(copy))
        #expect(prefs.categoryPath(original) == ["Pokémon", "1ª generación"])
        #expect(prefs.categoryPath(copy) == ["Copias"])
    }

    /// Como Android (N1-H1): etiquetas y categoría virtual solo se escriben con la huella confirmada.
    /// Con una huella de caché obsoleta (pista) o solo la última conocida de la ruta, nada.
    @Test func withoutAConfirmedFingerprintNothingIsWritten() {
        let prefs = LibraryPreferences(fileURL: nil)
        let stale = Self.game("Puzles/tetra.gb", "fp-de-otro-juego", verified: false)
        let noScan = Self.game("Puzles/otro.gb", nil)
        prefs.recordPlayed(id: noScan.id, fingerprint: "fp-pista", at: Date())     // pista por ruta
        let before = prefs.data
        for entry in [stale, noScan] {
            #expect(prefs.confirmedFingerprint(of: entry) == nil)
            #expect(prefs.addTag("rpg", to: entry) == .unconfirmed)
            #expect(!prefs.removeTag("rpg", from: entry))
            #expect(!prefs.moveToCategory(["Para jugar"], entry: entry))
            #expect(!prefs.returnToFolder(entry))
        }
        #expect(prefs.data == before)
        // Con la huella confirmada sí, y lo que se ve con la pista es lo de esa huella.
        var confirmed = noScan
        confirmed.fingerprint = "fp-pista"
        confirmed.fingerprintVerified = true
        #expect(prefs.addTag("rpg", to: confirmed) == .added)
        #expect(prefs.tags(noScan) == ["rpg"])
    }

    // MARK: Etiquetas

    @Test func tagsAreNormalizedCaseAndAccentInsensitiveAndLimited() {
        #expect(Tags.normalize("  #RPG   de \n acción ") == "RPG de acción")
        #expect(Tags.normalize(" ## ") == nil)
        #expect(Tags.normalize(String(repeating: "a", count: 40))?.count == Tags.maxLength)
        #expect(Tags.same("Acción", "accion") && Tags.same("RPG", "rpg"))
        #expect(Tags.sanitized(["b", "A", "a", "", "#c"]) == ["A", "b", "c"])

        let prefs = LibraryPreferences(fileURL: nil)
        let game = Self.game("Saga/a.gb", "fa")
        #expect(prefs.addTag("RPG de acción", to: game) == .added)
        #expect(prefs.addTag("rpg de accion", to: game) == .duplicate)
        #expect(prefs.addTag("   ", to: game) == .empty)
        #expect(prefs.addTag("Pendiente", to: game) == .added)
        #expect(prefs.tags(game) == ["Pendiente", "RPG de acción"])          // orden natural
        for i in 3...Tags.maxPerGame { #expect(prefs.addTag("t\(i)", to: game) == .added) }
        #expect(prefs.tags(game).count == Tags.maxPerGame)
        #expect(prefs.addTag("una más", to: game) == .full)
        #expect(prefs.removeTag("rpg DE ACCION", from: game))
        #expect(!prefs.tags(game).contains("RPG de acción"))
    }

    @Test func searchFindsTagsAndCategoriesAndTheTagFilterCombines() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.addTag("RPG", to: named(entries, "Pokémon/1ª generación/rojo.gb"))
        prefs.addTag("rpg", to: named(entries, "Pokémon/2ª generación/oro.gbc"))
        prefs.addTag("dos jugadores", to: named(entries, "Puzles/tetra.gb"))
        let data = prefs.data
        // Etiquetas: «RPG» y «rpg» son una opción; las copias cuentan como archivos.
        let options = LibraryQuery.tagOptions(entries, prefs: data)
        #expect(options.map(\.tag) == ["dos jugadores", "RPG"])
        #expect(options.map(\.count) == [1, 3])                  // rojo, su copia y oro
        // Búsqueda: etiqueta y cada nivel de la categoría, sin acentos ni mayúsculas.
        #expect(ids(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "jugadores")) == ["Puzles/tetra.gb"])
        #expect(ids(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "johto"))
                == ["Pokémon/2ª generación/Johto/cristal.gbc"])
        #expect(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "pokemon").count == 4)
        // Filtro por etiqueta, combinable con el de consola.
        #expect(ids(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "", tag: "Rpg"))
                == ["Copias/rojo.gb", "Pokémon/1ª generación/rojo.gb", "Pokémon/2ª generación/oro.gbc"])
        #expect(ids(LibraryQuery.visible(entries, prefs: data, filter: .gbc, query: "", tag: "rpg"))
                == ["Pokémon/2ª generación/oro.gbc"])
        #expect(LibraryQuery.visible(entries, prefs: data, filter: .all, query: "", tag: nil).count == entries.count)
    }

    // MARK: Categorías escritas

    @Test func typedCategoriesFollowTheFolderRulesAndReuseExistingOnes() throws {
        #expect(try CategoryPaths.parse(" Pokémon /  Para   jugar ").get() == ["Pokémon", "Para jugar"])
        #expect(try CategoryPaths.parse("Pokémon › Para jugar").get() == ["Pokémon", "Para jugar"])
        #expect(CategoryPaths.parse("  / ") == .failure(.empty))
        #expect(CategoryPaths.parse("Pokémon/_Revisar") == .failure(.reserved))
        #expect(CategoryPaths.parse(".oculta") == .failure(.reserved))
        #expect(CategoryPaths.parse("A/B/C/D/E/F") == .failure(.tooDeep))
        #expect(try CategoryPaths.parse(String(repeating: "x", count: 80)).get().first?.count == CategoryPaths.maxSegmentLength)

        let known = LibraryTree.knownPaths(Self.tree(), prefs: LibraryPreferencesData())
        #expect(CategoryPaths.matchExisting(["pokemon", "2ª GENERACION"], known: known) == ["Pokémon", "2ª generación"])
        #expect(CategoryPaths.matchExisting(["pokemon", "para jugar"], known: known) == ["Pokémon", "para jugar"])
        #expect(CategoryPaths.matchExisting(["Nueva"], known: known) == ["Nueva"])
        // Un nivel solo se reutiliza dentro de su padre: «johto» suelto no es «Pokémon › … › Johto».
        #expect(CategoryPaths.matchExisting(["johto"], known: known) == ["johto"])
    }

    @Test func eachCategoryRemembersItsLayout() {
        let prefs = LibraryPreferences(fileURL: nil)
        #expect(prefs.data.layout(forCategory: ["Pokémon"]) == .grid)
        prefs.setCategoryLayout(.list, for: ["Pokémon"])
        prefs.setCategoryLayout(.list, for: [])
        #expect(prefs.data.layout(forCategory: ["Pokémon"]) == .list)
        #expect(prefs.data.layout(forCategory: ["Kirby"]) == .grid)
        #expect(prefs.data.categoryLayouts == ["Pokémon": .list, ".": .list])
        // Cambiar la vista de la biblioteca no cambia la que eligió cada categoría.
        prefs.setLayout(.list)
        prefs.setCategoryLayout(.grid, for: ["Pokémon"])
        prefs.setLayout(.grid)
        #expect(prefs.data.layout(forCategory: ["Pokémon"]) == .grid)
        #expect(prefs.data.layout(forCategory: []) == .list)
    }

    // MARK: Inicio

    @Test func homeSettingsKeepTheRelativeOrderAndPinningDoesNotReorder() {
        let keys = ["Aventuras", "Kirby", "Pokémon", "."]
        var home = HomeSettings()
        #expect(home.arrange(keys) == keys)                                 // de fábrica: natural, raíz al final
        home = home.moving("Pokémon", by: -1, keys: keys)
        #expect(home.arrange(keys) == ["Aventuras", "Pokémon", "Kirby", "."])
        #expect(!home.order.contains("."))                                  // la raíz al final no se guarda

        // Fijar no cambia el orden guardado: al soltar vuelve a su sitio (N4A-9).
        home = home.pinning("Kirby", true)
        #expect(home.arrange(keys) == ["Kirby", "Aventuras", "Pokémon", "."])
        // Subir/bajar no cruza de fijadas a no fijadas.
        #expect(home.moving("Kirby", by: 1, keys: keys).arrange(keys) == home.arrange(keys))
        #expect(home.moving("Aventuras", by: -1, keys: keys).arrange(keys) == home.arrange(keys))
        // Mover otra con una fijada no la pone delante en el orden guardado (H11 de Android).
        home = home.moving("Pokémon", by: -1, keys: keys)
        #expect(home.arrange(keys) == ["Kirby", "Pokémon", "Aventuras", "."])
        home = home.pinning("Kirby", false)
        #expect(home.arrange(keys) == ["Pokémon", "Aventuras", "Kirby", "."])

        // Una carpeta nueva aparece al final de las ordenadas y antes de «Sin categoría».
        let more = ["Aventuras", "Kirby", "Pokémon", "Zelda", "."]
        #expect(home.arrange(more) == ["Pokémon", "Aventuras", "Kirby", "Zelda", "."])
        // Una que falta un rato conserva su sitio.
        #expect(home.arrange(["Aventuras", "Pokémon", "."]) == ["Pokémon", "Aventuras", "."])
        #expect(home.arrange(more).contains("Kirby"))
        // «Sin categoría» movida a mano sí se recuerda.
        let rootFirst = home.moving(".", by: -3, keys: keys).moving(".", by: -1, keys: keys)
        #expect(rootFirst.arrange(keys).firstIndex(of: ".")! < 3)
    }

    @Test func hiddenAndPinnedCategoriesShapeTheHome() {
        let entries = Self.tree()
        let prefs = LibraryPreferences(fileURL: nil)
        prefs.updateHome { $0 = $0.hiding("Kirby", true).pinning("Puzles", true) }
        let home = LibraryHome.sections(entries, prefs: prefs.data)
        #expect(home.shelves.map(\.category.key) == ["Puzles", "Copias", "Pokémon", "."])
        #expect(home.shelves.first?.pinned == true)
        // Ajustes › Biblioteca › Inicio sigue mostrando la oculta.
        let rows = LibraryHome.arrangement(entries, prefs: prefs.data)
        #expect(rows.map(\.key) == ["Puzles", "Copias", "Kirby", "Pokémon", "."])
        #expect(rows.first { $0.key == "Kirby" }?.hidden == true)
        // Sus juegos siguen en «Todos los juegos».
        #expect(LibraryQuery.visible(entries, prefs: prefs.data, filter: .all, query: "kirby").count == 1)
        prefs.updateHome { $0 = HomeSettings() }
        #expect(prefs.data.home.isDefault)
    }

    // MARK: Restauración de la pila

    @Test func theLibraryStackSurvivesEncodingAndRejectsNonsense() throws {
        let path: [LibraryRoute] = [.category(path: ["Pokémon"]), .category(path: ["Pokémon", "2ª generación"]),
                                    .details(id: "Pokémon/2ª generación/oro.gbc", source: "category-x"),
                                    .category(path: [])]
        let data = try #require(LibraryNavigationRestoration.encode(path))
        #expect(LibraryNavigationRestoration.decode(data) == path)
        #expect(LibraryNavigationRestoration.decode(Data("[1,2]".utf8)) == nil)
        #expect(LibraryNavigationRestoration.decode(nil) == nil)
        let bad = try JSONEncoder().encode([LibraryRoute.category(path: ["_Revisar"])])
        #expect(LibraryNavigationRestoration.decode(bad) == [])
    }
}
