package com.example.imageprocessinglab.processing

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** Nơi nhận kết quả từng ảnh từ pipeline (BenchmarkRunner cài đặt để ghi số đo). */
interface PipelineSink {
    fun success(index: Int, startNs: Long, bytes: Long)
    fun fail(t: Throwable)
}

/**
 * Mô hình xử lý nhiều giai đoạn (Pipeline):
 *   đọc (I/O) -> giải mã + resize (CPU) -> nén + ghi (CPU + I/O)
 * Các tầng chạy ở luồng riêng, nối bằng hàng đợi có giới hạn (back-pressure).
 *
 * Phân bổ luồng: tổng = max(3, N). Tầng đọc 1 luồng, phần còn lại chia cho tầng decode
 * (làm tròn lên) và tầng encode. Pipeline luôn cần tối thiểu 3 luồng.
 * Latency mỗi ảnh = từ lúc bắt đầu đọc đến lúc ghi xong (gồm cả thời gian chờ trong hàng đợi).
 */
class ImagePipeline(
    private val processor: ImageProcessor,
    private val isCancelled: () -> Boolean = { false }
) {

    private class PipelineJob(val index: Int, val startNs: Long) {
        var raw: ReusableBuffer? = null
        var bitmap: Bitmap? = null
    }

    /** Chạy toàn bộ [sources]; trả về tổng số luồng thực dùng. */
    fun run(
        sources: List<Uri>,
        threads: Int,
        outDir: File,
        sink: PipelineSink,
        onLog: (String) -> Unit
    ): Int {
        val total = max(3, threads)
        val rest = total - 1
        val decodeWorkers = (rest + 1) / 2
        val encodeWorkers = rest - decodeWorkers
        val capacity = max(4, total * 2)

        onLog("  Pipeline: read x1 -> decode x$decodeWorkers -> encode x$encodeWorkers ($total luồng, queue=$capacity)")

        val poison = PipelineJob(-1, 0L)
        val readToDecode = ArrayBlockingQueue<PipelineJob>(capacity)
        val decodeToEncode = ArrayBlockingQueue<PipelineJob>(capacity)
        val decodersLeft = AtomicInteger(decodeWorkers)
        val workers = ArrayList<Thread>()

        // Tầng 1: đọc
        workers.add(Thread({
            try {
                for (i in sources.indices) {
                    if (isCancelled()) break
                    val job = PipelineJob(i, System.nanoTime())
                    val raw = try {
                        processor.readSource(sources[i])
                    } catch (t: Throwable) {
                        sink.fail(t)
                        null
                    }
                    if (raw != null) {
                        job.raw = raw
                        readToDecode.put(job)
                    }
                }
            } catch (t: Throwable) {
                sink.fail(t)
            } finally {
                repeat(decodeWorkers) { readToDecode.put(poison) }
            }
        }, "pipe-read"))

        // Tầng 2: giải mã + resize
        repeat(decodeWorkers) { w ->
            workers.add(Thread({
                try {
                    while (true) {
                        val job = readToDecode.take()
                        if (job === poison) break
                        val raw = job.raw
                        try {
                            job.bitmap = processor.decodeAndResize(raw!!)
                        } catch (t: Throwable) {
                            job.bitmap = null
                            sink.fail(t)
                        } finally {
                            if (raw != null) processor.releaseBuffer(raw)
                            job.raw = null
                        }
                        if (job.bitmap != null) decodeToEncode.put(job)
                    }
                } catch (t: Throwable) {
                    sink.fail(t)
                } finally {
                    if (decodersLeft.decrementAndGet() == 0) {
                        repeat(encodeWorkers) { decodeToEncode.put(poison) }
                    }
                }
            }, "pipe-decode-${w + 1}"))
        }

        // Tầng 3: nén + ghi
        repeat(encodeWorkers) { w ->
            workers.add(Thread({
                try {
                    while (true) {
                        val job = decodeToEncode.take()
                        if (job === poison) break
                        val bitmap = job.bitmap!!
                        try {
                            val bytes = processor.encodeAndWrite(job.index, bitmap, outDir)
                            sink.success(job.index, job.startNs, bytes)
                        } catch (t: Throwable) {
                            sink.fail(t)
                        } finally {
                            processor.releaseBitmap(bitmap)
                            job.bitmap = null
                        }
                    }
                } catch (t: Throwable) {
                    sink.fail(t)
                }
            }, "pipe-encode-${w + 1}"))
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }
        return total
    }
}