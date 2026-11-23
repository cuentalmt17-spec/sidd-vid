package com.tuapp.reproductor

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var rootLayout: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var brightnessOverlay: View
    private lateinit var emptyCard: LinearLayout
    private lateinit var uiOverlay: FrameLayout
    private lateinit var splashOverlay: FrameLayout
    private lateinit var splashLogoView: ImageView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var stepToggleBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var unlockBtn: Button
    private lateinit var hudText: TextView

    private var player: ExoPlayer? = null
    private var playlist: List<Uri> = emptyList()
    private var currentIndex: Int = -1
    private var externalSubtitleUri: Uri? = null

    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIdx = 1
    private var doubleTapStepMs = 15_000L
    private var isLocked = false
    private var isUiVisible = true
    private var isUserSeeking = false
    private var isZoomMode = false
    private var brightnessLevel = 0.85f
    private var splashPlayed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideUiRunnable = Runnable { setUiVisible(false) }
    private val hideHudRunnable = Runnable { hudText.visibility = View.GONE }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressUi()
            mainHandler.postDelayed(this, 250)
        }
    }

    private val pickVideosLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                uris.forEach { uri ->
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
                playlist = uris
                currentIndex = 0
                externalSubtitleUri = null
                emptyCard.visibility = View.GONE
                loadUriAt(0)
            }
        }

    private val pickSubtitleLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                externalSubtitleUri = uri
                if (currentIndex in playlist.indices) {
                    val savedPos = player?.currentPosition ?: 0L
                    loadUriAt(currentIndex, savedPos)
                    showHud("Subtítulo externo cargado")
                } else {
                    Toast.makeText(this, "Primero selecciona un video", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            hideSystemBars()
            buildUi()
            setContentView(rootLayout)
            initPlayer()
            if (!splashPlayed) {
                splashPlayed = true
                startSplashAnimation()
            } else {
                splashOverlay.visibility = View.GONE
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error al iniciar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideSystemBars() {
        try {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (_: Exception) {
        }
    }

    private fun pillBg(bgColor: Int, strokeColor: Int, radiusPx: Float = 999f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(bgColor)
            setStroke(2, strokeColor)
            cornerRadius = radiusPx
        }
    }

    private fun makeChipButton(label: String, highlighted: Boolean = false): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (highlighted) Color.parseColor("#BBF7D0") else Color.parseColor("#ECFDF5"))
            background = pillBg(
                if (highlighted) Color.parseColor("#4422C55E") else Color.parseColor("#B80C1C12"),
                if (highlighted) Color.parseColor("#4ADE80") else Color.parseColor("#55BBF7D0")
            )
            setPadding(22, 10, 22, 10)
            minimumHeight = 0
            minHeight = 0
            minimumWidth = 0
            minWidth = 0
        }
    }

    private fun buildUi() {
        val greenAccent = Color.parseColor("#22C55E")
        val greenBright = Color.parseColor("#4ADE80")
        val greenSoftText = Color.parseColor("#DCFCE7")

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#06120B"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(Color.parseColor("#06120B"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(playerView)

        brightnessOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(brightnessOverlay)

        // Tarjeta de bienvenida mientras no hay video cargado
        emptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBg(
                Color.parseColor("#E60A1B11"),
                Color.parseColor("#664ADE80"),
                36f
            )
            setPadding(48, 40, 48, 40)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = 40
                rightMargin = 40
            }
        }

        val badgeText = TextView(this).apply {
            text = "▶"
            textSize = 26f
            setTextColor(greenBright)
            gravity = Gravity.CENTER
        }

        val appHeading = TextView(this).apply {
            text = "reproductor sidd"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F0FDF4"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }

        val appSub = TextView(this).apply {
            text = "MKV • Multi-audio • Subtítulos • Saltos 15s/30s"
            textSize = 12f
            setTextColor(Color.parseColor("#86EFAC"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }

        val choosePrimaryBtn = makeChipButton("Seleccionar video", highlighted = true).apply {
            textSize = 14f
            setPadding(36, 16, 36, 16)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            setOnClickListener {
                pickVideosLauncher.launch(arrayOf("video/*"))
            }
        }

        emptyCard.addView(badgeText)
        emptyCard.addView(appHeading)
        emptyCard.addView(appSub)
        emptyCard.addView(choosePrimaryBtn)
        rootLayout.addView(emptyCard)

        uiOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#9906120B"))
            setPadding(28, 24, 28, 20)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        titleText = TextView(this).apply {
            text = "reproductor sidd"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F0FDF4"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 12
            }
        }

        val openBtn = makeChipButton("Videos", highlighted = true).apply {
            setOnClickListener {
                pickVideosLauncher.launch(arrayOf("video/*"))
                resetHideTimer()
            }
        }

        val tracksBtn = makeChipButton("CC / Audio", highlighted = true).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = 10 }
            setOnClickListener {
                showTracksDialog()
                resetHideTimer()
            }
        }

        topBar.addView(titleText)
        topBar.addView(openBtn)
        topBar.addView(tracksBtn)
        uiOverlay.addView(topBar)

        val centerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                topMargin = 36
            }
        }

        val backBtn = makeChipButton("⏮").apply {
            textSize = 17f
            setPadding(28, 18, 28, 18)
            setOnClickListener { jumpBy(-doubleTapStepMs) }
        }

        playPauseBtn = makeChipButton("▶", highlighted = true).apply {
            textSize = 19f
            setPadding(38, 24, 38, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = 28
                rightMargin = 28
            }
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.currentMediaItem == null) {
                    pickVideosLauncher.launch(arrayOf("video/*"))
                } else if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
                resetHideTimer()
            }
        }

        val fwdBtn = makeChipButton("⏭").apply {
            textSize = 17f
            setPadding(28, 18, 28, 18)
            setOnClickListener { jumpBy(doubleTapStepMs) }
        }

        centerRow.addView(backBtn)
        centerRow.addView(playPauseBtn)
        centerRow.addView(fwdBtn)
        uiOverlay.addView(centerRow)

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#B306120B"))
            setPadding(28, 16, 28, 24)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        currentTimeText = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(greenSoftText)
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(greenBright)
            thumbTintList = ColorStateList.valueOf(greenBright)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 14
                rightMargin = 14
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = player?.duration ?: 0L
                        if (dur > 0) {
                            currentTimeText.text = formatMs((dur * progress) / 1000L)
                        }
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserSeeking = true
                    mainHandler.removeCallbacks(hideUiRunnable)
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val p = player
                    val dur = p?.duration ?: 0L
                    if (p != null && dur > 0 && sb != null) {
                        p.seekTo((dur * sb.progress) / 1000L)
                    }
                    isUserSeeking = false
                    resetHideTimer()
                }
            })
        }

        totalTimeText = TextView(this).apply {
            text = "0:00"
            textSize = 12f
            setTextColor(greenSoftText)
        }

        seekRow.addView(currentTimeText)
        seekRow.addView(seekBar)
        seekRow.addView(totalTimeText)
        bottomPanel.addView(seekRow)

        val jumpScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
        }

        val jumpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val b30 = makeChipButton("-30s").apply { setOnClickListener { jumpBy(-30_000L) } }
        val b15 = makeChipButton("-15s").apply { setOnClickListener { jumpBy(-15_000L) } }
        stepToggleBtn = makeChipButton("Doble toque: 15s", highlighted = true).apply {
            setOnClickListener {
                doubleTapStepMs = if (doubleTapStepMs == 15_000L) 30_000L else 15_000L
                val sec = (doubleTapStepMs / 1000L).toInt()
                text = "Doble toque: ${sec}s"
                showHud("Salto doble toque: ${sec}s")
                resetHideTimer()
            }
        }
        val f15 = makeChipButton("+15s").apply { setOnClickListener { jumpBy(15_000L) } }
        val f30 = makeChipButton("+30s").apply { setOnClickListener { jumpBy(30_000L) } }

        val right8 = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }

        b30.layoutParams = right8
        b15.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
        stepToggleBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
        f15.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }

        jumpRow.addView(b30)
        jumpRow.addView(b15)
        jumpRow.addView(stepToggleBtn)
        jumpRow.addView(f15)
        jumpRow.addView(f30)
        jumpScroll.addView(jumpRow)
        bottomPanel.addView(jumpScroll)

        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
        }

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val listBtn = makeChipButton("Lista").apply {
            setOnClickListener {
                showPlaylistDialog()
                resetHideTimer()
            }
        }

        val zoomBtn = makeChipButton("Ajuste").apply {
            setOnClickListener {
                isZoomMode = !isZoomMode
                playerView.resizeMode = if (isZoomMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                showHud(if (isZoomMode) "Ajuste: Rellenar" else "Ajuste: Original")
                resetHideTimer()
            }
        }

        speedBtn = makeChipButton("1.0x").apply {
            setOnClickListener {
                speedIdx = (speedIdx + 1) % speeds.size
                val spd = speeds[speedIdx]
                player?.setPlaybackSpeed(spd)
                text = "${spd}x"
                showHud("Velocidad: ${spd}x")
                resetHideTimer()
            }
        }

        val rotateBtn = makeChipButton("Girar").apply {
            setOnClickListener {
                requestedOrientation = if (resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
                ) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                resetHideTimer()
            }
        }

        lockBtn = makeChipButton("Bloquear").apply {
            setOnClickListener {
                isLocked = true
                setUiVisible(false)
                unlockBtn.visibility = View.VISIBLE
                showHud("Controles bloqueados")
            }
        }

        listBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
        zoomBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
        speedBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
        rotateBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }

        toolsRow.addView(listBtn)
        toolsRow.addView(zoomBtn)
        toolsRow.addView(speedBtn)
        toolsRow.addView(rotateBtn)
        toolsRow.addView(lockBtn)
        toolsScroll.addView(toolsRow)
        bottomPanel.addView(toolsScroll)

        uiOverlay.addView(bottomPanel)
        rootLayout.addView(uiOverlay)

        hudText = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#ECFDF5"))
            background = pillBg(Color.parseColor("#E60A1B11"), greenAccent, 24f)
            setPadding(36, 20, 36, 20)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        rootLayout.addView(hudText)

        unlockBtn = makeChipButton("Desbloquear", highlighted = true).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply {
                setMargins(0, 0, 36, 36)
            }
            setOnClickListener {
                isLocked = false
                unlockBtn.visibility = View.GONE
                setUiVisible(true)
                showHud("Controles desbloqueados")
            }
        }
        rootLayout.addView(unlockBtn)

        // Capa de animación inicial (Splash) con fondo verde oscuro y logo
        splashOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#05110A"))
            alpha = 1f
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val splashCenter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val logoFrame = FrameLayout(this).apply {
            background = pillBg(
                Color.parseColor("#2222C55E"),
                Color.parseColor("#994ADE80"),
                44f
            )
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(240, 240)
        }

        splashLogoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            try {
                setImageResource(R.drawable.ic_sidd_logo)
            } catch (_: Exception) {
            }
        }
        logoFrame.addView(splashLogoView)

        val splashTitle = TextView(this).apply {
            text = "reproductor sidd"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F0FDF4"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 22 }
        }

        val splashLoading = TextView(this).apply {
            text = "Cargando..."
            textSize = 13f
            setTextColor(Color.parseColor("#86EFAC"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
        }

        val splashSpinner = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(greenBright)
            layoutParams = LinearLayout.LayoutParams(76, 76).apply { topMargin = 16 }
        }

        splashCenter.addView(logoFrame)
        splashCenter.addView(splashTitle)
        splashCenter.addView(splashLoading)
        splashCenter.addView(splashSpinner)
        splashOverlay.addView(splashCenter)

        val splashFooter = TextView(this).apply {
            text = "Siddhartha video"
            textSize = 11f
            setTextColor(Color.parseColor("#6686EFAC"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 42 }
        }
        splashOverlay.addView(splashFooter)

        rootLayout.addView(splashOverlay)

        setupGestures()
    }

    private fun startSplashAnimation() {
        splashLogoView.scaleX = 0.72f
        splashLogoView.scaleY = 0.72f
        splashLogoView.alpha = 0f

        val logoScaleX = ObjectAnimator.ofFloat(splashLogoView, View.SCALE_X, 0.72f, 1.05f, 1.0f).apply {
            duration = 750L
            interpolator = OvershootInterpolator(1.2f)
        }
        val logoScaleY = ObjectAnimator.ofFloat(splashLogoView, View.SCALE_Y, 0.72f, 1.05f, 1.0f).apply {
            duration = 750L
            interpolator = OvershootInterpolator(1.2f)
        }
        val logoAlpha = ObjectAnimator.ofFloat(splashLogoView, View.ALPHA, 0f, 1f).apply {
            duration = 550L
        }

        val enterSet = AnimatorSet().apply {
            playTogether(logoScaleX, logoScaleY, logoAlpha)
        }

        enterSet.doOnEnd {
            mainHandler.postDelayed({
                val fadeOut = ObjectAnimator.ofFloat(splashOverlay, View.ALPHA, 1f, 0f).apply {
                    duration = 650L
                    interpolator = AccelerateDecelerateInterpolator()
                }
                val zoomOut = ObjectAnimator.ofFloat(splashOverlay, View.SCALE_X, 1f, 1.06f).apply {
                    duration = 650L
                }
                val zoomOutY = ObjectAnimator.ofFloat(splashOverlay, View.SCALE_Y, 1f, 1.06f).apply {
                    duration = 650L
                }
                AnimatorSet().apply {
                    playTogether(fadeOut, zoomOut, zoomOutY)
                    doOnEnd {
                        splashOverlay.visibility = View.GONE
                    }
                    start()
                }
            }, 650L)
        }

        enterSet.start()
    }

    private fun setupGestures() {
        var lastX = 0f
        var lastY = 0f
        var startX = 0f
        var isVerticalDrag = false

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    unlockBtn.visibility = View.VISIBLE
                    mainHandler.postDelayed({
                        if (isLocked) unlockBtn.visibility = View.GONE
                    }, 2500)
                } else {
                    setUiVisible(!isUiVisible)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || player?.currentMediaItem == null) return true
                val mid = rootLayout.width / 2f
                jumpBy(if (e.x < mid) -doubleTapStepMs else doubleTapStepMs)
                return true
            }
        })

        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    lastX = event.x
                    lastY = event.y
                    isVerticalDrag = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isLocked) return@setOnTouchListener true
                    val dy = lastY - event.y
                    val dx = event.x - lastX
                    if (!isVerticalDrag && abs(dy) > 22f && abs(dy) > abs(dx)) {
                        isVerticalDrag = true
                        mainHandler.removeCallbacks(hideUiRunnable)
                    }
                    if (isVerticalDrag) {
                        val delta = dy / (rootLayout.height * 0.65f)
                        if (startX < rootLayout.width / 2f) {
                            brightnessLevel = (brightnessLevel + delta).coerceIn(0.15f, 1.0f)
                            brightnessOverlay.alpha = (1f - brightnessLevel) * 0.78f
                            showHud("Brillo ${(brightnessLevel * 100).toInt()}%")
                        } else {
                            val p = player
                            if (p != null) {
                                val nextVol = (p.volume + delta).coerceIn(0f, 1f)
                                p.volume = nextVol
                                showHud("Volumen ${(nextVol * 100).toInt()}%")
                            }
                        }
                    }
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isVerticalDrag && !isLocked) {
                        resetHideTimer()
                    }
                    isVerticalDrag = false
                }
            }
            true
        }
    }

    private fun initPlayer() {
        try {
            val exo = ExoPlayer.Builder(this).build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playPauseBtn.text = if (isPlaying) "❚❚" else "▶"
                        if (isPlaying && !isLocked) {
                            resetHideTimer()
                        } else {
                            mainHandler.removeCallbacks(hideUiRunnable)
                            if (!isLocked) setUiVisible(true)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && currentIndex + 1 < playlist.size) {
                            loadUriAt(currentIndex + 1)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        showHud("Error: ${error.errorCodeName}")
                        Toast.makeText(
                            this@MainActivity,
                            "No se pudo reproducir (${error.errorCodeName})",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                })
            }
            player = exo
            playerView.player = exo
            mainHandler.post(progressRunnable)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo iniciar reproductor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadUriAt(index: Int, startPositionMs: Long = 0L) {
        val p = player ?: return
        if (index !in playlist.indices) return
        emptyCard.visibility = View.GONE
        val uri = playlist[index]
        titleText.text = queryDisplayName(uri) ?: "reproductor sidd"

        val builder = MediaItem.Builder().setUri(uri)
        externalSubtitleUri?.let { subUri ->
            val subName = queryDisplayName(subUri)?.lowercase(Locale.ROOT) ?: ""
            val mime = if (subName.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mime)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subConfig))
        }

        p.setMediaItem(builder.build(), startPositionMs)
        p.prepare()
        p.play()
        resetHideTimer()
    }

    private fun jumpBy(deltaMs: Long) {
        val p = player ?: return
        if (p.currentMediaItem == null) return
        val dur = p.duration
        val target = if (dur > 0) {
            (p.currentPosition + deltaMs).coerceIn(0L, dur)
        } else {
            (p.currentPosition + deltaMs).coerceAtLeast(0L)
        }
        p.seekTo(target)
        updateProgressUi()
        val sec = (deltaMs / 1000L).toInt()
        showHud(if (sec > 0) "+${sec}s" else "${sec}s")
        resetHideTimer()
    }

    private fun showTracksDialog() {
        val p = player
        if (p == null || p.currentMediaItem == null) {
            Toast.makeText(this, "Abre un video primero", Toast.LENGTH_SHORT).show()
            return
        }

        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        options.add("Cargar subtítulo externo (.srt / .vtt)")
        actions.add { pickSubtitleLauncher.launch(arrayOf("*/*")) }

        val groups = p.currentTracks.groups
        groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: "und"
                    val label = fmt.label ?: "Pista ${i + 1}"
                    val activeTag = if (group.isTrackSelected(i)) " [Activo]" else ""
                    options.add("Audio: $label ($lang)$activeTag")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, listOf(i))
                            )
                            .build()
                        showHud("Audio: $label ($lang)")
                    }
                }
            }
        }

        options.add("Desactivar subtítulos")
        actions.add {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            showHud("Subtítulos desactivados")
        }

        groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val fmt = group.getTrackFormat(i)
                    val lang = fmt.language ?: "und"
                    val label = fmt.label ?: "Subtítulo ${i + 1}"
                    val activeTag = if (group.isTrackSelected(i)) " [Activo]" else ""
                    options.add("Sub: $label ($lang)$activeTag")
                    actions.add {
                        p.trackSelectionParameters = p.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(
                                TrackSelectionOverride(group.mediaTrackGroup, listOf(i))
                            )
                            .build()
                        showHud("Subtítulo: $label ($lang)")
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Audio y subtítulos")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .show()
    }

    private fun showPlaylistDialog() {
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No hay videos en la lista", Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlist.mapIndexed { idx, uri ->
            val prefix = if (idx == currentIndex) "▶ " else ""
            prefix + (queryDisplayName(uri) ?: "Video ${idx + 1}")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Lista de videos")
            .setItems(names) { _, which ->
                currentIndex = which
                externalSubtitleUri = null
                loadUriAt(which)
            }
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                    null
                } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    private fun updateProgressUi() {
        val p = player ?: return
        val dur = p.duration
        val pos = p.currentPosition.coerceAtLeast(0L)
        if (!isUserSeeking && dur > 0) {
            seekBar.progress = ((pos * 1000L) / dur).toInt().coerceIn(0, 1000)
        }
        currentTimeText.text = formatMs(pos)
        totalTimeText.text = if (dur > 0) formatMs(dur) else "0:00"
    }

    private fun formatMs(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        return String.format(Locale.ROOT, "%d:%02d", min, sec)
    }

    private fun setUiVisible(visible: Boolean) {
        if (isLocked && visible) return
        isUiVisible = visible
        uiOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(hideUiRunnable)
        if (visible && (player?.isPlaying == true)) {
            resetHideTimer()
        }
    }

    private fun resetHideTimer() {
        mainHandler.removeCallbacks(hideUiRunnable)
        if (!isLocked && isUiVisible && (player?.isPlaying == true)) {
            mainHandler.postDelayed(hideUiRunnable, 3500)
        }
    }

    private fun showHud(msg: String) {
        hudText.text = msg
        hudText.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 650)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) initPlayer()
        playerView.onResume()
    }

    override fun onStop() {
        super.onStop()
        playerView.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}
