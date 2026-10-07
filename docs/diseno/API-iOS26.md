# APIs de iOS 26 para el diseño (extraídas del SDK)

Generado con Xcode 26.6 Build version 17F113 desde `iPhoneOS26.5.sdk`. La nube no tiene SDK: **usa solo firmas que aparezcan aquí o que ya compilen en el repo**; si necesitas otra, anótala como duda y compruébala con el CI. Regenerar en el Mac: `tools/extract-ios-apis.sh`.

## SwiftUICore
```swift
extension SwiftUICore.GlassEffectContainer : Swift.Sendable {}
public func glassEffect(_ glass: SwiftUICore.Glass = .regular, in shape: some Shape = DefaultGlassEffectShape()) -> some SwiftUICore.View
public func glassEffectID(_ id: (some (Hashable & Sendable))?, in namespace: SwiftUICore.Namespace.ID) -> some SwiftUICore.View
public func interactive(_ isEnabled: Swift.Bool = true) -> SwiftUICore.Glass
public func tint(_ color: SwiftUICore.Color?) -> SwiftUICore.Glass
public static var clear: SwiftUICore.Color {
public static var clear: SwiftUICore.Glass {
public static var identity: SwiftUICore.AnyTransition {
public static var identity: SwiftUICore.Glass {
public static var identity: SwiftUICore.GlassEffectTransition {
public static var matchedGeometry: SwiftUICore.GlassEffectTransition {
public static var materialize: SwiftUICore.GlassEffectTransition {
public static var regular: SwiftUICore.Glass {
public static var regularMaterial: SwiftUICore.Material {
public struct DefaultGlassEffectShape : SwiftUICore.Shape {
public struct Glass : Swift.Equatable, Swift.Sendable {
public struct GlassEffectTransition : Swift.Sendable {
```

