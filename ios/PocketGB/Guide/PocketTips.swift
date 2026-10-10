import SwiftUI
import TipKit

/// N9 · Consejos con TipKit en cuatro puntos clave. Sin red: el almacén de TipKit es local
/// (`.applicationDefault`) y no se configura ningún contenedor de CloudKit. Como mucho uno al día
/// (`displayFrequency(.daily)`), y cada uno se retira al usar lo que explica.
enum PocketTips {
    static func configure() {
        #if DEBUG
        // Pruebas de UI y capturas (`-uiStyle` lo pasan todas): sin consejos, salvo `-showTips`, que
        // los muestra todos para su captura sin depender del almacén.
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-showTips") {
            try? Tips.resetDatastore()
            Tips.showAllTipsForTesting()
        } else if args.contains("-uiStyle") {
            Tips.hideAllTipsForTesting()
        }
        #endif
        try? Tips.configure([.displayFrequency(.daily), .datastoreLocation(.applicationDefault)])
    }
}

/// En la pausa, sobre «Momentos».
struct MomentsTip: Tip {
    var title: Text { Text("Guarda este instante") }
    var message: Text? {
        Text("Un momento guarda la posición exacta y la partida de ahora. Si algo sale mal, vuelves a él; antes de cargar, lo de ahora queda en «Antes de cargar».")
    }
    var image: Image? { Image(systemName: "bookmark") }
}

/// En los ajustes del juego, sobre «Categoría».
struct MoveCategoryTip: Tip {
    var title: Text { Text("Cámbialo de categoría") }
    var message: Text? {
        Text("«Cambiar» lo muestra en otra categoría sin mover ni renombrar el archivo. «Volver a su carpeta» lo deshace.")
    }
    var image: Image? { Image(systemName: "folder") }
}

/// En el detalle del juego, sobre «Enviar a otro dispositivo».
struct SendToDeviceTip: Tip {
    var title: Text { Text("Sigue en otro equipo") }
    var message: Text? {
        Text("Envía un paquete con la partida y el punto exacto. En el otro iPhone o en Android, ábrelo con PocketGB y continúa donde lo dejaste.")
    }
    var image: Image? { Image(systemName: "paperplane") }
}

/// En Ajustes › Controles, sobre la cruceta.
struct SeparateArrowsTip: Tip {
    var title: Text { Text("Prueba las flechas separadas") }
    var message: Text? {
        Text("Cuatro botones con hueco entre ellos. En Personalizar controles ajustas su tamaño, su separación y dónde van.")
    }
    var image: Image? { Image(systemName: "dpad") }
}
