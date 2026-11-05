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
 * Lightweight active liveness challenge. Eye checks are used when the device
 * provides eye probabilities; head movement remains required on all devices.
 * This is a usability check, not certified anti-spoofing.
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
    private var unavailableEyeFrames = 0
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
                report("Yuz aniqlanmoqda… Kameraga qarab turing")
            }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    private fun advance(face: Face) {
        val left = face.leftEyeOpenProbability
        val right = face.rightEyeOpenProbability

        // Some front cameras/lighting conditions do not return eye probabilities.
        // Do not leave the user stuck forever: keep head-turn verification active.
        if (stage < 3 && (left == null || right == null)) {
            unavailableEyeFrames++
            if (unavailableEyeFrames >= 5) {
                stage = 3
                stableFrames = 0
                report("Yuz aniqlandi. Boshingizni chap yoki o‘ng tomonga buring")
            } else {
                report("Yuzingizni doira ichiga joylashtiring")
            }
            return
        }

        val l = left ?: 0f
        val r = right ?: 0f
        when (stage) {
            0 -> {
                if (l > 0.45f && r > 0.45f) stableFrames++ else stableFrames = 0
                report("Kameraga qarab, ko‘zingizni ochiq tuting")
                if (stableFrames >= 2) { stage = 1; stableFrames = 0 }
            }
            1 -> {
                if (l < 0.45f && r < 0.45f) stableFrames++ else stableFrames = 0
                report("Ko‘zingizni bir marta yumib oching")
                if (stableFrames >= 2) { stage = 2; stableFrames = 0 }
            }
            2 -> {
                if (l > 0.45f && r > 0.45f) stableFrames++ else stableFrames = 0
                report("Yaxshi. Boshingizni chap yoki o‘ng tomonga buring")
                if (stableFrames >= 2) { stage = 3; stableFrames = 0 }
            }
            3 -> {
                if (kotlin.math.abs(face.headEulerAngleY) >= 12f) stableFrames++ else stableFrames = 0
                report("Bosh harakati tekshirilmoqda…")
                if (stableFrames >= 2) {
                    passed = true
                    report("Jonlilik tekshirildi. Surat avtomatik olinmoqda…")
                    onPassed()
                }
            }
        }
    }

    private fun report(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt >= 180L || message.startsWith("Jonlilik")) {
            lastProgressAt = now
            onProgress(message)
        }
    }

    override fun close() {
        detector.close()
    }
}