## SwiftUI
```swift
extension SwiftUI.AutomaticNavigationTransition : Swift.Sendable {
extension SwiftUI.GlassButtonStyle : Swift.Sendable {
extension SwiftUI.GlassProminentButtonStyle : Swift.Sendable {
extension SwiftUI.NavigationTransition where Self == SwiftUI.AutomaticNavigationTransition {
extension SwiftUI.NavigationTransition where Self == SwiftUI.ZoomNavigationTransition {
extension SwiftUI.PrimitiveButtonStyle where Self == SwiftUI.GlassButtonStyle {
extension SwiftUI.PrimitiveButtonStyle where Self == SwiftUI.GlassProminentButtonStyle {
extension SwiftUI.ToolbarSpacer : Swift.Sendable {
extension SwiftUI.ZoomNavigationTransition : Swift.Sendable {
public func interactiveDismissDisabled(_ isDisabled: Swift.Bool = true) -> some SwiftUICore.View
public func matchedTransitionSource(id: some Hashable, in namespace: SwiftUICore.Namespace.ID) -> some SwiftUI.CustomizableToolbarContent
public func matchedTransitionSource(id: some Hashable, in namespace: SwiftUICore.Namespace.ID) -> some SwiftUI.ToolbarContent
public func matchedTransitionSource(id: some Hashable, in namespace: SwiftUICore.Namespace.ID) -> some SwiftUICore.View
public func matchedTransitionSource(id: some Hashable, in namespace: SwiftUICore.Namespace.ID, configuration: (SwiftUI.EmptyMatchedTransitionSourceConfiguration) -> some MatchedTransitionSourceConfiguration) -> some SwiftUICore.View
public func navigationTransition(_ style: some NavigationTransition) -> some SwiftUICore.View
public func presentationBackground<S>(_ style: S) -> some SwiftUICore.View where S : SwiftUICore.ShapeStyle
public func presentationBackground<V>(alignment: SwiftUICore.Alignment = .center, @SwiftUICore.ViewBuilder content: () -> V) -> some SwiftUICore.View where V : SwiftUICore.View
public func presentationBackgroundInteraction(_ interaction: SwiftUI.PresentationBackgroundInteraction) -> some SwiftUICore.View
public func presentationDetents(_ detents: Swift.Set<SwiftUI.PresentationDetent>) -> some SwiftUICore.View
public func presentationDetents(_ detents: Swift.Set<SwiftUI.PresentationDetent>, selection: SwiftUICore.Binding<SwiftUI.PresentationDetent>) -> some SwiftUICore.View
public func scrollEdgeEffectHidden(_ hidden: Swift.Bool = true, for edges: SwiftUICore.Edge.Set = .all) -> some SwiftUICore.View
public func scrollEdgeEffectStyle(_ style: SwiftUI.ScrollEdgeEffectStyle?, for edges: SwiftUICore.Edge.Set) -> some SwiftUICore.View
public func searchToolbarBehavior(_ behavior: SwiftUI.SearchToolbarBehavior) -> some SwiftUICore.View
public func tabBarMinimizeBehavior(_ behavior: SwiftUI.TabBarMinimizeBehavior) -> some SwiftUICore.View
public func tabViewBottomAccessory<Content>(@SwiftUICore.ViewBuilder content: () -> Content) -> some SwiftUICore.View where Content : SwiftUICore.View
public func tabViewBottomAccessory<Content>(isEnabled: Swift.Bool, @SwiftUICore.ViewBuilder content: () -> Content) -> some SwiftUICore.View where Content : SwiftUICore.View
public func tabViewSearchActivation(_ activation: SwiftUI.TabSearchActivation) -> some SwiftUICore.View
public protocol NavigationTransition {
public static func == (a: SwiftUI.ScrollEdgeEffectStyle, b: SwiftUI.ScrollEdgeEffectStyle) -> Swift.Bool
public static func == (a: SwiftUI.SearchToolbarBehavior, b: SwiftUI.SearchToolbarBehavior) -> Swift.Bool
public static func == (a: SwiftUI.TabBarMinimizeBehavior, b: SwiftUI.TabBarMinimizeBehavior) -> Swift.Bool
public static func _makeToolbar(content: SwiftUICore._GraphValue<SwiftUI.ToolbarSpacer>, inputs: SwiftUI._ToolbarInputs) -> SwiftUI._ToolbarOutputs
public static func interactive(timingCurve: SwiftUICore.UnitCurve = .easeInOut) -> SwiftUI.ScrollTransitionConfiguration
public static func zoom(sourceID: some Hashable, in namespace: SwiftUICore.Namespace.ID) -> SwiftUI.ZoomNavigationTransition
public static let automatic: SwiftUI.TabBarMinimizeBehavior
public static let never: SwiftUI.TabBarMinimizeBehavior
public static let onScrollDown: SwiftUI.TabBarMinimizeBehavior
public static let onScrollUp: SwiftUI.TabBarMinimizeBehavior
public static var automatic: SwiftUI.AutomaticNavigationTransition {
public static var automatic: SwiftUI.ScrollEdgeEffectStyle {
public static var automatic: SwiftUI.SearchToolbarBehavior {
public static var hard: SwiftUI.ScrollEdgeEffectStyle {
public static var minimize: SwiftUI.SearchToolbarBehavior {
public static var soft: SwiftUI.ScrollEdgeEffectStyle {
public struct AutomaticNavigationTransition : SwiftUI.NavigationTransition {
public struct GlassButtonStyle : SwiftUI.PrimitiveButtonStyle {
public struct GlassProminentButtonStyle : SwiftUI.PrimitiveButtonStyle {
public struct ScrollEdgeEffectStyle : Swift.Hashable, Swift.Sendable {
public struct SearchToolbarBehavior : Swift.Hashable, Swift.Sendable {
public struct TabBarMinimizeBehavior : Swift.Hashable, Swift.Sendable {
public struct ToolbarSpacer : SwiftUI.ToolbarContent, SwiftUI.CustomizableToolbarContent {
public struct ZoomNavigationTransition : SwiftUI.NavigationTransition {
public struct _NavigationTransitionInputs : Swift.Sendable {
public struct _NavigationTransitionOutputs : Swift.Sendable {
public var tabViewBottomAccessoryPlacement: SwiftUI.TabViewBottomAccessoryPlacement? {
```

## UIKit (vidrio para ControlsOverlayView)
```objc
#if (defined(USE_UIKIT_PUBLIC_HEADERS) && USE_UIKIT_PUBLIC_HEADERS) || !__has_include(<UIKitCore/UIGlassEffect.h>)
#import <UIKitCore/UIGlassEffect.h>
+ (UIGlassEffect *)effectWithStyle:(UIGlassEffectStyle)style NS_SWIFT_NAME(init(style:));
@interface UIGlassContainerEffect : UIVisualEffect
@interface UIGlassEffect : UIVisualEffect
@property (nonatomic) CGFloat spacing;
@property (nonatomic, copy, nullable) UIColor *tintColor;
@property (nonatomic, getter=isInteractive) BOOL interactive;
UIGlassEffectStyleClear
UIGlassEffectStyleRegular,
typedef NS_ENUM(NSInteger, UIGlassEffectStyle) {
}) NS_SWIFT_NAME(UIGlassEffect.Style);
```

## UIKit: forma de las vistas (N2, `ControlVisualView`)
Del `UIKit.swiftinterface` del SDK de iOS 26.5 (Xcode 26): la vista de vidrio toma su forma con `cornerConfiguration` en lugar de `layer.cornerRadius` + `clipsToBounds` (que recortaba el borde del vidrio).
```swift
@MainActor public var cornerConfiguration: UIKit.UICornerConfiguration { get set }   // extension UIView
public struct UICornerConfiguration {
  public static func capsule(maximumRadius: Swift.Double? = nil) -> UIKit.UICornerConfiguration
  public static func corners(radius: UIKit.UICornerRadius) -> UIKit.UICornerConfiguration
  public static func uniformCorners(radius: UIKit.UICornerRadius) -> UIKit.UICornerConfiguration
}
```
