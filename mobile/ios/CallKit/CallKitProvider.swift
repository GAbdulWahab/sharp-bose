import Foundation
import CallKit
import AVFoundation

/**
 * Native Apple CallKit Provider
 * Integrates incoming/outgoing offline peer calls into the native iOS lockscreen and calling interface.
 */
public class CallKitProvider: NSObject, CXProviderDelegate {
    private let provider: CXProvider
    private let callController = CXCallController()
    
    public var onCallAnswered: ((UUID) -> Void)?
    public var onCallEnded: ((UUID) -> Void)?
    public var onCallMuteToggled: ((UUID, Bool) -> Void)?
    
    public override init() {
        let config = CXProviderConfiguration()
        config.localizedName = "Offline Mesh Call"
        config.supportsVideo = false
        config.maximumCallsPerCallGroup = 1
        config.supportedHandleTypes = [.generic]
        
        provider = CXProvider(configuration: config)
        super.init()
        provider.setDelegate(self, queue: nil)
    }
    
    public func reportIncomingCall(uuid: UUID, callerName: String, completion: @escaping (Error?) -> Void) {
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName)
        update.hasVideo = false
        update.localizedCallerName = "\(callerName) (Offline P2P)"
        
        provider.reportNewIncomingCall(with: uuid, update: update) { error in
            completion(error)
        }
    }
    
    public func startOutgoingCall(uuid: UUID, recipientName: String) {
        let handle = CXHandle(type: .generic, value: recipientName)
        let action = CXStartCallAction(call: uuid, handle: handle)
        let transaction = CXTransaction(action: action)
        callController.request(transaction) { error in
            if let error = error {
                print("[CallKit] Error starting call: \(error.localizedDescription)")
            }
        }
    }
    
    public func endCall(uuid: UUID) {
        let action = CXEndCallAction(call: uuid)
        let transaction = CXTransaction(action: action)
        callController.request(transaction) { _ in }
    }
    
    // MARK: - CXProviderDelegate
    public func providerDidReset(_ provider: CXProvider) {
        // Stop audio engine and cleanup
    }
    
    public func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        onCallAnswered?(action.callUUID)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        onCallEnded?(action.callUUID)
        action.fulfill()
    }
    
    public func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        onCallMuteToggled?(action.callUUID, action.isMuted)
        action.fulfill()
    }
}
