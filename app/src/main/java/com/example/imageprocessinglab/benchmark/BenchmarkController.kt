package com.example.imageprocessinglab.benchmark

import android.content.Context
import com.example.imageprocessinglab.processing.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Điều phối thí nghiệm: mỗi thí nghiệm = danh sách "biến thể" cấu hình, chạy lần lượt
 * bằng [BenchmarkRunner], speedup tính so với biến thể ĐẦU TIÊN (baseline của thí nghiệm).
 *
 *  E1: Baseline vs inSampleSize vs setTargetSize        (1 luồng)
 *  E2: có/không Bitmap pool + Buffer pool               (1 và N luồng)
 *  E3: Sequential (baseline) vs Thread pool vs Coroutines vs Pipeline  (cùng N luồng)
 *  E4: quét N = 1..8 với mô hình song song đang chọn
 *  E5: engine (hiện chỉ có Skia)
 *  E6: JPEG vs WebP vs AVIF
 *  E7: baseline vs cấu hình đang chọn
 */
class BenchmarkController(context: Context) {

    interface Listener {
        fun onLog(message: String)
        fun onProgress(run: Int, runs: Int, done: Int, total: Int, label: String)
    }

    data class Outcome(
        val experiment: String,
        val results: List<BenchmarkResult>,
        /** Biến thể có throughput cao nhất. */
        val best: BenchmarkResult
    )

    @Volatile
    private var cancelled = false

    val store = ImageStore(context.applicationContext)

    private val runner = BenchmarkRunner(store) { cancelled }

    fun cancel() {
        cancelled = true
    }

    suspend fun runExperiment(
        experiment: String,
        base: BenchmarkConfig,
        listener: Listener
    ): Outcome {
        cancelled = false

        val variants = buildVariants(experiment, base)

        if (experiment == "E5" && base.engine != "Skia") {
            listener.onLog("Engine ${base.engine}: chưa triển khai, chạy bằng Skia (BitmapFactory/Canvas).")
        }
        if (experiment == "E4" && base.execution == ExecutionModel.PIPELINE) {
            listener.onLog("Lưu ý: Pipeline luôn dùng tối thiểu 3 luồng nên N=1,2 cho kết quả gần giống nhau.")
        }

        // ---- I/O: đọc danh sách ảnh nguồn
        val (sources, outputPath) = withContext(Dispatchers.IO) {
            store.loadSources(base.imageCount) to store.outputDir().absolutePath
        }
        if (sources.synthetic) {
            listener.onLog("Không có ảnh/quyền đọc -> dùng ${sources.uniqueCount} ảnh synthetic 2560x1920.")
        } else {
            val repeat = if (sources.uniqueCount < base.imageCount) {
                " (lặp lại để đủ ${base.imageCount})"
            } else {
                ""
            }
            listener.onLog("Đọc ${sources.uniqueCount} ảnh từ bộ nhớ máy$repeat.")
        }
        listener.onLog("Output: $outputPath")

        // ---- warm-up (không tính vào kết quả): JIT, page cache, lần chạy đầu không bị thiệt
        listener.onLog("Warm-up 8 ảnh (không tính vào kết quả)...")
        runner.run(
            experiment = experiment,
            config = base.copy(
                label = "warm-up",
                execution = ExecutionModel.SEQUENTIAL,
                threads = 1,
                bitmapPool = false,
                bufferPool = false
            ),
            sources = sources.uris.take(8),
            onProgress = { _, _ -> },
            onLog = { }
        )

        // ---- chạy lần lượt các biến thể
        val results = ArrayList<BenchmarkResult>()
        for ((index, config) in variants.withIndex()) {
            if (cancelled) break

            val run = index + 1
            val runs = variants.size
            listener.onLog("▶ [$run/$runs] ${config.label}")
            listener.onProgress(run, runs, 0, sources.uris.size, config.label)

            val raw = runner.run(
                experiment = experiment,
                config = config,
                sources = sources.uris,
                onProgress = { done, total ->
                    listener.onProgress(run, runs, done, total, config.label)
                },
                onLog = { listener.onLog(it) }
            )

            val baselineMs = results.firstOrNull()?.totalTimeMs ?: raw.totalTimeMs
            val result = raw.copy(speedup = baselineMs.toDouble() / raw.totalTimeMs)
            results.add(result)
            listener.onLog(describe(result))
        }

        if (results.isEmpty()) {
            throw IllegalStateException("Đã hủy trước khi có kết quả")
        }

        val best = results.reduce { a, b -> if (b.throughput > a.throughput) b else a }

        if (results.size > 1) {
            listener.onLog("=== TỔNG KẾT $experiment (speedup so với '${results.first().label}') ===")
            results.forEach { r ->
                listener.onLog(
                    String.format(
                        Locale.US, "%s -> %.1f img/s, x%.2f", r.label, r.throughput, r.speedup
                    )
                )
            }
            listener.onLog("★ Tốt nhất: ${best.label}")
        }

        return Outcome(experiment, results, best)
    }

