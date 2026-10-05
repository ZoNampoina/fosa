import SwiftUI
public enum FosaColors {
    public static let background = Color(red: 13/255, green: 18/255, blue: 24/255)
    public static let surface = Color(red: 22/255, green: 30/255, blue: 39/255)
    public static let elevated = Color(red: 30/255, green: 41/255, blue: 53/255)
    public static let accent = Color(red: 144/255, green: 233/255, blue: 187/255)
    public static let text = Color(red: 240/255, green: 244/255, blue: 247/255)
    public static let secondary = Color(red: 158/255, green: 173/255, blue: 188/255)
}
public enum FosaSpacing { public static let small: CGFloat = 8; public static let medium: CGFloat = 16; public static let large: CGFloat = 24 }
public struct FosaNativePreparationView: View {
    public init() {}
    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FosaSpacing.large) {
                HStack { VStack(alignment: .leading) { Text("FOSA").font(.title2.bold()).tracking(2); Text("MOBILE / iOS").font(.caption.bold()).foregroundStyle(FosaColors.secondary) }; Spacer(); Text("PREPARATION").font(.caption.bold()).foregroundStyle(FosaColors.accent) }
                Text("Your stage.\nYour connection.").font(.largeTitle.weight(.semibold))
                VStack(alignment: .leading, spacing: 16) {
                    Text("Native iOS audio is not released yet.").font(.headline)
                    Text("The common LAN protocol and visual identity are prepared. Use Web fallback while keeping Safari open.").foregroundStyle(FosaColors.secondary)
                    Link("OPEN WEB FALLBACK", destination: URL(string: "https://zonampoina.github.io/fosa/mobile/")!).font(.headline).foregroundStyle(FosaColors.accent)
                }.padding(20).frame(maxWidth: .infinity, alignment: .leading).background(FosaColors.surface).clipShape(RoundedRectangle(cornerRadius: 12))
                Text("LOCAL NETWORK · INTERNET NOT REQUIRED").font(.caption.bold()).foregroundStyle(FosaColors.secondary)
            }.padding(24)
        }.background(FosaColors.background).foregroundStyle(FosaColors.text)
    }
}
