import SwiftUI
import VisorCore

@main
struct VisorApp: App {
    @StateObject private var glasses = Glasses()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(glasses)
        }
    }
}

/// The shared Kotlin client, as SwiftUI observes it. Kotlin calls back on the main queue.
@MainActor
final class Glasses: ObservableObject {
    let client: VisorClient
    @Published private(set) var state: VisorState?
    @Published private(set) var pairs: [GlassesPair] = []
    @Published private(set) var nearby: [NearbyGlasses] = []
    @Published var problem: String?
    private var watch: Watch?

    init() {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        client = VisorClient(appVersion: version)
        pairs = client.pairs()
        watch = client.watch { [weak self] state in MainActor.assumeIsolated { self?.state = state } }
        if pairs.contains(where: { $0.linked }) { client.start() }
    }

    func importIdentity(_ json: String) {
        do {
            _ = try client.importIdentity(json: json)
            pairs = client.pairs()
        } catch {
            problem = "That is not an identity bundle."
        }
    }

    func scan() {
        nearby = []
        client.scan(
            onFound: { [weak self] found in
                MainActor.assumeIsolated {
                    guard let self, !self.nearby.contains(where: { $0.identifier == found.identifier }) else { return }
                    self.nearby.append(found)
                }
            },
            onError: { [weak self] message in MainActor.assumeIsolated { self?.problem = message } }
        )
    }

    func stopScan() { client.stopScan() }

    func link(_ pair: GlassesPair, to found: NearbyGlasses) {
        client.stopScan()
        client.link(serial: pair.serial, identifier: found.identifier)
        pairs = client.pairs()
        client.start()
    }

    func connect() { client.start() }

    func disconnect() { client.stop() }

    func forget(_ pair: GlassesPair) {
        client.stop()
        client.forget(serial: pair.serial)
        pairs = client.pairs()
    }
}
