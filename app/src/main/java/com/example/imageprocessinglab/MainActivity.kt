package com.example.imageprocessinglab

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.activity.result.contract.ActivityResultContracts
import com.example.imageprocessinglab.benchmark.BenchmarkConfig
import com.example.imageprocessinglab.benchmark.BenchmarkController
import com.example.imageprocessinglab.benchmark.DecodeStrategy
import com.example.imageprocessinglab.benchmark.ExecutionModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.google.android.material.button.MaterialButton
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var controller: BenchmarkController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runningJob: Job? = null

    private var pendingExperiment: String? = null
    private var permissionAsked = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val experiment = pendingExperiment
            pendingExperiment = null
            if (!granted) {
                addLog("Không có quyền đọc ảnh -> dùng bộ ảnh synthetic.")
            }
            if (experiment != null) startExperiment(experiment)
        }

    // Callback từ controller/runner (có thể gọi từ luồng nền) -> đẩy về UI thread
    private val listener = object : BenchmarkController.Listener {
        override fun onLog(message: String) {
            runOnUiThread { addLog(message) }
        }

        override fun onProgress(run: Int, runs: Int, done: Int, total: Int, label: String) {
            runOnUiThread {
                val safeTotal = if (total > 0) total else 1
                txtProgress.text = "Run $run/$runs · $done / $total images"
                progressBar.progress =
                    ((run - 1) * 100 + done * 100 / safeTotal) / runs
            }
        }
    }

    // Navigation
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenu: TextView
    private lateinit var btnCloseDrawer: MaterialButton

    // Layout parts that need window insets
    private lateinit var topBar: LinearLayout
    private lateinit var scrollMain: ScrollView
    private lateinit var bottomBar: LinearLayout
    private lateinit var drawerHeader: LinearLayout
    private lateinit var closeButtonParams: LinearLayout.LayoutParams

    // Dashboard
    private lateinit var txtStatus: TextView
    private lateinit var txtProgress: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtLog: TextView
    private lateinit var logScrollView: ScrollView

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

    // E1..E7 (bottom bar buttons)
    private lateinit var experimentButtons: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(c("#1E1B4B")))

        controller = BenchmarkController(applicationContext)

        setContentView(buildUi())
        setupDrawer()
        setupInsets()
        setupSpinners()
        setupButtons()
        setupLogScroll()

        addLog("Application initialized.")
        addLog("Benchmark engine ready (đo thật, không mock).")
    }

    // =====================================================================
    //  UI (Glassmorphism, built in code)
    // =====================================================================

    private fun buildUi(): View {

        // Material widgets (MaterialButton) need a Material theme,
        // so they get their own themed context.
        val mctx = ContextThemeWrapper(
            this,
            com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar
        )

        drawerLayout = DrawerLayout(this)

        // ---------- Main content ----------
        val content = CoordinatorLayout(mctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c("#1E1B4B"), c("#4C1D95"), c("#1D4ED8"))
            )
        }

        content.addView(
            blob(c("#998B5CF6"), 320, Gravity.TOP or Gravity.END, 120, -60)
        )
        content.addView(
            blob(c("#883B82F6"), 360, Gravity.BOTTOM or Gravity.START, -140, 60)
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = CoordinatorLayout.LayoutParams(MATCH, MATCH)
        }

        // Top bar
        btnMenu = TextView(this).apply {
            text = "☰"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Open settings"
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(c("#33FFFFFF")),
                glassOval(),
                null
            )
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(14)
            }
            addView(TextView(context).apply {
                text = "Image Processing Lab"
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = "Benchmark Dashboard"
                textSize = 12f
                setTextColor(c("#CCDDD6FE"))
            })
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(btnMenu)
            addView(titleBox)
        }
        column.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))

        // Scrollable dashboard
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Status card
        txtStatus = TextView(this).apply {
            text = "● IDLE"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c("#C4B5FD"))
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply {
                setColor(c("#338B5CF6"))
                cornerRadius = dpF(20)
                setStroke(dp(1), c("#66C4B5FD"))
            }
        }
        txtProgress = TextView(this).apply {
            text = "0 / 0 images"
            textSize = 13f
            setTextColor(c("#CCDDD6FE"))
        }
        progressBar = ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressDrawable = glassProgressDrawable()
        }

        val statusCard = glassCard().apply {
            addView(cardTitle("STATUS"))
            addView(
                txtStatus,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) }
            )
            addView(
                txtProgress,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) }
            )
            addView(
                progressBar,
                LinearLayout.LayoutParams(MATCH, dp(10)).apply { topMargin = dp(8) }
            )
        }
        cards.addView(statusCard, LinearLayout.LayoutParams(MATCH, WRAP))

        // Results card
        txtTotalTime = metric("Total Time: --")
        txtThroughput = metric("Throughput: --")
        txtPeakMemory = metric("Peak Memory: --")
        txtGcCount = metric("GC Count: --")
        txtMedian = metric("Median Latency: --")
        txtP95 = metric("P95 Latency: --")
        txtOutputSize = metric("Output Size: --")
        txtQuality = metric("PSNR / SSIM: --")
        txtSpeedup = metric("Speedup: --")

        val resultsCard = glassCard().apply {
            addView(
                cardTitle("RESULTS"),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) }
            )
            listOf(
                txtTotalTime, txtThroughput, txtPeakMemory, txtGcCount,
                txtMedian, txtP95, txtOutputSize, txtQuality, txtSpeedup
            ).forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        }
        cards.addView(
            resultsCard,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
        )

        // Log card
        txtLog = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(c("#DDD6FE"))
        }
        logScrollView = ScrollView(this).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = glassField()
            addView(txtLog, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        val logCard = glassCard().apply {
            addView(cardTitle("LOG"))
            addView(
                logScrollView,
                LinearLayout.LayoutParams(MATCH, dp(180)).apply { topMargin = dp(10) }
            )
        }
        cards.addView(
            logCard,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) }
        )

        scrollMain = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), dp(8), dp(16), dp(120))
            addView(cards, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        column.addView(scrollMain, LinearLayout.LayoutParams(MATCH, 0, 1f))
        content.addView(column)

        // ---------- Bottom bar (E1..E7, all the same) ----------
        val buttons = (1..7).map { barButton("E$it") }

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c("#38FFFFFF"), c("#14FFFFFF"))
            ).apply {
                cornerRadius = dpF(32)
                setStroke(dp(1), c("#4DFFFFFF"))
            }
            buttons.forEach { addView(it) }
            layoutParams = CoordinatorLayout.LayoutParams(MATCH, dp(64)).apply {
                gravity = Gravity.BOTTOM
                setMargins(dp(16), 0, dp(16), dp(12))
            }
        }
        content.addView(bottomBar)

        experimentButtons = buttons

        drawerLayout.addView(content, DrawerLayout.LayoutParams(MATCH, MATCH))

        // ---------- Side drawer (settings) ----------
        spBatch = glassSpinner()
        spThreads = glassSpinner()
        spExecution = glassSpinner()
        spDecode = glassSpinner()
        spEngine = glassSpinner()
        spFormat = glassSpinner()
        spQuality = glassSpinner()

        swBitmapPool = glassSwitch("Bitmap Pool")
        swBufferPool = glassSwitch("Buffer Pool")

        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun addSetting(label: String, spinner: Spinner) {
            settings.addView(
                TextView(this).apply {
                    text = label
                    textSize = 12f
                    letterSpacing = 0.04f
                    setTextColor(c("#CCDDD6FE"))
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(6)
                }
            )
            settings.addView(spinner)
        }
        addSetting("Batch size", spBatch)
        addSetting("Threads", spThreads)
        addSetting("Execution model", spExecution)
        addSetting("Decode strategy", spDecode)
        addSetting("Processing engine", spEngine)
        addSetting("Output format", spFormat)
        addSetting("Quality", spQuality)

        settings.addView(
            cardTitle("MEMORY POOLING"),
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = dp(24)
                bottomMargin = dp(8)
            }
        )
        settings.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = glassField()
                setPadding(dp(14), dp(4), dp(14), dp(4))
                addView(swBitmapPool)
                addView(swBufferPool)
            },
            LinearLayout.LayoutParams(MATCH, WRAP)
        )

        drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(8))
            addView(TextView(context).apply {
                text = "Settings"
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(TextView(context).apply {
                text = "Benchmark configuration"
                textSize = 13f
                setTextColor(c("#CCDDD6FE"))
            })
        }

        btnCloseDrawer = MaterialButton(mctx).apply {
            text = "Done"
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(c("#8B5CF6"))
            cornerRadius = dp(16)
        }
        closeButtonParams = LinearLayout.LayoutParams(MATCH, dp(52)).apply {
            setMargins(dp(20), dp(20), dp(20), dp(20))
        }

        val drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(c("#E6312E81"), c("#E61E1B4B"))
            ).apply {
                val r = dpF(28)
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setStroke(dp(1), c("#4DFFFFFF"))
            }
            addView(drawerHeader, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    clipToPadding = false
                    setPadding(dp(20), 0, dp(20), dp(12))
                    addView(settings, ViewGroup.LayoutParams(MATCH, WRAP))
                },
                LinearLayout.LayoutParams(MATCH, 0, 1f)
            )
            addView(btnCloseDrawer, closeButtonParams)
        }
        drawerLayout.addView(
            drawerPanel,
            DrawerLayout.LayoutParams(dp(320), MATCH, Gravity.START)
        )

        return drawerLayout
    }

    // ---------- UI helpers ----------

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpF(v: Int): Float = v * resources.displayMetrics.density

    private fun c(hex: String): Int = Color.parseColor(hex)

    private fun blob(
        color: Int,
        sizeDp: Int,
        gravity: Int,
        tx: Int,
        ty: Int
    ): View = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dpF(sizeDp / 2)
            colors = intArrayOf(color, color and 0x00FFFFFF)
        }
        layoutParams = CoordinatorLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
            this.gravity = gravity
        }
        translationX = dpF(tx)
        translationY = dpF(ty)
    }

    private fun glassOval(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(c("#44FFFFFF"), c("#14FFFFFF"))
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(1), c("#4DFFFFFF"))
    }

    private fun glassField(): GradientDrawable = GradientDrawable().apply {
        setColor(c("#26FFFFFF"))
        cornerRadius = dpF(14)
        setStroke(dp(1), c("#40FFFFFF"))
    }

    private fun glassCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(c("#38FFFFFF"), c("#12FFFFFF"))
        ).apply {
            cornerRadius = dpF(24)
            setStroke(dp(1), c("#4DFFFFFF"))
        }
    }

    private fun cardTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setTextColor(c("#C4B5FD"))
    }

    private fun metric(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun glassProgressDrawable(): LayerDrawable {
        val bg = GradientDrawable().apply {
            setColor(c("#26FFFFFF"))
            cornerRadius = dpF(8)
        }
        val fg = ClipDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(c("#8B5CF6"), c("#3B82F6"))
            ).apply { cornerRadius = dpF(8) },
            Gravity.START,
            ClipDrawable.HORIZONTAL
        )
        return LayerDrawable(arrayOf(bg, fg)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun barButton(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(
            ColorStateList(
                arrayOf(
                    intArrayOf(-android.R.attr.state_enabled),
                    intArrayOf()
                ),
                intArrayOf(c("#66FFFFFF"), Color.WHITE)
            )
        )
        background = RippleDrawable(
            ColorStateList.valueOf(c("#33FFFFFF")),
            null,
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpF(16)
            }
        )
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f)
    }

    private fun glassSpinner(): Spinner = Spinner(this).apply {
        val arrow = ChevronDrawable(c("#C4B5FD"))
        background = LayerDrawable(arrayOf(glassField(), arrow)).apply {
            setLayerSize(1, dp(24), dp(24))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetRight(1, dp(12))
        }
        setPopupBackgroundDrawable(
            GradientDrawable().apply {
                setColor(c("#FF2E1F78"))
                cornerRadius = dpF(14)
                setStroke(dp(1), c("#4DFFFFFF"))
            }
        )
        setPadding(0, 0, dp(40), 0)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
    }

    private fun glassSwitch(label: String): Switch = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(c("#C4B5FD"), c("#E5E7EB"))
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(c("#8B5CF6"), c("#4DFFFFFF"))
        )
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
    }

    private class ChevronDrawable(color: Int) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            paint.strokeWidth = w / 12f
            val path = Path().apply {
                moveTo(b.left + w * 0.30f, b.top + h * 0.40f)
                lineTo(b.left + w * 0.50f, b.top + h * 0.60f)
                lineTo(b.left + w * 0.70f, b.top + h * 0.40f)
            }
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // =====================================================================
    //  Drawer / insets
    // =====================================================================

    private fun setupDrawer() {

        drawerLayout.setScrimColor(c("#66100A30"))

        btnMenu.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        btnCloseDrawer.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    // Edge-to-edge: keep content clear of the status / navigation bars.
    private fun setupInsets() {

        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            topBar.setPadding(dp(16), dp(12) + bars.top, dp(16), dp(8))

            scrollMain.setPadding(dp(16), dp(8), dp(16), dp(120) + bars.bottom)

            (bottomBar.layoutParams as CoordinatorLayout.LayoutParams)
                .bottomMargin = bars.bottom + dp(12)
            bottomBar.requestLayout()

            drawerHeader.setPadding(dp(24), dp(20) + bars.top, dp(24), dp(8))

            closeButtonParams.bottomMargin = dp(20) + bars.bottom
            btnCloseDrawer.layoutParams = closeButtonParams

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.requestApplyInsets(drawerLayout)
    }

    // =====================================================================
    //  Benchmark logic
    // =====================================================================

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

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_spinner_item,
            items
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View = (super.getView(position, convertView, parent) as TextView)
                .also { styleItem(it, false) }

            override fun getDropDownView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View = (super.getDropDownView(position, convertView, parent) as TextView)
                .also { styleItem(it, true) }
        }

        adapter.setDropDownViewResource(
            android.R.layout.simple_spinner_dropdown_item
        )

        spinner.adapter = adapter
    }

    private fun styleItem(tv: TextView, dropdown: Boolean) {
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        tv.gravity = Gravity.CENTER_VERTICAL
        if (dropdown) {
            tv.setBackgroundColor(Color.TRANSPARENT)
            tv.minHeight = dp(48)
            tv.setPadding(dp(16), dp(12), dp(16), dp(12))
        } else {
            tv.setPadding(dp(14), dp(12), dp(14), dp(12))
        }
    }

    private fun setupButtons() {

        experimentButtons.forEachIndexed { index, button ->

            val experiment = "E${index + 1}"

            button.setOnClickListener {
                onExperimentClicked(experiment)
            }
        }
    }

    // The log box lives inside the page ScrollView: let it scroll on its own.
    @SuppressLint("ClickableViewAccessibility")
    private fun setupLogScroll() {
        logScrollView.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
    }

    private fun onExperimentClicked(experiment: String) {

        if (runningJob?.isActive == true) return

        val store = controller.store
        if (!store.hasPermission() && !permissionAsked) {
            permissionAsked = true
            pendingExperiment = experiment
            permissionLauncher.launch(store.requiredPermission())
        } else {
            startExperiment(experiment)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        experimentButtons.forEach { it.isEnabled = enabled }
    }

    private fun startExperiment(experiment: String) {

        if (runningJob?.isActive == true) return

        val imageCount = spBatch.selectedItem.toString().toInt()
        val threads = spThreads.selectedItem.toString().toInt()

        val execution = spExecution.selectedItem.toString()
        val decode = spDecode.selectedItem.toString()
        val engine = spEngine.selectedItem.toString()
        val format = spFormat.selectedItem.toString()
        val quality = spQuality.selectedItem.toString()

        val bitmapPool = swBitmapPool.isChecked
        val bufferPool = swBufferPool.isChecked

        val config = BenchmarkConfig(
            label = experiment,
            imageCount = imageCount,
            threads = threads,
            execution = ExecutionModel.fromLabel(execution),
            decode = DecodeStrategy.fromLabel(decode),
            engine = engine,
            format = format,
            quality = quality.toInt(),
            bitmapPool = bitmapPool,
            bufferPool = bufferPool
        )

        // Settings are read, so the drawer can be closed
        drawerLayout.closeDrawer(GravityCompat.START)

        // Update UI
        txtStatus.text = "● RUNNING - $experiment"
        txtProgress.text = "0 / $imageCount images"
        progressBar.progress = 0

        resetResults()
        setButtonsEnabled(false)

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

        runningJob = uiScope.launch {
            try {
                val outcome = controller.runExperiment(experiment, config, listener)
                showResult(experiment, outcome)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                txtStatus.text = "● FAILED - $experiment"
                addLog("ERROR: ${t.message ?: t.toString()}")
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun showResult(
        experiment: String,
        outcome: BenchmarkController.Outcome
    ) {
        val result = outcome.best

        progressBar.progress = 100
        txtProgress.text = "Completed ${outcome.results.size} run(s)"

        txtTotalTime.text = "Total Time: ${result.totalTimeMs} ms"

        txtThroughput.text = String.format(
            Locale.US, "Throughput: %.2f images/sec", result.throughput
        )

        txtPeakMemory.text = String.format(
            Locale.US, "Peak Memory: %.1f MB", result.peakMemoryMb
        )

        txtGcCount.text = "GC Count: ${result.gcCount}"

        txtMedian.text = String.format(
            Locale.US, "Median Latency: %.2f ms", result.medianLatencyMs
        )

        txtP95.text = String.format(
            Locale.US, "P95 Latency: %.2f ms", result.p95LatencyMs
        )

        val outputSize = if (result.outputSizeKb >= 1024) {
            String.format(Locale.US, "%.2f MB", result.outputSizeKb / 1024)
        } else {
            String.format(Locale.US, "%.2f KB", result.outputSizeKb)
        }
        txtOutputSize.text = "Output Size: $outputSize"

        txtQuality.text = String.format(
            Locale.US, "PSNR / SSIM: %.2f dB / %.4f", result.psnr, result.ssim
        )

        txtSpeedup.text = String.format(
            Locale.US, "Speedup: %.2fx (%s)", result.speedup, result.label
        )

        txtStatus.text = "● COMPLETED - $experiment"

        addLog("$experiment completed.")
        addLog("Best: ${result.label}")
        addLog(
            String.format(
                Locale.US, "Throughput: %.2f images/sec", result.throughput
            )
        )
        addLog("Total time: ${result.totalTimeMs} ms")
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

        logScrollView.post {
            logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        controller.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}