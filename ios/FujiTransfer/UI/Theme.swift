import SwiftUI
import UIKit

/// The Android app's look: paper ground, warm ink, one orange accent, Inter Tight for the
/// interface and JetBrains Mono for camera data. Follows the phone's light/dark setting.
enum Film {
    static let paper = Color(light: 0xF3F0EA, dark: 0x0E0E0E)
    static let ink = Color(light: 0x161512, dark: 0xF2EFEA)
    static let inkSoft = Color(light: 0x6E6A62, dark: 0xA8A49C)
    static let hairline = Color(light: 0xDAD5CB, dark: 0x2A2A2A)
    /// Cards, chips, icon buttons.
    static let card = Color(light: 0xFFFFFF, dark: 0x161616)
    static let raised = Color(light: 0xE8E3DA, dark: 0x242424)
    static let frameEmpty = Color(light: 0xE4DFD6, dark: 0x1E1E1E)
    static let accent = Color(hex: 0xFF9D28)
    /// The accent as text on the page, dark enough to read on the light ground.
    static let accentText = Color(light: 0xA85400, dark: 0xFF9D28)
    static let onAccent = Color(hex: 0x140C02)
    static let error = Color(light: 0xB3261E, dark: 0xF09595)
    static let errorBg = Color(light: 0xF9DEDC, dark: 0x2A1515)

    /// Interface and headings (falls back to the system font if Inter Tight isn't bundled).
    static func sans(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom("Inter Tight", size: size).weight(weight)
    }

    /// Camera data: file names, counts, speeds.
    static func mono(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        .custom("JetBrains Mono", size: size).weight(weight)
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }

    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { traits in
            let v = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                           blue: CGFloat(v & 0xFF) / 255, alpha: 1)
        })
    }
}

/// A rounded chip: dark-on-light when selected, outlined otherwise.
struct Pill: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Film.sans(15, .medium))
                .foregroundStyle(selected ? Film.paper : Film.ink)
                .padding(.horizontal, 16)
                .frame(height: 36)
                .background(Capsule().fill(selected ? Film.ink : Film.card))
                .overlay(Capsule().stroke(selected ? Color.clear : Film.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// The big round-ended button: orange when [primary], outlined otherwise.
struct PillButton: View {
    let label: String
    let primary: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Film.sans(17, .semibold))
                .foregroundStyle(primary ? Film.onAccent : Film.ink)
                .frame(maxWidth: .infinity)
                .frame(height: primary ? 56 : 52)
                .background(Capsule().fill(primary ? Film.accent : Color.clear))
                .overlay(Capsule().stroke(primary ? Color.clear : Film.hairline, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}
