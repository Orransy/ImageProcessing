package com.example.imageprocessinglab

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.imageprocessinglab.benchmark.BenchmarkRunner
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var runner: BenchmarkRunner

    // Dashboard
    private lateinit var txtStatus: TextView
    private lateinit var txtProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtLog: TextView

    private lateinit var txtTotalTime: TextView
    private lateinit var txtThroughput: TextView
    private lateinit var txtPeakMemory: TextView
    private lateinit var txtGcCount: TextView
    private lateinit var txtMedian: TextView
    private lateinit var txtP95: TextView
    private lateinit var txtOutputSize: TextView
    private lateinit var txtQuality: TextView
    private lateinit var txtSpeedup: TextView

    // Settings
    private lateinit var spBatch: Spinner
    private lateinit var spThreads: Spinner
    private lateinit var spExecution: Spinner
    private lateinit var spDecode: Spinner
    private lateinit var spEngine: Spinner
    private lateinit var spFormat: Spinner
    private lateinit var spQuality: Spinner

    private lateinit var swBitmapPool: Switch
    private lateinit var swBufferPool: Switch

    private lateinit var experimentButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        runner = BenchmarkRunner()

        initializeViews()
        setupSpinners()
        setupButtons()

        addLog("Application initialized.")
        addLog("Mock Benchmark Engine ready.")
    }

    private fun initializeViews() {

        txtStatus = findViewById(R.id.txtStatus)
        txtProgress = findViewById(R.id.txtProgress)
        progressBar = findViewById(R.id.progressBar)
        txtLog = findViewById(R.id.txtLog)

        txtTotalTime = findViewById(R.id.txtTotalTime)
        txtThroughput = findViewById(R.id.txtThroughput)
        txtPeakMemory = findViewById(R.id.txtPeakMemory)
        txtGcCount = findViewById(R.id.txtGcCount)
        txtMedian = findViewById(R.id.txtMedian)
        txtP95 = findViewById(R.id.txtP95)
        txtOutputSize = findViewById(R.id.txtOutputSize)
        txtQuality = findViewById(R.id.txtQuality)
        txtSpeedup = findViewById(R.id.txtSpeedup)

        spBatch = findViewById(R.id.spBatch)
        spThreads = findViewById(R.id.spThreads)
        spExecution = findViewById(R.id.spExecution)
        spDecode = findViewById(R.id.spDecode)
        spEngine = findViewById(R.id.spEngine)
        spFormat = findViewById(R.id.spFormat)
        spQuality = findViewById(R.id.spQuality)

        swBitmapPool = findViewById(R.id.swBitmapPool)
        swBufferPool = findViewById(R.id.swBufferPool)

        experimentButtons = listOf(
            findViewById(R.id.btnE1),
            findViewById(R.id.btnE2),
            findViewById(R.id.btnE3),
            findViewById(R.id.btnE4),
            findViewById(R.id.btnE5),
            findViewById(R.id.btnE6),
            findViewById(R.id.btnE7)
        )
    }

    private fun setupSpinners() {

        setupSpinner(
            spBatch,
            listOf("100", "250", "500", "1000")
        )

        setupSpinner(
            spThreads,
            (1..8).map { it.toString() }
        )

        setupSpinner(
            spExecution,
            listOf(
                "Sequential",
                "Thread Pool",
                "Coroutines",
                "Multi-stage Pipeline"
            )
        )

        setupSpinner(
            spDecode,
            listOf(
                "Baseline",
                "inSampleSize",
                "setTargetSize"
            )
        )

        setupSpinner(
            spEngine,
            listOf(
                "Skia",
                "C++",
                "NEON"
            )
        )

        setupSpinner(
            spFormat,
            listOf(
                "JPEG",
                "WebP",
                "AVIF"
            )
        )

        setupSpinner(
            spQuality,
            listOf("60", "75", "85", "95", "100")
        )
    }

    private fun setupSpinner(
        spinner: Spinner,
        items: List<String>
    ) {

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            items
        )

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter
    }

    private fun setupButtons() {

        experimentButtons.forEachIndexed { index, button ->

            val experiment = "E${index + 1}"

            button.setOnClickListener {
                runExperiment(experiment)
            }
        }
    }

    private fun runExperiment(experiment: String) {

        val imageCount = spBatch.selectedItem.toString().toInt()
        val threads = spThreads.selectedItem.toString().toInt()

        val execution = spExecution.selectedItem.toString()
        val decode = spDecode.selectedItem.toString()
        val engine = spEngine.selectedItem.toString()
        val format = spFormat.selectedItem.toString()
        val quality = spQuality.selectedItem.toString()

        val bitmapPool = swBitmapPool.isChecked
        val bufferPool = swBufferPool.isChecked

        // Update UI
        txtStatus.text = "● RUNNING - $experiment"
        txtProgress.text = "0 / $imageCount images"
        progressBar.progress = 0

        resetResults()

        experimentButtons.forEach {
            it.isEnabled = false
        }

        addLog("--------------------------------")
        addLog("Starting $experiment")
        addLog("Images: $imageCount")
        addLog("Threads: $threads")
        addLog("Execution: $execution")
        addLog("Decode: $decode")
        addLog("Engine: $engine")
        addLog("Format: $format")
        addLog("Quality: $quality")
        addLog("Bitmap Pool: $bitmapPool")
        addLog("Buffer Pool: $bufferPool")

        runner.runMock(
            experiment = experiment,
            imageCount = imageCount,
            threads = threads,
            bitmapPool = bitmapPool,
            bufferPool = bufferPool,
            format = format
        ) { result ->

            // Update progress
            progressBar.progress = 100
            txtProgress.text =
                "$imageCount / $imageCount images"

            // Update results
            txtTotalTime.text =
                "Total Time: ${result.totalTimeMs} ms"

            txtThroughput.text =
                "Throughput: ${
                    String.format(
                        Locale.US,
                        "%.2f",
                        result.throughput
                    )
                } images/sec"

            txtPeakMemory.text =
                "Peak Memory: ${result.peakMemoryMb} MB"

            txtGcCount.text =
                "GC Count: ${result.gcCount} (MOCK)"

            txtMedian.text =
                "Median Latency: ${result.medianLatencyMs} ms"

            txtP95.text =
                "P95 Latency: ${result.p95LatencyMs} ms"

            val outputSize = if (result.outputSizeKb >= 1024) {
                String.format(
                    Locale.US,
                    "%.2f MB",
                    result.outputSizeKb / 1024
                )
            } else {
                String.format(
                    Locale.US,
                    "%.2f KB",
                    result.outputSizeKb
                )
            }

            txtOutputSize.text =
                "Output Size: $outputSize"

            txtQuality.text =
                "PSNR / SSIM: ${result.psnr} / ${result.ssim}"

            txtSpeedup.text =
                "Speedup: ${result.speedup}x (MOCK)"

            txtStatus.text = "● COMPLETED - $experiment"

            addLog("$experiment completed.")
            addLog("Throughput: ${result.throughput} images/sec")
            addLog("Total time: ${result.totalTimeMs} ms")
            addLog("NOTE: MOCK DATA ONLY")

            experimentButtons.forEach {
                it.isEnabled = true
            }
        }
    }

    private fun resetResults() {

        txtTotalTime.text = "Total Time: --"
        txtThroughput.text = "Throughput: --"
        txtPeakMemory.text = "Peak Memory: --"
        txtGcCount.text = "GC Count: --"
        txtMedian.text = "Median Latency: --"
        txtP95.text = "P95 Latency: --"
        txtOutputSize.text = "Output Size: --"
        txtQuality.text = "PSNR / SSIM: --"
        txtSpeedup.text = "Speedup: --"
    }

    private fun addLog(message: String) {
        txtLog.append("\n$message")

        val logScrollView =
            findViewById<android.widget.ScrollView>(R.id.logScrollView)

        logScrollView.post {
            logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }
}
