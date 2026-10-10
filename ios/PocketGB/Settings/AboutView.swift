import SwiftUI

/// Acerca de: versión, núcleo, privacidad sin red y licencias (SPEC §9, `settings-about`).
struct AboutView: View {
    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "—"
        let build = info?["CFBundleVersion"] as? String ?? "—"
        return "\(short) (\(build))"
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Versión", value: version)
                LabeledContent("Núcleo", value: "PocketGB core (C11)")
                LabeledContent("Consolas", value: "Game Boy, Game Boy Color y Game Boy Advance")
            }
            Section {
                Label {
                    Text("PocketGB no se conecta a internet: sin telemetría, sin anuncios y sin descargar portadas. La guía y los consejos van dentro de la app. Tus juegos y partidas se quedan en tu iPhone y en tu iCloud Drive.")
                } icon: {
                    Image(systemName: "wifi.slash")
                }
            } header: {
                Text("Privacidad")
            }
            Section {
                Label {
                    Text("Usa solo ROMs volcados de tus propios cartuchos. PocketGB no incluye juegos ni el software de arranque de Nintendo.")
                } icon: {
                    Image(systemName: "gamecontroller")
                }
            } header: {
                Text("Juegos")
            }
            Section {
                NavigationLink(value: SettingsRoute.licenses) {
                    Label("Licencias de terceros", systemImage: "doc.text")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Acerca de")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Avisos de licencia del código o datos de terceros incluidos en la app.
struct LicensesView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PocketSpacing.md) {
                Text("SameBoy")
                    .font(.headline)
                Text("Tablas de paletas de compatibilidad de Game Boy Color (core/src/cgb.c), transcritas de BootROMs/cgb_boot.asm.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Text(Self.sameBoyLicense)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(PocketSpacing.md)
        }
        .background(PocketColor.backgroundBase.ignoresSafeArea())
        .navigationTitle("Licencias")
        .navigationBarTitleDisplayMode(.inline)
    }

    static let sameBoyLicense = """
    Expat License

    Copyright (c) 2015-2026 Lior Halphon

    Permission is hereby granted, free of charge, to any person obtaining a copy \
    of this software and associated documentation files (the "Software"), to deal \
    in the Software without restriction, including without limitation the rights \
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell \
    copies of the Software, and to permit persons to whom the Software is \
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all \
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR \
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, \
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE \
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER \
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, \
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE \
    SOFTWARE.
    """
}
