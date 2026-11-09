import AVFoundation
import AudioToolbox
import Foundation
import UIKit

private let scannerScannedEvent = "Native\\Mobile\\Events\\Scanner\\CodeScanned"
private let scannerCancelledEvent = "Native\\Mobile\\Events\\Scanner\\ScannerCancelled"

/// In continuous mode the same value is ignored while it stays in view and until
/// it has been out of view this long, so one box held in front of the camera
/// counts once but showing it again counts again.
private let scannerRepeatWindowSeconds: TimeInterval = 1.5

/// Namespace: "Scanner.*"
///
/// Mirrors `ScannerFunctions` on Android: the call returns at once, a full-screen
/// scanner opens, and the answer comes back as CodeScanned {data, format, id} or
/// ScannerCancelled {cancelled, reason, id}, carrying the caller's id.
enum ScannerFunctions {

    /// Parameters: prompt, continuous (bool), formats (qr, ean13, ean8, code128,
    /// code39, upca, upce, all), id, event.
    class Scan: BridgeFunction {
        func execute(parameters: [String: Any]) throws -> [String: Any] {
            let prompt = (parameters["prompt"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Scan QR Code"
            let continuous = (parameters["continuous"] as? Bool) ?? false
            var formats = ((parameters["formats"] as? [Any]) ?? []).compactMap { ($0 as? String)?.lowercased() }
            if formats.isEmpty {
                formats = ["qr"]
            }
            let id = parameters["id"] as? String
            let event = (parameters["event"] as? String) ?? scannerScannedEvent

            let request = ScannerRequest(prompt: prompt, continuous: continuous, formats: formats, id: id, event: event)

            DispatchQueue.main.async {
                ScannerPresenter.start(request)
            }

            return BridgeResponse.success(data: [:])
        }
    }
}

struct ScannerRequest {
    let prompt: String
    let continuous: Bool
    let formats: [String]
    let id: String?
    let event: String
}

/// Asks for the camera when needed and presents the scanner. Main thread only.
enum ScannerPresenter {
    private static weak var current: ScannerViewController?

    static func start(_ request: ScannerRequest) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            present(request)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    if granted {
                        present(request)
                    } else {
                        ScannerEvents.cancelled(id: request.id, reason: "permission_denied")
                    }
                }
            }
        default:
            ScannerEvents.cancelled(id: request.id, reason: "permission_denied")
        }
    }

    private static func present(_ request: ScannerRequest) {
        if current != nil {
            // One scanner at a time; this caller gets its answer at once.
            ScannerEvents.cancelled(id: request.id, reason: "cancelled")
            return
        }

        guard AVCaptureDevice.default(for: .video) != nil, let top = topViewController() else {
            ScannerEvents.cancelled(id: request.id, reason: "unavailable")
            return
        }

        let scanner = ScannerViewController(request: request)
        scanner.modalPresentationStyle = .fullScreen
        current = scanner
        top.present(scanner, animated: true)
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController

        while let presented = top?.presentedViewController {
            top = presented
        }

        return top
    }
}

enum ScannerEvents {
    static func scanned(event: String, id: String?, data: String, format: String) {
        send(event, ["data": data, "format": format, "id": id])
    }

    static func cancelled(id: String?, reason: String) {
        send(scannerCancelledEvent, ["cancelled": true, "reason": reason, "id": id])
    }

    private static func send(_ event: String, _ payload: [String: Any?]) {
        // Drop absent values instead of sending null ids.
        let clean = payload.compactMapValues { $0 }
        LaravelBridge.shared.send?(event, clean)
    }
}

