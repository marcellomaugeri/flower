package dev.flower.flower_tflite

import android.util.Log
import dev.flower.flower_tflite.helpers.assertIntsEqual
import org.tensorflow.lite.Interpreter
import java.lang.Integer.min
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * Flower client that handles TensorFlow Lite model [Interpreter] and sample data.
 * @param tfliteFileBuffer TensorFlow Lite model file.
 * @param layersSizes Sizes of model parameters layers in bytes.
 * @param spec Specification for the samples, see [SampleSpec].
 */
class FlowerClient<X : Any, Y : Any>(
    tfliteFileBuffer: MappedByteBuffer,
    val layersSizes: IntArray,
    val spec: SampleSpec<X, Y>,
) : AutoCloseable {
    val interpreter = Interpreter(tfliteFileBuffer)
    val interpreterLock = ReentrantLock()
    val trainingSamples = mutableListOf<Sample<X, Y>>()
    val testSamples = mutableListOf<Sample<X, Y>>()
    val trainSampleLock = ReentrantReadWriteLock()
    val testSampleLock = ReentrantReadWriteLock()

    /**
     * Add one sample point ([bottleneck], [label]) for training or testing later.
     * Thread-safe.
     */
    fun addSample(
        bottleneck: X, label: Y, isTraining: Boolean
    ) {
        val samples = if (isTraining) trainingSamples else testSamples
        val lock = if (isTraining) trainSampleLock else testSampleLock
        lock.write {
            samples.add(Sample(bottleneck, label))
            //Log.d(TAG, "Added sample: bottleneck=$bottleneck, label=$label, isTraining=$isTraining")
            /*
            if (bottleneck is FloatArray) {
                for (value in bottleneck) { // Iterate over the elements
                    Log.d(TAG, "Bottleneck value: $value") // Log each value
                }
            } else {
                Log.d(TAG, "bottleneck is not a FloatArray") // Handle the case where it's not
            }
            if (label is FloatArray) {
                for (value in label) { // Iterate over the elements
                    Log.d(TAG, "Label value: $value") // Log each value
                }
            } else {
                Log.d(TAG, "label is not a FloatArray") // Handle the case where it's not
            }
            */
        }
    }

    /**
     * Obtain the model parameters from [interpreter].
     *
     * This method is more expensive than a simple lookup because it interfaces [interpreter].
     * Thread-safe.
     */
    fun getParameters(): Array<ByteBuffer> {
        Log.d(TAG, "Getting parameters...")
        val inputs: Map<String, Any> = FakeNonEmptyMap()
        Log.i(TAG, "Raw inputs: $inputs.")
        val outputs = emptyParameterMap()
        runSignatureLocked(inputs, outputs, "parameters")
        Log.i(TAG, "Raw outputs: $outputs.")
        return parametersFromMap(outputs)
    }

    /**
     * Update the model parameters in [interpreter] with [parameters].
     *
     * This method is more expensive than a simple "set" because it interfaces [interpreter].
     * Thread-safe.
     */
    fun updateParameters(parameters: Array<ByteBuffer>): Array<ByteBuffer> {
        Log.d(TAG, "Updating parameters: ${parameters.contentToString()}")
        val outputs = emptyParameterMap()
        runSignatureLocked(parametersToMap(parameters), outputs, "restore")
        Log.d(TAG, "Updated parameters: ${parameters.contentToString()}")
        return parametersFromMap(outputs)
    }

    /**
     * Fit the local model using [trainingSamples] for [epochs] epochs with batch size [batchSize].
     *
     * Thread-safe, and block operations on [trainingSamples].
     * @param lossCallback Called after every epoch with the [List] of training losses.
     * @return [List] of average training losses for each epoch.
     */
    fun fit(
        epochs: Int = 1, batchSize: Int = 16, lossCallback: ((List<Float>) -> Unit)? = null
    ): List<Double> {
        Log.d(TAG, "Starting to train for $epochs epochs with batch size $batchSize.")
        // Obtain write lock to prevent training samples from being modified.
        return trainSampleLock.write {
            (1..epochs).map {
                val losses = trainOneEpoch(batchSize)
                Log.d(TAG, "Epoch $it: losses = $losses.")
                lossCallback?.invoke(losses)
                losses.average()
            }
        }
    }

    /**
     * Evaluate model loss and accuracy using [testSamples] and [spec].
     *
     * Thread-safe, and block operations on [testSamples].
     * @return (loss, accuracy).
     */
    fun evaluate(): Pair<Float, Float> {
        val result = testSampleLock.read {
            val bottlenecks = testSamples.map { it.bottleneck }
            Log.d(TAG, "Evaluating with bottlenecks: $bottlenecks.")
            val logits = inference(spec.convertX(bottlenecks))
            spec.loss(testSamples, logits) to spec.accuracy(testSamples, logits)
        }
        Log.d(TAG, "Evaluate loss & accuracy: $result.")
        return result
    }

    /**
     * Run inference on [x] using [interpreter] and return the result.
     */
    fun inference(x: Array<X>): Array<Y> {
        val inputs = mapOf("x" to x)
        val logits = spec.emptyY(x.size)
        val outputs = mapOf("logits" to logits)
        runSignatureLocked(inputs, outputs, "infer")
        return logits
    }

    /**
     * Not thread-safe.
     */
    private fun trainOneEpoch(batchSize: Int): List<Float> {
        if (trainingSamples.isEmpty()) {
            Log.d(TAG, "No training samples available.")
            return listOf()
        }

        trainingSamples.shuffle()
        return trainingBatches(min(batchSize, trainingSamples.size)).map {
            val bottlenecks = it.map { sample -> sample.bottleneck }
            val labels = it.map { sample -> sample.label }
            training(spec.convertX(bottlenecks), spec.convertY(labels))
        }.toList()
    }

    /**
     * Not thread-safe because we assume [trainSampleLock] is already acquired.
     */
    private fun training(
        bottlenecks: Array<X>, labels: Array<Y>
    ): Float {
        val inputs = mapOf<String, Any>(
            "x" to bottlenecks,
            "y" to labels,
        )
        val loss = FloatBuffer.allocate(1)
        val outputs = mapOf<String, Any>(
            "loss" to loss,
        )
        Log.d(TAG, "Training with inputs: $inputs")
        runSignatureLocked(inputs, outputs, "train")
        Log.d(TAG, "Training loss: ${loss.get(0)}")
        return loss.get(0)
    }


    /**
     * Constructs an iterator that iterates over training sample batches.
     */
    private fun trainingBatches(trainBatchSize: Int): Sequence<List<Sample<X, Y>>> {
        return sequence {
            var nextIndex = 0

            while (nextIndex < trainingSamples.size) {
                val fromIndex = nextIndex
                nextIndex += trainBatchSize

                val batch = if (nextIndex >= trainingSamples.size) {
                    trainingSamples.subList(
                        trainingSamples.size - trainBatchSize, trainingSamples.size
                    )
                } else {
                    trainingSamples.subList(fromIndex, nextIndex)
                }
                //Log.d(TAG, "Yielding training batch: $batch")
                yield(batch)
            }
        }
    }

    fun parametersFromMap(map: Map<String, Any>): Array<ByteBuffer> {
        assertIntsEqual(layersSizes.size, map.size)
        return (0 until map.size).map {
            val buffer = map["a$it"] as ByteBuffer
            buffer.rewind()
            buffer
        }.toTypedArray()
    }

    fun parametersToMap(parameters: Array<ByteBuffer>): Map<String, Any> {
        assertIntsEqual(layersSizes.size, parameters.size)
        return parameters.mapIndexed { index, bytes -> "a$index" to bytes }.toMap()
    }

    private fun runSignatureLocked(
        inputs: Map<String, Any>,
        outputs: Map<String, Any>,
        signatureKey: String
    ) {
        //Log.d(TAG, "Running signature with key: $signatureKey, inputs: $inputs, outputs: $outputs")
        interpreterLock.withLock {
            interpreter.runSignature(inputs, outputs, signatureKey)
        }
        //Log.d(TAG, "Completed signature with key: $signatureKey")
    }

    private fun emptyParameterMap(): Map<String, Any> {
        return layersSizes.mapIndexed { index, size -> "a$index" to ByteBuffer.allocate(size) }
            .toMap()
    }

    companion object {
        private const val TAG = "Flower Client"
    }

    override fun close() {
        interpreter.close()
    }
}

/**
 * One sample data point ([bottleneck], [label]).
 */
data class Sample<X, Y>(val bottleneck: X, val label: Y)

/**
 * This map always returns `false` when [isEmpty] is called to bypass TFLite interpreter's
 * stupid empty check on the `input` argument of `runSignature`.
 */
class FakeNonEmptyMap<K, V> : HashMap<K, V>() {
    override fun isEmpty(): Boolean {
        return false
    }
}