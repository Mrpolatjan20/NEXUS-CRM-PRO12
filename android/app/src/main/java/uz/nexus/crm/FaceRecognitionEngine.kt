package uz.nexus.crm

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * On-device MobileFaceNet embedding helper.
 *
 * Expected model contract: float32 input [1,112,112,3], RGB values normalized
 * to [-1,1], and float32 output [1,N]. The model file is intentionally loaded
 * lazily from assets so the app can still start while model packaging is being
 * completed.
 */
class FaceRecognitionEngine(
    private val interpreter: Interpreter
) : Closeable {

    companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112

        fun create(context: android.content.Context): FaceRecognitionEngine {
            val modelBytes = context.assets.open(MODEL_ASSET).use { input ->
                input.readBytes()
            }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size)
                .order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()
            return FaceRecognitionEngine(Interpreter(modelBuffer))
        }
    }

    fun createEmbedding(faceBitmap: Bitmap): FloatArray {
        val inputTensor = interpreter.getInputTensor(0)
        val shape = inputTensor.shape()
        require(shape.size == 4 && shape[1] == INPUT_SIZE && shape[2] == INPUT_SIZE && shape[3] == 3) {
            "MobileFaceNet modeli [1,112,112,3] formatida bo‘lishi kerak."
        }

        val resized = centerSquare(faceBitmap)
        val scaled = Bitmap.createScaledBitmap(resized, INPUT_SIZE, INPUT_SIZE, true)
        val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            input.putFloat((((pixel shr 16) and 0xFF) / 127.5f) - 1f)
            input.putFloat((((pixel shr 8) and 0xFF) / 127.5f) - 1f)
            input.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
        }
        input.rewind()

        val outputSize = interpreter.getOutputTensor(0).shape().last()
        val output = Array(1) { FloatArray(outputSize) }
        interpreter.run(input, output)
        return l2Normalize(output[0])
    }

    fun cosineSimilarity(first: FloatArray, second: FloatArray): Float {
        require(first.size == second.size) { "Yuz vektorlari o‘lchami mos emas." }
        var dot = 0f
        var a = 0f
        var b = 0f
        for (i in first.indices) {
            dot += first[i] * second[i]
            a += first[i] * first[i]
            b += second[i] * second[i]
        }
        if (a <= 0f || b <= 0f) return 0f
        return (dot / (sqrt(a) * sqrt(b))).coerceIn(-1f, 1f)
    }

    private fun centerSquare(source: Bitmap): Bitmap {
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        return Bitmap.createBitmap(source, left, top, side, side)
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sum = 0f
        for (value in vector) sum += value * value
        val norm = sqrt(sum)
        require(norm > 0f) { "Yuz modeli bo‘sh natija qaytardi." }
        return FloatArray(vector.size) { vector[it] / norm }
    }

    override fun close() {
        interpreter.close()
    }
}
