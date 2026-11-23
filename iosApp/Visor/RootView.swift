import SwiftUI
import UniformTypeIdentifiers
import VisorCore

struct RootView: View {
    @EnvironmentObject private var glasses: Glasses

    var body: some View {
        NavigationStack {
            Group {
                if let pair = glasses.pairs.first(where: { $0.linked }) {
                    StatusView(pair: pair)
                } else if let pair = glasses.pairs.first {
                    NearbyView(pair: pair)
                } else {
                    ImportView()
                }
            }
            .navigationTitle("Visor")
        }
        .alert(
            "Visor",
            isPresented: Binding(get: { glasses.problem != nil }, set: { if !$0 { glasses.problem = nil } }),
            actions: { Button("OK") {} },
            message: { Text(glasses.problem ?? "") }
        )
    }
}

/// No pair yet: bring in the owner key the pair was set up with.
struct ImportView: View {
    @EnvironmentObject private var glasses: Glasses
    @State private var text = ""
    @State private var picking = false

    var body: some View {
        Form {
            Section("Identity") {
                TextEditor(text: $text)
                    .font(.system(.footnote, design: .monospaced))
                    .frame(minHeight: 160)
                Button("Add") { glasses.importIdentity(text) }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("Open file") { picking = true }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.json, .plainText]) { result in
            guard case .success(let url) = result else { return }
            let opened = url.startAccessingSecurityScopedResource()
            defer { if opened { url.stopAccessingSecurityScopedResource() } }
            if let json = try? String(contentsOf: url, encoding: .utf8) { glasses.importIdentity(json) }
        }
    }
}

/// A key and no glasses yet: iOS names nearby glasses by its own id, so the user picks them once.
struct NearbyView: View {
    @EnvironmentObject private var glasses: Glasses
    let pair: GlassesPair

    var body: some View {
        List {
            Section(pair.serial) {
                if glasses.nearby.isEmpty {
                    HStack {
                        ProgressView()
                        Text("Looking for glasses")
                    }
                }
                ForEach(glasses.nearby, id: \.identifier) { found in
                    Button {
                        glasses.link(pair, to: found)
                    } label: {
                        HStack {
                            Text(found.name)
                            Spacer()
                            Text("\(found.rssi) dBm").foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section {
                Button("Forget", role: .destructive) { glasses.forget(pair) }
            }
        }
        .onAppear { glasses.scan() }
        .onDisappear { glasses.stopScan() }
    }
}

/// The pair, the link and what the glasses report.
struct StatusView: View {
    @EnvironmentObject private var glasses: Glasses
    let pair: GlassesPair

    var body: some View {
        let state = glasses.state
        List {
            Section(pair.serial) {
                row("Link", state.map { linkName($0.link) } ?? "Off")
                if let reason = state?.reason, !reason.isEmpty { row("Reason", reason) }
                if let state, state.battery >= 0 { row("Battery", "\(state.battery)%" + (state.charging ? ", charging" : "")) }
                if let state, state.caseBattery >= 0 { row("Case", "\(state.caseBattery)%") }
                if let state, !state.model.isEmpty { row("Model", state.model) }
                if let state, !state.build.isEmpty { row("Firmware", state.build) }
            }
            Section {
                if state?.link == "READY" || state?.link == "CONNECTING" {
                    Button("Disconnect") { glasses.disconnect() }
                } else {
                    Button("Connect") { glasses.connect() }
                }
                Button("Forget", role: .destructive) { glasses.forget(pair) }
            }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundStyle(.secondary)
        }
    }

    private func linkName(_ link: String) -> String {
        switch link {
        case "READY": return "Connected"
        case "CONNECTING", "LINK_SETUP", "TRUST": return "Connecting"
        case "LOST": return "Lost, trying again"
        default: return "Off"
        }
    }
}
