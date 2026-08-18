package org.tensorflow.lite

import java.nio.ByteBuffer

/**
 * `org.tensorflow:tensorflow-lite` on Maven Central is a placeholder that is not
 * a valid zip, so the real class cannot be resolved here at all. The shipped
 * melspectrogram maths is pure Kotlin ([com.jarvis.os.voice.MelSpectrogram]) and
 * IS tested for real; this stub only carries the two feature models' signatures
 * past the compiler.
 */
class Interpreter(model: ByteBuffer, options: Options = Options()) {

    class Options {
        // A public field in the real API, not a setter — the shipped code writes
        // `numThreads = 1` directly.
        @JvmField var numThreads: Int = -1
        fun setNumThreads(threads: Int): Options = this
        fun setUseXNNPACK(use: Boolean): Options = this
    }

    fun run(input: Any, output: Any) = Unit
    fun runForMultipleInputsOutputs(inputs: Array<Any>, outputs: Map<Int, Any>) = Unit
    fun resizeInput(index: Int, dims: IntArray) = Unit
    fun allocateTensors() = Unit
    fun getInputTensor(index: Int): Tensor = Tensor()
    fun getOutputTensor(index: Int): Tensor = Tensor()
    fun close() = Unit

    class Tensor {
        fun shape(): IntArray = IntArray(0)
        fun numElements(): Int = 0
    }
}