    private fun describe(r: BenchmarkResult): String =
        String.format(
            Locale.US,
            "  %d ms | %.1f img/s | peak %.0f MB | GC %d | median %.1f ms | x%.2f | %d luồng%s",
            r.totalTimeMs,
            r.throughput,
            r.peakMemoryMb,
            r.gcCount,
            r.medianLatencyMs,
            r.speedup,
            r.threadsUsed,
            if (r.failed > 0) " | lỗi ${r.failed}" else ""
        )

    // ====================================================================
    //  Danh sách biến thể cho từng thí nghiệm
    // ====================================================================

    private fun buildVariants(experiment: String, b: BenchmarkConfig): List<BenchmarkConfig> {
        val n = b.threads
        val parallel = if (b.execution == ExecutionModel.SEQUENTIAL) {
            ExecutionModel.THREAD_POOL
        } else {
            b.execution
        }

        return when (experiment) {

            "E1" -> DecodeStrategy.values().map {
                b.copy(
                    label = "Decode: ${it.label}",
                    decode = it,
                    threads = 1,
                    execution = ExecutionModel.SEQUENTIAL
                )
            }

            "E2" -> {
                val list = arrayListOf(
                    b.copy(
                        label = "No pool · 1 luồng",
                        bitmapPool = false, bufferPool = false,
                        threads = 1, execution = ExecutionModel.SEQUENTIAL
                    ),
                    b.copy(
                        label = "Pools · 1 luồng",
                        bitmapPool = true, bufferPool = true,
                        threads = 1, execution = ExecutionModel.SEQUENTIAL
                    )
                )
                if (n > 1) {
                    list.add(
                        b.copy(
                            label = "No pool · $n luồng",
                            bitmapPool = false, bufferPool = false,
                            threads = n, execution = parallel
                        )
                    )
                    list.add(
                        b.copy(
                            label = "Pools · $n luồng",
                            bitmapPool = true, bufferPool = true,
                            threads = n, execution = parallel
                        )
                    )
                }
                list
            }

            "E3" -> {
                val list = arrayListOf(
                    b.copy(
                        label = "Sequential (baseline)",
                        execution = ExecutionModel.SEQUENTIAL, threads = 1
                    )
                )
                listOf(
                    ExecutionModel.THREAD_POOL,
                    ExecutionModel.COROUTINES,
                    ExecutionModel.PIPELINE
                ).forEach {
                    list.add(b.copy(label = "${it.label} · N=$n", execution = it))
                }
                list
            }

            "E4" -> (1..8).map { k ->
                b.copy(label = "${parallel.label} · N=$k", execution = parallel, threads = k)
            }

            "E5" -> listOf(b.copy(label = "Engine: ${b.engine}"))

            "E6" -> listOf("JPEG", "WebP", "AVIF").map {
                b.copy(label = "Format: $it", format = it)
            }

            "E7" -> listOf(
                b.copy(
                    label = "Baseline",
                    execution = ExecutionModel.SEQUENTIAL, threads = 1,
                    decode = DecodeStrategy.BASELINE,
                    bitmapPool = false, bufferPool = false
                ),
                b.copy(label = "Cấu hình đã chọn")
            )

            else -> listOf(b)
        }
    }
}