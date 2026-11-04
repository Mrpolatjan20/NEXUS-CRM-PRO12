package uz.nexus.crm

import android.graphics.Bitmap
import android.graphics.Rect
import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Detects and crops one face locally. This is face detection only; identity
 * verification is performed separately by FaceRecognitionEngine.
 */
object FaceImageProcessor {
    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .enableTracking()
        .build()

    suspend fun detectAndCrop(context: Context, bitmap: Bitmap): Bitmap {
        val detector = FaceDetection.getClient(options)
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val faces = suspendCancellableCoroutine<List<com.google.mlkit.vision.face.Face>> { cont ->
                detector.process(image)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
            }
            require(faces.size == 1) {
                if (faces.isEmpty()) "Yuz aniqlanmadi. Kameraga qarab turing."
                else "Kadrda faqat bitta yuz bo‘lishi kerak."
            }
            val bounds = Rect(faces[0].boundingBox)
            val marginX = (bounds.width() * 0.18f).toInt()
            val marginY = (bounds.height() * 0.22f).toInt()
            bounds.inset(-marginX, -marginY)
            bounds.left = bounds.left.coerceAtLeast(0)
            bounds.top = bounds.top.coerceAtLeast(0)
            bounds.right = bounds.right.coerceAtMost(bitmap.width)
            bounds.bottom = bounds.bottom.coerceAtMost(bitmap.height)
            require(bounds.width() >= 80 && bounds.height() >= 80) {
                "Yuz juda uzoq. Kameraga yaqinroq keling."
            }
            return Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
        } finally {
            detector.close()
        }
    }
}
