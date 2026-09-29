import SwiftUI

/// Tab Favoritos. Los carriles de recientes y favoritos llegan en D3.
struct FavoritesView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                EmptyStateView(
                    title: "Sin favoritos todavía",
                    systemImage: "star",
                    message: "Marca un juego con la estrella para tenerlo aquí, junto a los que jugaste hace poco.")
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, PocketSpacing.md)
                    .padding(.top, PocketSpacing.xxl)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background(PocketColor.backgroundBase.ignoresSafeArea())
            .navigationTitle("Favoritos")
        }
    }
}
