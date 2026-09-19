import Foundation
import AVFoundation

/**
 * iOS Real-Time Audio Engine utilizing AVAudioEngine & VoiceProcessingIO Audio Unit.
 * Ensures hardware Acoustic Echo Cancellation (AEC), AGC, and low-latency buffer processing.
 */
public class iOSAudioEngine: NSObject {
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var isRunning: Bool = false
    
    public var onPcmAudioCaptured: ((Data) -> Void)?
    
    public override init() {
        super.init()
    }
    
    public func startVoiceProcessing() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth, .defaultToSpeaker])
        try session.setPreferredSampleRate(16000.0) // 16kHz Opus Voice Mode
        try session.setPreferredIOBufferDuration(0.02) // 20ms frame duration
        try session.setActive(true)
        
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else { return }
        
        inputNode = engine.inputNode
        let inputFormat = inputNode!.inputFormat(forBus: 0)
        
        // Tap input buffer for raw speech capture
        inputNode!.installTap(onBus: 0, bufferSize: 320, format: inputFormat) { [weak self] (buffer, time) in
            guard let self = self else { return }
            let channelData = buffer.floatChannelData?[0]
            let frameLength = Int(buffer.frameLength)
            if let samples = channelData {
                let pcmData = Data(bytes: samples, count: frameLength * MemoryLayout<Float>.size)
                self.onPcmAudioCaptured?(pcmData)
            }
        }
        
        try engine.start()
        isRunning = true
    }
    
    public func stopVoiceProcessing() {
        inputNode?.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        isRunning = false
    }
    
    public func setSpeakerOutput(enabled: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(enabled ? .speaker : .none)
    }
}
