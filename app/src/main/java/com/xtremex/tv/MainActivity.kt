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
import android.widget.Button
import com.xtremex.tv.auth.AccessController

class MainActivity : AppCompatActivity() {
    private enum class GuideKind { ALL, FAVORITES, RECENT, CATEGORY }
    private data class GuideMode(val kind: GuideKind, val label: String, val category: String? = null)

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var infoBox: LinearLayout
    private lateinit var touchControls: LinearLayout
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
    private val prefs by lazy { getSharedPreferences("xtremex-tv", MODE_PRIVATE) }

    private var channels: List<TvChannel> = emptyList()
    private var currentIndex = 0
    private var previousIndex = -1
    private var sourceIndex = 0
    private var numericBuffer = ""
    private var firstPlayPending = true
    private var playbackLayoutStarted = false
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
            if (allowed) {
                if (!playbackLayoutStarted) {
                    playbackLayoutStarted = true
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
                signupView.visibility = View.GONE
                if (channels.isNotEmpty()) {
                    if (firstPlayPending || player.mediaItemCount == 0) { firstPlayPending = false; tune(currentIndex) }
                    else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }

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
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
        }
        root.addView(playerView)

        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = panelDrawable()
            visibility = View.GONE
        }
        root.addView(
            infoBox,
            FrameLayout.LayoutParams(dp(650), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(28), dp(28), 0, 0) }
        )

        numberText = text(14, Color.rgb(238, 51, 78), true)
        nameText = text(25, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metaText = text(13, Color.rgb(138, 150, 168), false)
        channelLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Channel logo" }
        infoBox.addView(channelLogo, LinearLayout.LayoutParams(dp(64), dp(48)))
        infoBox.addView(numberText)
        infoBox.addView(nameText)
        infoBox.addView(metaText)

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
            setBackgroundColor(Color.argb(205, 7, 12, 19))
            isClickable = true
            visibility = View.GONE
        }
        root.addView(
            guide,
            FrameLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        )

        guideTitle = text(17, Color.rgb(238, 51, 78), true)
        val guideHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        guideHeader.addView(guideTitle, LinearLayout.LayoutParams(0, -2, 1f))
        guideHeader.addView(text(22, Color.WHITE, true).apply {
            text = "×"; gravity = Gravity.CENTER; contentDescription = "Close channels"
            setOnClickListener { hideGuide() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        guide.addView(guideHeader)
        val tabs = LinearLayout(this)
        listOf("All" to GuideKind.ALL, "★" to GuideKind.FAVORITES, "Recent" to GuideKind.RECENT).forEach { (label, kind) ->
            tabs.addView(text(12, Color.WHITE, false).apply {
                text = label; gravity = Gravity.CENTER; background = panelDrawable()
                setOnClickListener { setGuideMode(kind) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        tabs.addView(text(12, Color.WHITE, false).apply {
            text = "Filter"; gravity = Gravity.CENTER; background = panelDrawable()
            setOnClickListener { showCategories() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        guide.addView(tabs)

        guideHint = text(11, Color.rgb(138, 150, 168), false)
        guide.addView(
            guideHint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4) }
        )

        search = EditText(this).apply {
            hint = "Search channel"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 117, 136))
            textSize = 15f
            isSingleLine = true
            setPadding(dp(14), 0, dp(14), 0)
            background = panelDrawable()
        }
        guide.addView(
            search,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
                .apply { topMargin = dp(12) }
        )

        list = RecyclerView(this).apply {
            clipToPadding = false
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
            background = panelDrawable()
        }
        fun control(label: String, action: () -> Unit) {
            touchControls.addView(Button(this).apply { text = label; textSize = 12f; minWidth = 0; minimumWidth = 0; setPadding(dp(8), 0, dp(8), 0); setOnClickListener { action(); revealControls() } })
        }
        if (!isTv) {
            control("CH −") { if (channels.isNotEmpty()) tune((currentIndex - 1 + channels.size) % channels.size) }
            control("CH +") { if (channels.isNotEmpty()) tune((currentIndex + 1) % channels.size) }
        }
        control("Channels") { showGuide() }
        if (!isTv) control("⛶") {
            requestedOrientation = if (portraitMobile) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        control("Menu") { showReceiverMenu() }
        root.addView(touchControls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(16) })
        touchControls.visibility = View.GONE
        playerView.setOnClickListener { if (isTv) showReceiverMenu() else revealControls() }
        guideTitle.setOnClickListener { cycleGuide(1) }
        signupView = SignupView(this, { access.register(it) }, { access.retry() })
        root.addView(signupView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        updateLayout()
    }

    private fun revealControls() {
        if (guide.visibility == View.VISIBLE) return
        touchControls.visibility = View.VISIBLE
        handler.removeCallbacks(hideTouchControls)
        handler.postDelayed(hideTouchControls, 5000)
    }

    private fun updateLayout() {
        val width = resources.displayMetrics.widthPixels
        playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
        val guideWidth = minOf(dp(if (isTv) 360 else 320), (width * if (portraitMobile) 0.84f else 0.42f).toInt())
        guide.layoutParams = FrameLayout.LayoutParams(guideWidth, -1, Gravity.END)
        infoBox.layoutParams = FrameLayout.LayoutParams(minOf(dp(420), width - dp(32)), -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) }
        nameText.textSize = if (isTv) 22f else 17f
        channelLogo.visibility = View.GONE

    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateLayout()

    }
    private fun showReceiverMenu() {
        if (!::access.isInitialized || !access.canPlay()) return
        val labels = arrayOf("All channels", "Favorites", "Recent", "Categories", "Search", "Favorite current channel", "Previous channel", "Refresh channels", "Check update", "Full screen / Auto rotate", "Exit")
        activeDialog = AlertDialog.Builder(this).setTitle("XtremeX TV").setItems(labels) { _, choice ->
            when (choice) {
                0 -> showGuide(GuideKind.ALL)
                1 -> showGuide(GuideKind.FAVORITES)
                2 -> showGuide(GuideKind.RECENT)
                3 -> showCategories()
                4 -> { showGuide(); search.requestFocus(); showKeyboard() }
                5 -> toggleFavorite(currentIndex)
                6 -> if (previousIndex >= 0) tune(previousIndex)
                7 -> refreshPlaylist(true)
                8 -> updater.check(true)
                9 -> if (!isTv) requestedOrientation = if (portraitMobile) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                10 -> finishAffinity()
            }
        }.create().apply { setOnDismissListener { activeDialog = null }; show() }
    }

    private fun showCategories() {
        val categories = guideModes.withIndex().filter { it.value.kind == GuideKind.CATEGORY }
        if (categories.isEmpty()) { showGuide(GuideKind.ALL); return }
        activeDialog = AlertDialog.Builder(this).setTitle("Categories")
            .setItems(categories.map { it.value.label }.toTypedArray()) { _, choice ->
                guideModeIndex = categories[choice].index
                showGuide()
            }.create().apply { setOnDismissListener { activeDialog = null }; show() }
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
        player.playWhenReady = true
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
        guideTitle.text = mode.label.uppercase() + "  •  " + rows.size
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
                if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
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
            .create().apply { setOnDismissListener { activeDialog = null }; show() }
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
        super.onDestroy()
    }
}
