package uz.nexus.crm

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * Active camera challenge: open eyes -> blink -> reopen -> turn head sideways.
 * This blocks a simple still-photo attempt, but is not a certified anti-spoofing
 * system and can still be fooled by a sufficiently convincing replay.
 */
class FaceLivenessAnalyzer(
    private val onProgress: (String) -> Unit,
    private val onPassed: () -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
    )

    private var busy = false
    private var stage = 0
    private var stableFrames = 0
    private var passed = false
    private var lastProgressAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        if (busy || passed) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        busy = true
        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces ->
                if (passed) return@addOnSuccessListener
                if (faces.size != 1) {
                    stableFrames = 0
                    report(if (faces.isEmpty()) "Yuzingizni kameraga qarating" else "Kadrda faqat bitta yuz bo‘lsin")
                    return@addOnSuccessListener
                }
                advance(faces[0])
            }
            .addOnFailureListener {
                report("Kamera tasvirini tekshirib bo‘lmadi. Qayta urinib ko‘ring.")
            }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    private fun advance(face: Face) {
        val left = face.leftEyeOpenProbability ?: 0f
        val right = face.rightEyeOpenProbability ?: 0f
        when (stage) {
            0 -> {
                if (left > 0.65f && right > 0.65f) stableFrames++ else stableFrames = 0
                report("Kameraga qarab, ko‘zingizni ochiq tuting")
                if (stableFrames >= 2) { stage = 1; stableFrames = 0 }
            }
            1 -> {
                if (left < 0.25f && right < 0.25f) stableFrames++ else stableFrames = 0
                report("Endi ko‘zingizni yumib, qayta oching")
                if (stableFrames >= 2) { stage = 2; stableFrames = 0 }
            }
            2 -> {
                if (left > 0.65f && right > 0.65f) stableFrames++ else stableFrames = 0
                report("Yaxshi. Endi boshingizni chap yoki o‘ng tomonga buring")
                if (stableFrames >= 2) { stage = 3; stableFrames = 0 }
            }
            3 -> {
                if (kotlin.math.abs(face.headEulerAngleY) >= 18f) stableFrames++ else stableFrames = 0
                report("Bosh harakati tekshirilmoqda…")
                if (stableFrames >= 2) {
                    passed = true
                    report("Jonli yuz tekshirildi. Endi suratga oling.")
                    onPassed()
                }
            }
        }
    }

    private fun report(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt >= 250L || message.startsWith("Jonli")) {
            lastProgressAt = now
            onProgress(message)
        }
    }

    override fun close() {
        detector.close()
    }
}
