package com.example.imageprocessinglab.benchmark

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Debug
import com.example.imageprocessinglab.processing.BitmapPool
import com.example.imageprocessinglab.processing.BufferPool
import com.example.imageprocessinglab.processing.ImageProcessor
import com.example.imageprocessinglab.processing.ImagePipeline
import com.example.imageprocessinglab.processing.ImageStore
import com.example.imageprocessinglab.processing.PipelineSink
import com.example.imageprocessinglab.processing.QualityMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Chạy MỘT cấu hình (một batch ảnh) với đo đạc thật:
 * thời gian tổng, throughput, latency từng ảnh (median/P95), peak memory, số lần GC,
 * kích thước output và PSNR/SSIM (đo trên vài ảnh mẫu, ngoài vùng tính giờ).
 *
 * 3 mô hình song song:
 *  - THREAD_POOL : ExecutorService cố định N luồng, mỗi ảnh là một task
 *  - COROUTINES  : mỗi ảnh là một coroutine, giới hạn song song N (Dispatchers.IO.limitedParallelism)
 *  - PIPELINE    : xem [ImagePipeline] (3 tầng nối bằng hàng đợi có giới hạn)
 */
class BenchmarkRunner(
    private val store: ImageStore,
    private val isCancelled: () -> Boolean = { false }
) {

    suspend fun run(
        experiment: String,
        config: BenchmarkConfig,
        sources: List<Uri>,
        onProgress: (done: Int, total: Int) -> Unit,
        onLog: (String) -> Unit
    ): BenchmarkResult = withContext(Dispatchers.IO) {

        val n = sources.size
        val threads = config.threads.coerceIn(1, 8)

        val bitmapPool = BitmapPool(config.bitmapPool)
        val bufferPool = BufferPool(config.bufferPool)
        val processor = ImageProcessor(store, config, bitmapPool, bufferPool)
        processor.formatNote?.let(onLog)

        val outDir = store.outputDir()
        store.clearOutput()

        val state = RunState(n, onProgress)

        // Ổn định bộ nhớ trước khi đo
        Runtime.getRuntime().gc()
        Thread.sleep(150)
        val gcBefore = readGcCount()
        val baselineBytes = usedBytes()

        // Luồng lấy mẫu bộ nhớ (Java heap + native heap, nơi chứa pixel Bitmap)
        val peakBytes = AtomicLong(baselineBytes)
        val sampling = AtomicBoolean(true)
        val sampler = Thread({
            while (sampling.get()) {
                val used = usedBytes()
                peakBytes.accumulateAndGet(used) { a, b -> max(a, b) }
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }, "mem-sampler").apply { isDaemon = true }
        sampler.start()

        val task: (Int) -> Unit = { i -> runOne(i, sources[i], processor, outDir, state) }

        // ------------------------------------------------------------ vùng tính giờ
        var threadsUsed = 1
        var totalNs = 0L
        try {
            val startNs = System.nanoTime()
            threadsUsed = when (config.execution) {
                ExecutionModel.SEQUENTIAL -> {
                    for (i in 0 until n) {
                        if (isCancelled()) break
                        task(i)
                    }
                    1
                }

                ExecutionModel.THREAD_POOL -> {
                    runThreadPool(n, threads, task)
                    threads
                }

                ExecutionModel.COROUTINES -> {
                    runCoroutines(n, threads, task)
                    threads
                }

                ExecutionModel.PIPELINE ->
                    ImagePipeline(processor, isCancelled)
                        .run(sources, threads, outDir, state, onLog)
            }
            totalNs = System.nanoTime() - startNs
        } finally {
            sampling.set(false)
            sampler.interrupt()
        }
        // ------------------------------------------------------------------------

        sampler.join(500)
        val gcCount = (readGcCount() - gcBefore).toInt().coerceAtLeast(0)
        // Peak = mức cao nhất so với baseline sau GC (đỡ bị lẫn rác của lần chạy trước)
        val peakMb = max(0L, peakBytes.get() - baselineBytes) / (1024.0 * 1024.0)

        val failed = state.failed.get()
        val processed = state.done.get() - failed
        val totalMs = max(1L, totalNs / 1_000_000L)
        val throughput = processed * 1000.0 / totalMs

        val latenciesMs = state.latenciesNs
            .filter { it >= 0L }
            .sorted()
            .map { it / 1_000_000.0 }

        if (failed > 0) {
            onLog("  ⚠ $failed ảnh lỗi. Lỗi đầu tiên: ${state.firstError.get()}")
        }

        val (psnr, ssim) = measureQuality(processor, sources, state, outDir)
        bitmapPool.clear()

        BenchmarkResult(
            experiment = experiment,
            totalTimeMs = totalMs,
            throughput = throughput,
            peakMemoryMb = peakMb,
            gcCount = gcCount,
            medianLatencyMs = percentile(latenciesMs, 0.50),
            p95LatencyMs = percentile(latenciesMs, 0.95),
            outputSizeKb = state.bytesOut.get() / 1024.0,
            psnr = psnr,
            ssim = ssim,
            speedup = 1.0,
            label = config.label,
            threadsUsed = threadsUsed,
            processed = processed,
            failed = failed
        )
    }

    // ====================================================================
    //  Một ảnh
    // ====================================================================

    private fun runOne(
        index: Int,
        uri: Uri,
        processor: ImageProcessor,
        outDir: File,
        state: RunState
    ) {
        if (isCancelled()) return
        val startNs = System.nanoTime()
        try {
            val bytes = processor.processOne(index, uri, outDir)
            state.success(index, startNs, bytes)
        } catch (t: Throwable) {
            state.fail(t)
        }
    }

    // ====================================================================
    //  Mô hình 1: Thread pool (ExecutorService)
    // ====================================================================

    private fun runThreadPool(n: Int, threads: Int, task: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads, namedFactory("pool"))
        try {
            val futures = ArrayList<Future<*>>(n)
            for (i in 0 until n) {
                futures.add(pool.submit(Runnable { task(i) }))
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    // ====================================================================
    //  Mô hình 2: Kotlin Coroutines
    // ====================================================================

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runCoroutines(n: Int, threads: Int, task: (Int) -> Unit) {
        val dispatcher = Dispatchers.IO.limitedParallelism(threads)
        coroutineScope {
            (0 until n)
                .map { i -> async(dispatcher) { task(i) } }
                .awaitAll()
        }
    }

    // ====================================================================
    //  Trạng thái chung + tiện ích đo
    // ====================================================================

    private class RunState(
        val n: Int,
        private val onProgress: (done: Int, total: Int) -> Unit
    ) : PipelineSink {
        val latenciesNs = LongArray(n) { -1L }
        val bytesOut = AtomicLong(0)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        private var lastPercent = -1

        override fun success(index: Int, startNs: Long, bytes: Long) {
            bytesOut.addAndGet(bytes)
            latenciesNs[index] = System.nanoTime() - startNs
            tick()
        }

        override fun fail(t: Throwable) {
            failed.incrementAndGet()
            firstError.compareAndSet(null, t.toString())
            tick()
        }

        private fun tick() {
            val d = done.incrementAndGet()
            synchronized(this) {
                val percent = d * 100 / n
                if (percent > lastPercent) {
                    lastPercent = percent
                    onProgress(d, n)
                }
            }
        }
    }

    private fun measureQuality(
        processor: ImageProcessor,
        sources: List<Uri>,
        state: RunState,
        outDir: File
    ): Pair<Double, Double> {
        val okIndices = state.latenciesNs.indices.filter { state.latenciesNs[it] >= 0L }
        if (okIndices.isEmpty()) return 0.0 to 0.0

        val picks = listOf(okIndices.first(), okIndices[okIndices.size / 2], okIndices.last())
            .distinct()

        var psnr = 0.0
        var ssim = 0.0
        var count = 0
        for (i in picks) {
            try {
                val ref = processor.referenceBitmap(sources[i])
                val test = BitmapFactory.decodeFile(processor.outputFile(outDir, i).path)
                if (test != null) {
                    val sameSize = abs(test.width - ref.width) <= 2 &&
                            abs(test.height - ref.height) <= 2
                    if (sameSize) {
                        val score = QualityMetrics.compare(ref, test)
                        psnr += score.psnr
                        ssim += score.ssim
                        count++
                    }
                    test.recycle()
                }
                ref.recycle()
            } catch (t: Throwable) {
                // bỏ qua ảnh mẫu lỗi
            }
        }
        return if (count == 0) 0.0 to 0.0 else (psnr / count) to (ssim / count)
    }

    private fun percentile(sortedMs: List<Double>, p: Double): Double {
        if (sortedMs.isEmpty()) return 0.0
        val rank = ceil(p * sortedMs.size).toInt().coerceIn(1, sortedMs.size)
        return sortedMs[rank - 1]
    }

    private fun usedBytes(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) + Debug.getNativeHeapAllocatedSize()
    }

    private fun readGcCount(): Long =
        try {
            Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }

    private fun namedFactory(prefix: String): ThreadFactory {
        val counter = AtomicInteger(0)
        return ThreadFactory { r -> Thread(r, "$prefix-${counter.incrementAndGet()}") }
    }
}