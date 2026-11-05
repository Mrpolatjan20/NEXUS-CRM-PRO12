package uz.nexus.crm

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Qualcomm MobileFaceNet pair model adapter.
 * The exported model accepts two NCHW images and returns two 128D embeddings.
 * Supplying the same face to both branches lets this app use one embedding.
 */
class FaceRecognitionEngine(
    private val interpreter: Interpreter
) : Closeable {

    companion object {
        const val MODEL_ASSET = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112
        private const val EMBEDDING_SIZE = 128

        fun create(context: android.content.Context): FaceRecognitionEngine {
            val modelBytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            val modelBuffer = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            modelBuffer.put(modelBytes)
            modelBuffer.rewind()
            return FaceRecognitionEngine(Interpreter(modelBuffer))
        }
    }

    fun createEmbedding(faceBitmap: Bitmap): FloatArray {
        require(interpreter.inputTensorCount == 2) {
            "Yuz modeli ikkita kirish tasvirini kutmoqda."
        }
        for (i in 0 until 2) {
            val tensor = interpreter.getInputTensor(i)
            require(tensor.dataType() == DataType.FLOAT32 &&
                tensor.shape().contentEquals(intArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE))) {
                "MobileFaceNet kirish formati mos emas."
            }
        }

        val outputTensor = interpreter.getOutputTensor(0)
        require(outputTensor.dataType() == DataType.FLOAT32 &&
            outputTensor.shape().contentEquals(intArrayOf(2, EMBEDDING_SIZE))) {
            "MobileFaceNet chiqishi [2,128] FLOAT32 bo‘lishi kerak."
        }

        val input = createInput(faceBitmap)
        val secondInput = createInput(faceBitmap)
        val output = Array(2) { FloatArray(EMBEDDING_SIZE) }
        interpreter.runForMultipleInputsOutputs(
            arrayOf(input, secondInput),
            hashMapOf(0 to output)
        )
        return l2Normalize(output[0])
    }

    private fun createInput(faceBitmap: Bitmap): ByteBuffer {
        val square = centerSquare(faceBitmap)
        val scaled = Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val input = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
            .order(ByteOrder.nativeOrder())
        for (pixel in pixels) input.putFloat((((pixel shr 16) and 0xFF) / 127.5f) - 1f)
        for (pixel in pixels) input.putFloat((((pixel shr 8) and 0xFF) / 127.5f) - 1f)
        for (pixel in pixels) input.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
        input.rewind()
        return input
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
