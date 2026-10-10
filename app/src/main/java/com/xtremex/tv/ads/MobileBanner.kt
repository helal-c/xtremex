package com.xtremex.tv.ads

import android.app.Activity
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.xtremex.tv.BuildConfig
import org.json.JSONObject
import java.util.concurrent.Executors

/** AdMob is mobile-only; every request rechecks the server-bound account lease. */
class MobileBanner(private val activity: Activity, private val allowed: () -> Boolean) {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var epoch = 0
    private var host: FrameLayout? = null
    private var privacy: View? = null
    private var config = JSONObject()
    private var banner: AdView? = null
    private var consent: ConsentInformation? = null
    private var consentStarted = false
    private var consentReady = false
    private var initialized = false
    private var initializing = false
    private val tv get() = activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    private fun unit(): String? = AdPolicy.banner(tv, !closed && !activity.isFinishing && allowed(), true,
        config.optBoolean("enabled", false), config.optBoolean("testMode", true),
        BuildConfig.ADMOB_APP_ID, config.optString("appId"), config.optString("bannerUnitId"))

    fun attach(container: FrameLayout, privacyButton: View, settings: JSONObject) {
        detach(); host = container; privacy = privacyButton; config = settings
        container.visibility = View.GONE; privacyButton.visibility = View.GONE
        updatePrivacy()
        if (unit() == null) return
        val current = ++epoch
        // The dialog is shown after its content is constructed.
        container.post {
            if (!valid(current)) return@post
            val info = consent ?: UserMessagingPlatform.getConsentInformation(activity).also { consent = it }
            if (!consentStarted) {
                consentStarted = true
                info.requestConsentInfoUpdate(activity, ConsentRequestParameters.Builder().build(), {
                    if (!valid(current)) { if (epoch == current) consentStarted = false; return@requestConsentInfoUpdate }
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                        if (!valid(current)) return@loadAndShowConsentFormIfRequired
                        consentReady = true
                        updatePrivacy(); loadCurrent()
                    }
                    updatePrivacy()
                }, {
                    if (valid(current)) { consentReady = true; updatePrivacy(); loadCurrent() }
                    else if (epoch == current) consentStarted = false
                })
            }
            updatePrivacy()
            if (consentReady) loadCurrent()
        }
    }
    private fun valid(current: Int = epoch) = !closed && current == epoch && unit() != null && host?.isAttachedToWindow == true
    private fun updatePrivacy() {
        privacy?.visibility = if (!tv && consent?.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED) View.VISIBLE else View.GONE
    }
    fun showPrivacy() {
        if (tv || closed || consent == null || activity.isFinishing) return
        clearBanner()
        val current = epoch
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            if (valid(current)) { updatePrivacy(); loadCurrent() }
        }
    }
    private fun loadCurrent() {
        if (!valid() || !consentReady || consent?.canRequestAds() != true || banner != null) return
        if (!initialized) {
            if (initializing) return
            initializing = true
            val currentEpoch = epoch
            executor.execute {
                if (closed || epoch != currentEpoch) {
                    main.post { initializing = false }
                    return@execute
                }
                runCatching {
                    MobileAds.initialize(activity.applicationContext) {
                        main.post { initialized = true; initializing = false; if (valid(currentEpoch)) loadCurrent() }
                    }
                }.onFailure { main.post { initializing = false } }
            }
            return
        }
        val container = host ?: return
        val id = unit() ?: return
        val density = activity.resources.displayMetrics.density
        val width = ((activity.resources.displayMetrics.widthPixels / density).toInt() - 64).coerceIn(200, 396)
        val view = AdView(activity)
        view.adUnitId = id
        view.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, width))
        container.addView(view, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER))
        banner = view; container.visibility = View.VISIBLE
        view.loadAd(AdRequest.Builder().build())
    }
    fun accessChanged() { if (!valid()) clearBanner() }
    private fun clearBanner() {
        banner?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        banner = null; host?.visibility = View.GONE
    }
    fun detach() { epoch++; clearBanner(); host = null; privacy = null }
    fun close() { closed = true; detach(); executor.shutdownNow(); main.removeCallbacksAndMessages(null) }
}
