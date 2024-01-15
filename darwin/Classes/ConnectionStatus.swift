import Foundation
import NetworkExtension

/// ConnectionStatus returned to Dart
enum ConnectionStatus: String {
    case connected
    case disconnected
    case connecting
    case disconnecting
    case unknown
    
    static func fromNEVPNStatus(ns: NEVPNStatus) -> ConnectionStatus {
        switch ns {
        case .connected: return ConnectionStatus.connected
        case .disconnected: return ConnectionStatus.disconnected
        case .connecting: return ConnectionStatus.connecting
        case .disconnecting: return ConnectionStatus.disconnecting
        default: return ConnectionStatus.unknown
        }
    }
    
    func string() -> String {
        return self.rawValue
    }
}
