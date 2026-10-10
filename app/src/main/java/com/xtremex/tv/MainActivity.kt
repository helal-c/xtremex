package com.xtremex.tv

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.widget.doAfterTextChanged
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import android.content.res.Configuration
import android.content.pm.ActivityInfo
import android.widget.ImageView
import android.widget.ImageButton
import android.graphics.drawable.StateListDrawable
import com.xtremex.tv.auth.AccessController

class MainActivity : AppCompatActivity() {
    private enum class GuideKind { ALL, FAVORITES, RECENT, CATEGORY }
    private data class GuideMode(val kind: GuideKind, val label: String, val category: String? = null)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var infoBox: LinearLayout
    private lateinit var touchControls: LinearLayout
    private lateinit var pauseIcon: ImageView
    private lateinit var pauseLabel: TextView
    private lateinit var screenLabel: TextView
    private val guideTabs = mutableListOf<Pair<TextView, GuideKind?>>()
    private lateinit var channelLogo: ImageView
    private lateinit var numberText: TextView
    private lateinit var nameText: TextView
    private lateinit var metaText: TextView
    private lateinit var guideScrim: View
    private var gestureX = 0f
    private var gestureY = 0f
    private var gestureCanOpen = false
    private lateinit var guide: LinearLayout
    private lateinit var guideTitle: TextView
    private lateinit var guideHint: TextView
    private lateinit var list: RecyclerView
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var numberEntry: TextView
    private lateinit var adapter: ChannelAdapter
    private lateinit var signupView: SignupView
    private lateinit var access: AccessController
    private var activeDialog: AlertDialog? = null
    private val tuneGuard = TuneGuard()
    private val isTv: Boolean get() = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    private val portraitMobile: Boolean get() = !isTv && resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private val handler = Handler(Looper.getMainLooper())
    private val repository by lazy { PlaylistRepository(this) }
    private val updater by lazy { UpdateManager(this) }
    private val extras by lazy { AppExtras(this) { ::access.isInitialized && access.canPlay() && access.adsAllowed } }
    private val prefs by lazy { getSharedPreferences("xtremex-tv", MODE_PRIVATE) }

