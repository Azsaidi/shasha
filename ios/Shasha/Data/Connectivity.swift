import Foundation
import Network

/// Whether the device has internet, published for the offline banner and downloads.
final class Connectivity: ObservableObject {
    static let shared = Connectivity()
    @Published private(set) var isOnline = true
    private let monitor = NWPathMonitor()

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let online = path.status == .satisfied
            DispatchQueue.main.async {
                guard let self, self.isOnline != online else { return }
                self.isOnline = online
                if online { Downloads.shared.kick() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "connectivity"))
    }
}
