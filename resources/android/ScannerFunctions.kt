package com.smronju.plugins.scanner

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Namespace: "Scanner.*"
 *
 * Implements the bridge function Native\Mobile\PendingScanner (nativephp/mobile
 * core) calls but core registers on neither platform. The call returns at once;
 * a full-screen scanner opens and the answer comes back as events carrying the
 * caller's id: CodeScanned {data, format, id} and ScannerCancelled
 * {cancelled, reason, id}.
 */
object ScannerFunctions {

    const val SCANNED_EVENT = "Native\\Mobile\\Events\\Scanner\\CodeScanned"
    const val CANCELLED_EVENT = "Native\\Mobile\\Events\\Scanner\\ScannerCancelled"

    /**
     * Parameters: prompt, continuous (bool), formats (qr, ean13, ean8, code128,
     * code39, upca, upce, all), id, event.
     */
    class Scan(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val prompt = (parameters["prompt"] as? String)?.takeIf { it.isNotBlank() } ?: "Scan QR Code"
            val continuous = parameters["continuous"] as? Boolean ?: false
            val formats = stringList(parameters["formats"]).ifEmpty { listOf("qr") }
            val id = parameters["id"] as? String
            val event = (parameters["event"] as? String) ?: SCANNED_EVENT

            Handler(Looper.getMainLooper()).post {
                try {
                    ScannerFragment.show(activity, prompt, continuous, formats, id, event)
                } catch (e: Exception) {
                    Log.e(ScannerFragment.TAG, "Could not open the scanner: ${e.message}", e)
                    dispatchCancelled(activity, id, "unavailable")
                }
            }

            return emptyMap()
        }
    }

    private fun stringList(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it as? String }
        is Array<*> -> value.mapNotNull { it as? String }
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it, null) }
        is String -> listOf(value)
        else -> emptyList()
    }.map { it.lowercase() }

    fun dispatchCancelled(activity: FragmentActivity, id: String?, reason: String) {
        val payload = JSONObject().apply {
            put("cancelled", true)
            put("reason", reason)
            id?.let { put("id", it) }
        }

        NativeActionCoordinator.dispatchEvent(activity, CANCELLED_EVENT, payload.toString())
    }

    /** ML Kit format constants for the names core's PendingScanner::formats() accepts. */
    fun mlKitFormats(names: List<String>): Int {
        if (names.contains("all")) {
            return Barcode.FORMAT_ALL_FORMATS
        }

        val map = mapOf(
            "qr" to Barcode.FORMAT_QR_CODE,
            "ean13" to Barcode.FORMAT_EAN_13,
            "ean8" to Barcode.FORMAT_EAN_8,
            "code128" to Barcode.FORMAT_CODE_128,
            "code39" to Barcode.FORMAT_CODE_39,
            "upca" to Barcode.FORMAT_UPC_A,
            "upce" to Barcode.FORMAT_UPC_E,
        )

        val flags = names.mapNotNull { map[it] }.fold(0) { acc, flag -> acc or flag }

        return if (flags == 0) Barcode.FORMAT_QR_CODE else flags
    }

    /** The lowercase name CodeScanned reports, the same names the caller asks with. */
    fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "qr"
        Barcode.FORMAT_EAN_13 -> "ean13"
        Barcode.FORMAT_EAN_8 -> "ean8"
        Barcode.FORMAT_CODE_128 -> "code128"
        Barcode.FORMAT_CODE_39 -> "code39"
        Barcode.FORMAT_UPC_A -> "upca"
        Barcode.FORMAT_UPC_E -> "upce"
        Barcode.FORMAT_CODE_93 -> "code93"
        Barcode.FORMAT_CODABAR -> "codabar"
        Barcode.FORMAT_ITF -> "itf"
        Barcode.FORMAT_DATA_MATRIX -> "datamatrix"
        Barcode.FORMAT_PDF417 -> "pdf417"
        Barcode.FORMAT_AZTEC -> "aztec"
        else -> "unknown"
    }
}