    private var channels: List<TvChannel> = emptyList()
    private var currentIndex = 0
    private var previousIndex = -1
    private var sourceIndex = 0
    private var numericBuffer = ""
    private var firstPlayPending = true
    private var playbackLayoutStarted = false
    private var userPaused = false
    private var guideModes: List<GuideMode> = listOf(GuideMode(GuideKind.ALL, "All"))
    private var guideModeIndex = 0
    private var favorites = linkedSetOf<String>()
    private var recent = mutableListOf<String>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val hideTouchControls = Runnable { touchControls.visibility = View.GONE }
    private val hideInfo = Runnable { infoBox.visibility = View.GONE }
    private val hideStatus = Runnable { status.visibility = View.GONE }
    private val tuneNumber = Runnable {
        val target = numericBuffer.toIntOrNull()
        numericBuffer = ""
        numberEntry.visibility = View.GONE
        if (target != null && target in 1..channels.size) tune(target - 1)
        else showStatus("Channel not found")
    }
    private val refreshPlaylistPeriodically = object : Runnable {
        override fun run() {
            refreshPlaylist(showMessage = false)
            handler.postDelayed(this, 30 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        favorites = loadStringSet("favorites")
        recent = loadStringList("recent").toMutableList()

        buildUi()
        buildPlayer()
        access = AccessController(this, if (isTv) "tv" else "mobile", { ::player.isInitialized && player.isPlaying }) { state, support, allowed ->
            extras.accessChanged()
            if (allowed) {
                if (!playbackLayoutStarted) {
                    playbackLayoutStarted = true
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                signupView.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    if (firstPlayPending || player.mediaItemCount == 0) { firstPlayPending = false; tune(currentIndex) }
                    else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); if (!userPaused) player.play() }

                }
            } else {
                tuneGuard.next()
                player.stop()
                signupView.visibility = View.VISIBLE
                signupView.render(state, support, access.userId)
            }
        }

        adapter = ChannelAdapter(
            onSelected = { index ->
                tune(index)
                hideGuide()
            },
            onFavorite = { index -> toggleFavorite(index) },
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        search.doAfterTextChanged { rebuildGuide(keepFocus = false) }

        val cached = repository.loadCached()
        if (cached.isNotEmpty()) {
            applyChannels(cached, autoplay = true)
        } else {
            showStatus("Loading TV channels…", persistent = true)
        }

        refreshPlaylist(showMessage = cached.isNotEmpty())
        handler.postDelayed({ updater.check(force = false) }, 4_000)
        handler.postDelayed(refreshPlaylistPeriodically, 30 * 60 * 1000L)
        registerNetworkCallback()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 8, 13)) }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false
            isFocusable = false
            keepScreenOn = true
            resizeMode = when (prefs.getString("screen_mode", "Stretch")) {
                "Fit" -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                "Fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView)

        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = panelDrawable()
            visibility = View.GONE
        }
        root.addView(
            infoBox,
            FrameLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(28), dp(28), 0, 0) }
        )

        numberText = text(12, Color.rgb(238, 51, 78), true)
        nameText = text(16, Color.WHITE, true).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metaText = text(11, Color.rgb(180, 187, 197), false)
        channelLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Channel logo" }
        infoBox.addView(channelLogo, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(10) })
        val channelIdentity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val channelHeading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        channelHeading.addView(numberText, LinearLayout.LayoutParams(dp(32), -2))
        channelHeading.addView(nameText, LinearLayout.LayoutParams(0, -2, 1f))
        channelIdentity.addView(channelHeading)
        channelIdentity.addView(metaText)
        infoBox.addView(channelIdentity, LinearLayout.LayoutParams(0, -2, 1f))

        numberEntry = text(30, Color.rgb(238, 51, 78), true).apply {
            background = panelDrawable()
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
        }
        root.addView(
            numberEntry,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply { setMargins(0, dp(28), dp(28), 0) }
        )

        guideScrim = View(this).apply {
            setBackgroundColor(Color.argb(25, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { hideGuide() }
        }
        root.addView(guideScrim, FrameLayout.LayoutParams(-1, -1))

        guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.argb(195, 10, 16, 23), Color.argb(225, 10, 16, 23))).apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), Color.argb(35, 255, 255, 255)) }
            isClickable = true
            visibility = View.GONE
        }
        root.addView(
            guide,
            FrameLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        )

        guideTitle = text(18, Color.WHITE, true)
        val guideHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        guideHeader.addView(guideTitle, LinearLayout.LayoutParams(0, -2, 1f))
        guideHeader.addView(ImageButton(this).apply {
            setImageResource(R.drawable.player_close); background = focusDrawable(); contentDescription = "Close channels"
            setPadding(dp(12), dp(12), dp(12), dp(12)); setOnClickListener { hideGuide() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        guide.addView(guideHeader)
        search = EditText(this).apply {
            hint = "Search channels"; setTextColor(Color.WHITE); setHintTextColor(Color.rgb(165, 175, 189))
            textSize = 13f; isSingleLine = true; setPadding(dp(12), 0, dp(12), 0)
            background = chipDrawable(false)
            val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(this@MainActivity, R.drawable.player_search)!!
            icon.setBounds(0, 0, dp(18), dp(18)); setCompoundDrawables(icon, null, null, null); compoundDrawablePadding = dp(8)
        }
        guide.addView(search, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        val tabs = LinearLayout(this)
        fun tab(label: String, kind: GuideKind?, action: () -> Unit) {
            val view = text(11, Color.WHITE, false).apply {
                text = label; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                background = focusDrawable(); setOnClickListener { action() }
                setOnFocusChangeListener { _, _ -> updateGuideTabs() }
            }
            guideTabs.add(view to kind)
            tabs.addView(view, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        }
        tab("All", GuideKind.ALL) { setGuideMode(GuideKind.ALL) }
        tab("★ Saved", GuideKind.FAVORITES) { setGuideMode(GuideKind.FAVORITES) }
        tab("Recent", GuideKind.RECENT) { setGuideMode(GuideKind.RECENT) }
        tab("Filter", GuideKind.CATEGORY) { showCategories() }
        guide.addView(tabs)
        guideHint = text(10, Color.rgb(165, 175, 189), false).apply { visibility = if (isTv) View.VISIBLE else View.GONE }
        guide.addView(guideHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        list = RecyclerView(this).apply {
            clipToPadding = true
            clipChildren = true
            setPadding(0, dp(8), 0, dp(8))
        }
        guide.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = dp(4) }
        )

        val footer = text(10, Color.rgb(104, 117, 135), false).apply {
            text = "RED Favorites   GREEN Recent   YELLOW Refresh   BLUE Update"
            gravity = Gravity.CENTER
        }
        if (isTv) guide.addView(footer)

        status = text(13, Color.WHITE, true).apply {
            background = panelDrawable()
            setPadding(dp(14), dp(9), dp(14), dp(9))
            visibility = View.GONE
        }
        root.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply { setMargins(dp(22), 0, 0, dp(22)) }
        )

        touchControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(225, 5, 8, 13)))
        }
        fun control(label: String, resource: Int, action: () -> Unit): Pair<ImageView, TextView> {
            val icon = ImageView(this).apply { setImageResource(resource); scaleType = ImageView.ScaleType.FIT_CENTER }
            val caption = text(10, Color.WHITE, false).apply { text = label; gravity = Gravity.CENTER }
            val button = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                contentDescription = label; background = focusDrawable()
                addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                setOnClickListener { action(); revealControls() }
            }
            touchControls.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
            return icon to caption
        }
        control("Previous", R.drawable.player_previous) { if (channels.isNotEmpty()) tune((currentIndex - 1 + channels.size) % channels.size) }
        val pause = control("Pause", R.drawable.player_pause) { togglePlayback() }
        pauseIcon = pause.first; pauseLabel = pause.second
        control("Next", R.drawable.player_next) { if (channels.isNotEmpty()) tune((currentIndex + 1) % channels.size) }
        control("Channels", R.drawable.player_channels) { showGuide() }
        screenLabel = control(prefs.getString("screen_mode", "Stretch") ?: "Stretch", R.drawable.player_screen) { showScreenModes() }.second
        control("Settings", R.drawable.player_settings) { showReceiverMenu() }
        root.addView(touchControls, FrameLayout.LayoutParams(-2, dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        touchControls.visibility = View.GONE
        playerView.setOnClickListener { if (isTv) showGuide() else { showInfo(); revealControls() } }

        guideTitle.setOnClickListener { cycleGuide(1) }
        signupView = SignupView(this, { access.register(it) }, { access.retry() })
        root.addView(signupView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        updateLayout()
    }

    private fun chipDrawable(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(if (selected) Color.argb(220, 205, 25, 53) else Color.argb(90, 18, 24, 33))
        setStroke(dp(1), if (selected) Color.rgb(238, 51, 78) else Color.argb(50, 255, 255, 255))
    }
    private fun focusDrawable() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), chipDrawable(true))
        addState(intArrayOf(android.R.attr.state_pressed), chipDrawable(true))
        addState(intArrayOf(), chipDrawable(false))
    }
    private fun updateGuideTabs() {
        val kind = guideModes.getOrNull(guideModeIndex)?.kind
        guideTabs.forEach { (view, target) -> view.background = if (target == kind || view.hasFocus()) chipDrawable(true) else focusDrawable() }
    }
    private fun togglePlayback() {
        if (!::access.isInitialized || !access.canPlay() || player.mediaItemCount == 0) return
        userPaused = player.playWhenReady
        if (userPaused) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
        updatePlaybackControl()
    }
    private fun updatePlaybackControl() {
        if (!::pauseIcon.isInitialized || !::player.isInitialized) return
        val playing = player.playWhenReady
        pauseIcon.setImageResource(if (playing) R.drawable.player_pause else R.drawable.player_play)
        pauseLabel.text = if (playing) "Pause" else "Play"
        (pauseIcon.parent as View).contentDescription = pauseLabel.text
    }
    private fun showScreenModes() {
        val labels = arrayOf("Stretch — full screen, no crop", "Fill — keep shape, crop edges", "Fit — full picture, allow borders")
        val modes = arrayOf("Stretch", "Fill", "Fit")
        val chosen = modes.indexOf(prefs.getString("screen_mode", "Stretch")).coerceAtLeast(0)
        activeDialog = AlertDialog.Builder(this).setTitle("Screen size").setSingleChoiceItems(labels, chosen) { dialog, index ->
            prefs.edit().putString("screen_mode", modes[index]).apply()
            playerView.resizeMode = when (index) { 1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> AspectRatioFrameLayout.RESIZE_MODE_FIT; else -> AspectRatioFrameLayout.RESIZE_MODE_FILL }
            screenLabel.text = modes[index]
            dialog.dismiss()
        }.create().apply { setOnDismissListener { if (activeDialog === this) activeDialog = null }; show() }
    }

    private fun revealControls() {
        if (guide.visibility == View.VISIBLE) return
        updatePlaybackControl()
        touchControls.visibility = View.VISIBLE
        handler.removeCallbacks(hideTouchControls)
        handler.postDelayed(hideTouchControls, 5000)
    }

    private fun updateLayout() {
        val width = resources.displayMetrics.widthPixels
        playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
        val guideWidth = minOf(dp(if (isTv) 380 else 340), (width * if (portraitMobile) 0.90f else 0.34f).toInt())
        guide.layoutParams = FrameLayout.LayoutParams(guideWidth, -1, Gravity.END)
        infoBox.layoutParams = FrameLayout.LayoutParams(minOf(dp(300), width - dp(32)), -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) }
        touchControls.layoutParams = FrameLayout.LayoutParams(minOf(dp(480), width - dp(24)), dp(68), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) }
        nameText.textSize = if (isTv) 17f else 14f


    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout()

    }
    private fun showReceiverMenu() {
        if (!::access.isInitialized || !access.canPlay()) return
        showStatus("Loading settings…")
        extras.refresh { renderSettingsMenu() }
    }
    private fun renderSettingsMenu() {
        if (!::access.isInitialized || !access.canPlay() || isFinishing) return
        extras.panel("Settings · " + BuildConfig.VERSION_NAME) { content, dismiss ->
            fun item(label: String, action: () -> Unit) {
                content.addView(extras.button(label) { dismiss(); action() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            item("Channels") { showGuide(GuideKind.ALL) }
            item("Favorites") { showGuide(GuideKind.FAVORITES) }
            item("Recent channels") { showGuide(GuideKind.RECENT) }
            item("Categories") { showCategories() }
            item("Search channels") { showGuide(); search.requestFocus(); showKeyboard() }
            item("Favorite current channel") { toggleFavorite(currentIndex) }
            item("Previous channel") { if (previousIndex >= 0) tune(previousIndex) }
            item("Screen size · " + (prefs.getString("screen_mode", "Stretch") ?: "Stretch")) { showScreenModes() }
            if (!isTv) item("Full screen / Auto rotate") { requestedOrientation = if (portraitMobile) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
            item("Refresh channels") { refreshPlaylist(true) }
            item("Live sports · Tapmad / FanCode ↗") { extras.showSports() }
            item("Check for update") { updater.check(true) }
            item("Admin panel ↗") { extras.openAdmin() }
            if (extras.donationEnabled()) item("♡ Donate · QR / Send Money") { extras.showDonation() }
            (if (access.adsAllowed) extras.sponsor() else null)?.let { ad ->
                val label = "Sponsored · " + ad.optString("title") + "\n" + ad.optString("message")
                item(label) { if (ad.optString("url").isNotBlank()) extras.openUrl(ad.optString("url")) }
            }
            item("Exit app") { finishAffinity() }
            extras.addAdMob(content)
        }
    }

    private fun showCategories() {
        val categories = guideModes.withIndex().filter { it.value.kind == GuideKind.CATEGORY }
        if (categories.isEmpty()) { showGuide(GuideKind.ALL); return }
        activeDialog = AlertDialog.Builder(this).setTitle("Categories")
            .setItems(categories.map { it.value.label }.toTypedArray()) { _, choice ->
                guideModeIndex = categories[choice].index
                showGuide()
            }.create().apply { setOnDismissListener { if (activeDialog === this) activeDialog = null }; show() }
    }

    private fun buildPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("XtremeX-TV-Android/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(httpFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                2_500,
                10_000,
                500,
                1_000
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )

        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { updatePlaybackControl() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (::access.isInitialized) access.heartbeat() }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showStatus("Connecting…")
                    Player.STATE_READY -> if (player.playWhenReady) showStatus(
                        if (sourceIndex > 0) "LIVE • Backup " + sourceIndex else "LIVE"
                    )
                    Player.STATE_ENDED -> retryFromPrimary()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val channel = channels.getOrNull(currentIndex) ?: return
                if (sourceIndex + 1 < channel.sources.size) {
                    sourceIndex += 1
                    showStatus("Primary unavailable • trying backup " + sourceIndex)
                    val retry = tuneGuard.next()
                    handler.postDelayed({ if (tuneGuard.valid(retry)) playCurrentSource() }, 300)
                } else {
                    showStatus("Signal unavailable • retrying")
                    val retry = tuneGuard.next()
                    handler.postDelayed({ if (tuneGuard.valid(retry)) retryFromPrimary() }, 2_500)
                }
            }
        })
    }

    private fun refreshPlaylist(showMessage: Boolean) {
        repository.refresh { result ->
            runOnUiThread {
                result.onSuccess { fresh ->
                    val shouldAutoplay = channels.isEmpty()
                    applyChannels(fresh, autoplay = shouldAutoplay)
                    if (showMessage) showStatus("Channel list updated")
                }.onFailure {
                    if (channels.isEmpty()) showStatus("Channel list unavailable", persistent = true)
                }
            }
        }
    }

    private fun applyChannels(newChannels: List<TvChannel>, autoplay: Boolean) {
        if (newChannels.isEmpty()) return

        val playingId = channels.getOrNull(currentIndex)?.id
        val selection = reconcileSelection(PlaybackSelection(playingId, channels.getOrNull(previousIndex)?.id,
            channels.getOrNull(currentIndex)?.sources?.getOrNull(sourceIndex)), newChannels)
        channels = newChannels
        rebuildModes()

        currentIndex = when {
            playingId != null ->
                channels.indexOfFirst { it.id == playingId }.takeIf { it >= 0 } ?: 0
            else -> {
                val savedId = prefs.getString("last_channel_id", null)
                val savedSource = prefs.getString("last_channel_src", null)
                channels.indexOfFirst { it.id == savedId }.takeIf { it >= 0 }
                    ?: channels.indexOfFirst { savedSource != null && it.sources.contains(savedSource) }
                        .takeIf { it >= 0 }
                    ?: 0
            }
        }

        previousIndex = channels.indexOfFirst { it.id == selection.previousId }
        sourceIndex = channels.getOrNull(currentIndex)?.sources?.indexOf(selection.source)?.takeIf { it >= 0 } ?: 0
        if (playingId != null && selection.retune) {
            tuneGuard.next()
            if (::access.isInitialized && access.canPlay()) playCurrentSource()
        }

        rebuildGuide(keepFocus = false)

        if (autoplay && firstPlayPending && ::access.isInitialized && access.canPlay()) {
            firstPlayPending = false
            tune(currentIndex)
        }
    }

    private fun tune(index: Int) {
        if (channels.isEmpty() || !::access.isInitialized || !access.canPlay()) return

        val next = ((index % channels.size) + channels.size) % channels.size
        if (next != currentIndex) previousIndex = currentIndex
        currentIndex = next
        sourceIndex = 0

        val channel = channels[currentIndex]
        prefs.edit()
            .putString("last_channel_id", channel.id)
            .putString("last_channel_src", channel.primarySource)
            .apply()

        rememberRecent(channel.id)
        userPaused = false
        playCurrentSource()
        showInfo()
    }

    private fun playCurrentSource() {
        if (!::access.isInitialized || !access.canPlay()) return
        tuneGuard.next()
        val channel = channels.getOrNull(currentIndex) ?: return
        val source = channel.sources.getOrNull(sourceIndex) ?: channel.primarySource

        player.stop()
        player.clearMediaItems()
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(source)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        )
        player.prepare()
        player.playWhenReady = !userPaused
    }

    private fun retryFromPrimary() {
        sourceIndex = 0
        playCurrentSource()
    }

    private fun showInfo() {
        val channel = channels.getOrNull(currentIndex) ?: return
        numberText.text = "%03d".format(currentIndex + 1)
        nameText.text = channel.name
        ChannelLogo.show(channelLogo, channel.logo)

        val source = channel.sources.getOrNull(sourceIndex) ?: channel.primarySource
        val sourceLabel = if (source.startsWith("http:")) "HTTP" else "LIVE"
        val backupLabel = if (channel.sources.size > 1) {
            " • " + channel.sources.size + " sources"
        } else {
            ""
        }
        val favoriteLabel = if (favorites.contains(channel.id)) " • ★ Favorite" else ""

        metaText.text = channel.category + " • " + sourceLabel + backupLabel + favoriteLabel
        infoBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        handler.postDelayed(hideInfo, 3_500)
    }

    private fun showGuide(mode: GuideKind? = null) {
        if (channels.isEmpty()) return

        mode?.let { target ->
            val found = guideModes.indexOfFirst { it.kind == target }
            if (found >= 0) guideModeIndex = found
        }

        val opening = guide.visibility != View.VISIBLE
        guideScrim.visibility = View.VISIBLE
        guide.visibility = View.VISIBLE
        if (opening) {
            guide.translationX = guide.layoutParams.width.toFloat()
            guide.animate().translationX(0f).setDuration(180).start()
        }
        touchControls.visibility = View.GONE
        infoBox.visibility = View.GONE
        status.visibility = View.GONE
        rebuildGuide(keepFocus = false)
        adapter.focusGlobalIndex(list, currentIndex)
    }

    private fun hideGuide() {
        guide.animate().cancel()
        guide.translationX = 0f
        guide.visibility = View.GONE
        guideScrim.visibility = View.GONE
        search.clearFocus()
        hideKeyboard()
    }

    private fun rebuildModes() {
        val currentLabel = guideModes.getOrNull(guideModeIndex)?.label
        val categories = channels.map { it.category }.filter { it.isNotBlank() }.distinct()

        guideModes = buildList {
            add(GuideMode(GuideKind.ALL, "All"))
            add(GuideMode(GuideKind.FAVORITES, "Favorites"))
            add(GuideMode(GuideKind.RECENT, "Recent"))
            categories.forEach { add(GuideMode(GuideKind.CATEGORY, it, it)) }
        }

        guideModeIndex = guideModes.indexOfFirst { it.label == currentLabel }.takeIf { it >= 0 } ?: 0
    }

    private fun rebuildGuide(keepFocus: Boolean) {
        if (!::adapter.isInitialized || channels.isEmpty()) return

        val mode = guideModes.getOrNull(guideModeIndex) ?: GuideMode(GuideKind.ALL, "All")
        val query = search.text?.toString().orEmpty().trim()
        val indexById = channels.withIndex().associate { it.value.id to it.index }

        var rows: List<ChannelRow> = when (mode.kind) {
            GuideKind.ALL -> channels.mapIndexed { index, channel -> ChannelRow(index, channel) }
            GuideKind.FAVORITES -> channels.mapIndexedNotNull { index, channel ->
                if (favorites.contains(channel.id)) ChannelRow(index, channel) else null
            }
            GuideKind.RECENT -> recent.mapNotNull { id ->
                val index = indexById[id] ?: return@mapNotNull null
                ChannelRow(index, channels[index])
            }
            GuideKind.CATEGORY -> channels.mapIndexedNotNull { index, channel ->
                if (channel.category == mode.category) ChannelRow(index, channel) else null
            }
        }

        if (query.isNotBlank()) {
            rows = rows.filter { row ->
                row.channel.name.contains(query, ignoreCase = true) ||
                    row.channel.category.contains(query, ignoreCase = true) ||
                    row.channel.group.orEmpty().contains(query, ignoreCase = true)
            }
        }

        adapter.submit(rows, favorites, currentIndex)
        guideTitle.text = (if (mode.kind == GuideKind.ALL) "Channels" else mode.label) + "  " + rows.size
        updateGuideTabs()
        guideHint.text = if (isTv) "← → Filter · OK Play · Hold OK ★" else "Tap to play · Hold for ★"

        if (!keepFocus) list.post { adapter.focusGlobalIndex(list, currentIndex) }
    }

    private fun cycleGuide(delta: Int) {
        if (guideModes.isEmpty()) return
        guideModeIndex = (guideModeIndex + delta + guideModes.size) % guideModes.size
        rebuildGuide(keepFocus = false)
    }

    private fun setGuideMode(kind: GuideKind) {
        val index = guideModes.indexOfFirst { it.kind == kind }
        if (index >= 0) {
            guideModeIndex = index
            rebuildGuide(keepFocus = false)
        }
    }

    private fun toggleFavorite(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        if (favorites.remove(channel.id)) {
            showStatus("Removed from Favorites")
        } else {
            favorites.add(channel.id)
            showStatus("Added to Favorites")
        }
        saveStringCollection("favorites", favorites.toList())
        rebuildGuide(keepFocus = true)
        showInfo()
    }

    private fun rememberRecent(id: String) {
        recent.remove(id)
        recent.add(0, id)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        saveStringCollection("recent", recent)
    }

    private fun showStatus(message: String, persistent: Boolean = false) {
        status.text = message
        status.visibility = View.VISIBLE
        handler.removeCallbacks(hideStatus)
        if (!persistent) handler.postDelayed(hideStatus, 1_800)
    }

    private fun handleNumber(number: Int) {
        if (numericBuffer.length >= 3) numericBuffer = ""
        numericBuffer += number.toString()
        numberEntry.text = numericBuffer
        numberEntry.visibility = View.VISIBLE

        handler.removeCallbacks(tuneNumber)
        handler.postDelayed(tuneNumber, 950)
    }

    private fun focusedGuideIndex(): Int? {
        val child = list.focusedChild ?: return null
        val position = list.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION) return null
        return adapter.globalIndexAt(position)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isTv && ::access.isInitialized && access.canPlay() && activeDialog == null && !updater.isShowingDialog) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureX = event.x; gestureY = event.y
                    gestureCanOpen = event.x >= resources.displayMetrics.widthPixels - dp(48) && guide.visibility != View.VISIBLE
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - gestureX
                    val dy = event.y - gestureY
                    val open = gestureCanOpen && dx < -dp(64)
                    val close = guide.visibility == View.VISIBLE && gestureX >= guide.left && dx > dp(64)
                    if ((open || close) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel); cancel.recycle()
                        if (open) showGuide() else hideGuide()
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (activeDialog?.isShowing == true || updater.isShowingDialog || !window.decorView.hasWindowFocus()) return super.dispatchKeyEvent(event)
        if (::signupView.isInitialized && signupView.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        if (guide.visibility == View.VISIBLE) {
            if (search.hasFocus() && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (search.hasFocus()) {
                        search.clearFocus()
                        hideKeyboard()
                        adapter.focusGlobalIndex(list, currentIndex)
                    } else {
                        hideGuide()
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> {
                    hideGuide()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    cycleGuide(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cycleGuide(1)
                    return true
                }
                KeyEvent.KEYCODE_PROG_RED -> {
                    setGuideMode(GuideKind.FAVORITES)
                    return true
                }
                KeyEvent.KEYCODE_PROG_GREEN -> {
                    setGuideMode(GuideKind.RECENT)
                    return true
                }
                KeyEvent.KEYCODE_PROG_YELLOW -> {
                    refreshPlaylist(showMessage = true)
                    return true
                }
                KeyEvent.KEYCODE_PROG_BLUE -> {
                    updater.check(force = true)
                    return true
                }
                KeyEvent.KEYCODE_INFO -> {
                    focusedGuideIndex()?.let(::toggleFavorite)
                    return true
                }
                KeyEvent.KEYCODE_SEARCH -> {
                    search.requestFocus()
                    showKeyboard()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_SETTINGS -> { showReceiverMenu(); return true }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                tune(currentIndex + 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                tune(currentIndex - 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_GUIDE -> {
                showGuide()
                return true
            }

            KeyEvent.KEYCODE_SEARCH -> {
                showGuide()
                search.requestFocus()
                showKeyboard()
                return true
            }

            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showInfo()
                return true
            }

            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (previousIndex >= 0) tune(previousIndex)
                return true
            }

            KeyEvent.KEYCODE_PROG_RED -> {
                showGuide(GuideKind.FAVORITES)
                return true
            }

            KeyEvent.KEYCODE_PROG_GREEN -> {
                showGuide(GuideKind.RECENT)
                return true
            }

            KeyEvent.KEYCODE_PROG_YELLOW -> {
                refreshPlaylist(showMessage = true)
                return true
            }

            KeyEvent.KEYCODE_PROG_BLUE -> {
                updater.check(force = true)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePlayback()
                showInfo()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                confirmExit()
                return true
            }

            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                handleNumber(event.keyCode - KeyEvent.KEYCODE_0)
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    private fun confirmExit() {
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Exit XtremeX TV?")
            .setMessage("Current channel will be remembered for next launch.")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Keep watching", null)
            .create().apply { setOnDismissListener { if (activeDialog === this) activeDialog = null }; show() }
    }

    private fun showKeyboard() {
        search.post {
            getSystemService<InputMethodManager>()
                ?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = getSystemService<ConnectivityManager>() ?: return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (::player.isInitialized && player.playerError != null && channels.isNotEmpty()) {
                        showStatus("Network restored")
                        retryFromPrimary()
                    }
                }
            }
        }

        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val callback = networkCallback ?: return
        val manager = getSystemService<ConnectivityManager>() ?: return
        runCatching { manager.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun loadStringSet(key: String): LinkedHashSet<String> =
        LinkedHashSet(loadStringList(key))

    private fun loadStringList(key: String): List<String> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveStringCollection(key: String, values: List<String>) {
        prefs.edit().putString(key, JSONArray(values).toString()).apply()
    }

    private fun text(sizeSp: Int, color: Int, bold: Boolean) = TextView(this).apply {
        setTextColor(color)
        textSize = sizeSp.toFloat()
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun panelDrawable() = GradientDrawable().apply {
        setColor(Color.argb(232, 11, 17, 27))
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.argb(40, 255, 255, 255))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        updater.onResume()
        if (::access.isInitialized) access.start()
    }

    override fun onStop() {
        super.onStop()
        updater.onStop()
        extras.suspendAds()
        if (::access.isInitialized) access.stop()
        if (::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        if (::player.isInitialized) player.release()
        repository.close()
        updater.close()
        if (::access.isInitialized) access.close()
        extras.close()
        super.onDestroy()
    }
}