/// The full-screen scanner: camera preview, the caller's prompt, the last value
/// scanned, a torch toggle and a Done/Cancel button.
final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {

    private let request: ScannerRequest
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.smronju.plugins.scanner.session")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var device: AVCaptureDevice?

    private var finished = false
    private var scannedCount = 0

    /// value -> when it was last seen, for the continuous-mode repeat window.
    private var lastSeen: [String: Date] = [:]

    private let promptLabel = UILabel()
    private let lastScannedLabel = ScannerPaddedLabel()
    private let doneButton = UIButton(type: .system)
    private let torchButton = UIButton(type: .system)
    private let haptics = UINotificationFeedbackGenerator()

    init(request: ScannerRequest) {
        self.request = request
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    override var prefersStatusBarHidden: Bool { true }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildUI()
        configureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        setTorch(false)
        sessionQueue.async { [session] in
            if session.isRunning {
                session.stopRunning()
            }
        }
    }

    // MARK: - Camera

    private func configureSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            close(reason: "unavailable")
            return
        }

        self.device = device
        session.beginConfiguration()
        if session.canSetSessionPreset(.hd1280x720) {
            session.sessionPreset = .hd1280x720
        }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            close(reason: "unavailable")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)

        // Must be set after the output joins the session, and only to available types.
        let wanted = Self.metadataTypes(for: request.formats)
        output.metadataObjectTypes = wanted.filter { output.availableMetadataObjectTypes.contains($0) }
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer

        torchButton.isHidden = !device.hasTorch

        sessionQueue.async { [session] in
            session.startRunning()
        }
    }

    static func metadataTypes(for formats: [String]) -> [AVMetadataObject.ObjectType] {
        let map: [String: [AVMetadataObject.ObjectType]] = [
            "qr": [.qr],
            "ean13": [.ean13],
            "ean8": [.ean8],
            "code128": [.code128],
            "code39": [.code39, .code39Mod43],
            // iOS reads UPC-A as EAN-13 with a leading 0; see formatName(for:data:).
            "upca": [.ean13],
            "upce": [.upce],
        ]

        if formats.contains("all") {
            return [.qr, .ean13, .ean8, .code128, .code39, .code39Mod43, .code93, .upce, .pdf417, .aztec, .dataMatrix, .itf14, .interleaved2of5]
        }

        var types: [AVMetadataObject.ObjectType] = []
        for format in formats {
            for type in map[format] ?? [] where !types.contains(type) {
                types.append(type)
            }
        }

        return types.isEmpty ? [.qr] : types
    }

    /// The lowercase name CodeScanned reports, the same names the caller asks with.
    /// A 13-digit EAN starting with 0 is a UPC-A; when the caller asked for upca (or
    /// all) it is reported as upca with its 12 digits, as Android's ML Kit does.
    static func formatName(for type: AVMetadataObject.ObjectType, data: String, requested: [String]) -> (String, String) {
        switch type {
        case .qr: return ("qr", data)
        case .ean13:
            if data.count == 13, data.hasPrefix("0"), requested.contains("upca") || requested.contains("all") {
                return ("upca", String(data.dropFirst()))
            }
            return ("ean13", data)
        case .ean8: return ("ean8", data)
        case .code128: return ("code128", data)
        case .code39, .code39Mod43: return ("code39", data)
        case .code93: return ("code93", data)
        case .upce: return ("upce", data)
        case .pdf417: return ("pdf417", data)
        case .aztec: return ("aztec", data)
        case .dataMatrix: return ("datamatrix", data)
        case .itf14, .interleaved2of5: return ("itf", data)
        default: return ("unknown", data)
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !finished else {
            return
        }

        let now = Date()
        lastSeen = lastSeen.filter { now.timeIntervalSince($0.value) < scannerRepeatWindowSeconds }

        for object in metadataObjects {
            guard let code = object as? AVMetadataMachineReadableCodeObject,
                  let raw = code.stringValue, !raw.isEmpty else {
                continue
            }

            let (format, data) = Self.formatName(for: code.type, data: raw, requested: request.formats)

            if !request.continuous {
                finished = true
                feedback()
                ScannerEvents.scanned(event: request.event, id: request.id, data: data, format: format)
                dismissScanner()
                return
            }

            let seenRecently = lastSeen[data] != nil
            lastSeen[data] = now

            if seenRecently {
                continue
            }

            scannedCount += 1
            feedback()
            lastScannedLabel.text = "\(scannedCount) · \(data)"
            lastScannedLabel.isHidden = false
            ScannerEvents.scanned(event: request.event, id: request.id, data: data, format: format)
        }
    }

    private func feedback() {
        AudioServicesPlaySystemSound(1057)
        haptics.notificationOccurred(.success)
    }

    // MARK: - Torch

    private var torchOn = false

    @objc private func toggleTorch() {
        setTorch(!torchOn)
    }

    private func setTorch(_ on: Bool) {
        guard let device = device, device.hasTorch, torchOn != on else {
            return
        }

        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
            torchOn = on
            torchButton.alpha = on ? 1 : 0.7
        } catch {
            NSLog("Scanner: torch unavailable: \(error)")
        }
    }

    // MARK: - Closing

    @objc private func doneTapped() {
        close(reason: request.continuous && scannedCount > 0 ? "done" : "cancelled")
    }

    /// Closes without (another) code: ScannerCancelled exactly once.
    private func close(reason: String) {
        guard !finished else {
            return
        }

        finished = true
        ScannerEvents.cancelled(id: request.id, reason: reason)
        dismissScanner()
    }

    private func dismissScanner() {
        DispatchQueue.main.async { [weak self] in
            self?.presentingViewController?.dismiss(animated: true)
        }
    }

    // MARK: - UI

    private func buildUI() {
        let frame = UIView()
        frame.layer.borderColor = UIColor.white.cgColor
        frame.layer.borderWidth = 3
        frame.layer.cornerRadius = 16
        frame.isUserInteractionEnabled = false

        promptLabel.text = request.prompt
        promptLabel.textColor = .white
        promptLabel.font = .systemFont(ofSize: 18, weight: .medium)
        promptLabel.textAlignment = .center
        promptLabel.numberOfLines = 0
        promptLabel.layer.shadowColor = UIColor.black.cgColor
        promptLabel.layer.shadowRadius = 4
        promptLabel.layer.shadowOpacity = 0.9
        promptLabel.layer.shadowOffset = .zero

        lastScannedLabel.textColor = .white
        lastScannedLabel.font = .boldSystemFont(ofSize: 16)
        lastScannedLabel.textAlignment = .center
        lastScannedLabel.backgroundColor = UIColor(red: 0.09, green: 0.64, blue: 0.29, alpha: 0.85)
        lastScannedLabel.layer.cornerRadius = 18
        lastScannedLabel.clipsToBounds = true
        lastScannedLabel.isHidden = true

        doneButton.setTitle(request.continuous ? "Done" : "Cancel", for: .normal)
        doneButton.setTitleColor(.black, for: .normal)
        doneButton.titleLabel?.font = .boldSystemFont(ofSize: 17)
        doneButton.backgroundColor = .white
        doneButton.layer.cornerRadius = 26
        doneButton.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)

        torchButton.setTitle("Torch", for: .normal)
        torchButton.setTitleColor(.white, for: .normal)
        torchButton.titleLabel?.font = .boldSystemFont(ofSize: 17)
        torchButton.backgroundColor = UIColor(white: 1, alpha: 0.3)
        torchButton.layer.cornerRadius = 26
        torchButton.alpha = 0.7
        torchButton.isHidden = true
        torchButton.addTarget(self, action: #selector(toggleTorch), for: .touchUpInside)

        let buttons = UIStackView(arrangedSubviews: [torchButton, doneButton])
        buttons.axis = .horizontal
        buttons.spacing = 16

        for subview in [frame, promptLabel, lastScannedLabel, buttons] as [UIView] {
            subview.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(subview)
        }

        let guide = view.safeAreaLayoutGuide

        NSLayoutConstraint.activate([
            frame.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            frame.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            frame.widthAnchor.constraint(equalToConstant: 280),
            frame.heightAnchor.constraint(equalToConstant: 200),

            promptLabel.topAnchor.constraint(equalTo: guide.topAnchor, constant: 24),
            promptLabel.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 24),
            promptLabel.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -24),

            buttons.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -32),
            buttons.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            doneButton.widthAnchor.constraint(equalToConstant: 160),
            doneButton.heightAnchor.constraint(equalToConstant: 52),
            torchButton.widthAnchor.constraint(equalToConstant: 110),
            torchButton.heightAnchor.constraint(equalToConstant: 52),

            lastScannedLabel.bottomAnchor.constraint(equalTo: buttons.topAnchor, constant: -20),
            lastScannedLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            lastScannedLabel.leadingAnchor.constraint(greaterThanOrEqualTo: guide.leadingAnchor, constant: 24),
            lastScannedLabel.trailingAnchor.constraint(lessThanOrEqualTo: guide.trailingAnchor, constant: -24),
            lastScannedLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 36),
        ])
    }
}

/// A label with room around its text, for the last-scanned pill.
final class ScannerPaddedLabel: UILabel {
    private let insets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.inset(by: insets))
    }

    override var intrinsicContentSize: CGSize {
        let size = super.intrinsicContentSize
        return CGSize(width: size.width + insets.left + insets.right, height: size.height + insets.top + insets.bottom)
    }
}