/**
 * The full-screen scanner: a CameraX preview with ML Kit (bundled model, so no
 * Google Play services needed) analysing frames, the caller's prompt, the last
 * value scanned, a torch toggle and a Done/Cancel button. A DialogFragment over
 * the app's own activity, so no manifest entry is needed and the PHP side never
 * goes to the background.
 */
class ScannerFragment : DialogFragment() {

    companion object {
        const val TAG = "SmronjuScanner"
        private const val FRAGMENT_TAG = "SmronjuScannerFragment"

        /**
         * In continuous mode the same value is ignored while it stays in view and
         * until it has been out of view this long, so one box held in front of the
         * camera counts once but showing it again counts again.
         */
        const val REPEAT_WINDOW_MS = 1_500L

        fun show(activity: FragmentActivity, prompt: String, continuous: Boolean, formats: List<String>, id: String?, event: String) {
            val fm = activity.supportFragmentManager

            if (fm.findFragmentByTag(FRAGMENT_TAG) != null) {
                // One scanner at a time; this caller gets its answer at once.
                Log.w(TAG, "A scanner is already open; cancelling the new request")
                ScannerFunctions.dispatchCancelled(activity, id, "cancelled")
                return
            }

            val fragment = ScannerFragment().apply {
                arguments = Bundle().apply {
                    putString("prompt", prompt)
                    putBoolean("continuous", continuous)
                    putStringArrayList("formats", ArrayList(formats))
                    putString("id", id)
                    putString("event", event)
                }
            }

            fm.beginTransaction().add(fragment, FRAGMENT_TAG).commitAllowingStateLoss()
        }
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<String>

    private var prompt = "Scan QR Code"
    private var continuous = false
    private var formats: List<String> = listOf("qr")
    private var id: String? = null
    private var event = ScannerFunctions.SCANNED_EVENT

    private var finished = false
    private var scannedCount = 0

    /** value -> when it was last seen, for the continuous-mode repeat window. */
    private val lastSeen = HashMap<String, Long>()

    private var camera: Camera? = null
    private var torchOn = false
    private var analysisExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    private var tone: ToneGenerator? = null

    private lateinit var previewView: PreviewView
    private lateinit var lastScannedView: TextView
    private lateinit var torchButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        isCancelable = true

        arguments?.let { args ->
            prompt = args.getString("prompt") ?: prompt
            continuous = args.getBoolean("continuous", false)
            formats = args.getStringArrayList("formats")?.toList() ?: formats
            id = args.getString("id")
            event = args.getString("event") ?: event
        }

        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else close("permission_denied")
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setCanceledOnTouchOutside(false)

        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        previewView = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // A frame to aim with; ML Kit reads the whole picture regardless.
        val viewfinder = View(context).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), Color.WHITE)
                cornerRadius = dp(16).toFloat()
                setColor(Color.TRANSPARENT)
            }
        }
        root.addView(viewfinder, FrameLayout.LayoutParams(dp(280), dp(200), Gravity.CENTER))

        val promptView = TextView(context).apply {
            text = prompt
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(dp(24), dp(16), dp(24), dp(16))
        }
        root.addView(promptView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = dp(48)
        })

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), 0, dp(24), dp(40))
        }

        lastScannedView = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.argb(200, 22, 163, 74))
            }
            visibility = View.INVISIBLE
        }
        bottom.addView(lastScannedView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(20)
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        torchButton = pillButton("Torch", Color.argb(140, 255, 255, 255), Color.WHITE).apply {
            visibility = View.GONE
            setOnClickListener { toggleTorch() }
        }
        buttons.addView(torchButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)).apply {
            marginEnd = dp(16)
        })

        val doneButton = pillButton(if (continuous) "Done" else "Cancel", Color.WHITE, Color.BLACK).apply {
            setOnClickListener { close(closeReason()) }
        }
        buttons.addView(doneButton, LinearLayout.LayoutParams(dp(160), dp(52)))

        bottom.addView(buttons)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Back button. */
    override fun onCancel(dialog: android.content.DialogInterface) {
        super.onCancel(dialog)
        close(closeReason(), dismiss = false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        barcodeScanner?.close()
        barcodeScanner = null
        tone?.release()
        tone = null
        camera = null
    }

    private fun closeReason(): String = if (continuous && scannedCount > 0) "done" else "cancelled"

    private fun startCamera() {
        if (finished || !isAdded) {
            return
        }

        val context = requireContext()
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            if (finished || !isAdded || view == null) {
                return@addListener
            }

            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .build()

                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(ScannerFunctions.mlKitFormats(formats))
                        .build(),
                )
                barcodeScanner = scanner

                val executor = Executors.newSingleThreadExecutor()
                analysisExecutor = executor
                val mainExecutor = ContextCompat.getMainExecutor(context)
                analysis.setAnalyzer(executor) { proxy -> analyze(scanner, proxy, mainExecutor) }

                val bound = provider.bindToLifecycle(viewLifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = bound

                torchButton.visibility = if (bound.cameraInfo.hasFlashUnit()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed to start: ${e.message}", e)
                close("unavailable")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(scanner: BarcodeScanner, proxy: ImageProxy, mainExecutor: Executor) {
        val media = proxy.image

        if (media == null || finished) {
            proxy.close()
            return
        }

        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)

        scanner.process(input)
            .addOnSuccessListener(mainExecutor) { barcodes ->
                onBarcodes(barcodes)
            }
            .addOnFailureListener { e -> Log.w(TAG, "Barcode detection failed: ${e.message}") }
            .addOnCompleteListener { proxy.close() }
    }

    /** Main thread. */
    private fun onBarcodes(barcodes: List<Barcode>) {
        if (finished || !isAdded) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        lastSeen.entries.removeAll { now - it.value >= REPEAT_WINDOW_MS }

        for (barcode in barcodes) {
            val value = barcode.rawValue
            if (value.isNullOrEmpty()) {
                continue
            }

            val format = ScannerFunctions.formatName(barcode.format)

            if (!continuous) {
                finished = true
                feedback()
                dispatchScanned(value, format)
                dismissAllowingStateLoss()
                return
            }

            val seenRecently = lastSeen.containsKey(value)
            lastSeen[value] = now

            if (seenRecently) {
                continue
            }

            scannedCount++
            feedback()
            showLastScanned(value)
            dispatchScanned(value, format)
        }
    }

    private fun showLastScanned(value: String) {
        lastScannedView.text = "$scannedCount · $value"
        lastScannedView.visibility = View.VISIBLE
    }

    private fun feedback() {
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).also { tone = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Exception) {
            Log.w(TAG, "No beep: ${e.message}")
        }

        try {
            ContextCompat.getSystemService(requireContext(), Vibrator::class.java)
                ?.takeIf { it.hasVibrator() }
                ?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "No vibration: ${e.message}")
        }
    }

    private fun toggleTorch() {
        val camera = camera ?: return
        torchOn = !torchOn
        camera.cameraControl.enableTorch(torchOn)
        torchButton.alpha = if (torchOn) 1f else 0.7f
    }

    /** Closes without (another) code: ScannerCancelled exactly once. */
    private fun close(reason: String, dismiss: Boolean = true) {
        if (finished) {
            return
        }

        finished = true
        activity?.let { ScannerFunctions.dispatchCancelled(it, id, reason) }

        if (dismiss && isAdded) {
            dismissAllowingStateLoss()
        }
    }

    private fun dispatchScanned(data: String, format: String) {
        val activity = activity ?: run {
            Log.e(TAG, "Not attached; dropping $event")
            return
        }

        val payload = JSONObject().apply {
            put("data", data)
            put("format", format)
            id?.let { put("id", it) }
        }

        NativeActionCoordinator.dispatchEvent(activity, event, payload.toString())
    }

    private fun pillButton(label: String, background: Int, textColor: Int): Button = Button(requireContext()).apply {
        text = label
        isAllCaps = false
        setTextColor(textColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        stateListAnimator = null
        setPadding(dp(20), 0, dp(20), 0)
        this.background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            setColor(background)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
