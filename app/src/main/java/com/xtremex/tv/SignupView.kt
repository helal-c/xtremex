package com.xtremex.tv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.xtremex.tv.auth.AuthState

class SignupView(context: Context, private val register: (String) -> Unit, private val retry: () -> Unit) : LinearLayout(context) {
    private val heading = TextView(context)
    private val details = TextView(context)
    private val userId = EditText(context)
    private val signup = Button(context)
    private val recheck = Button(context)
    private val support = TextView(context)
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(15, 15, 20)); setPadding(dp(24), dp(16), dp(24), dp(16))
        val brand = TextView(context).apply { text = "XtremeX TV"; textSize = 30f; setTextColor(Color.rgb(238, 51, 78)); gravity = Gravity.CENTER }
        addView(brand)
        heading.textSize = 23f; heading.setTextColor(Color.WHITE); heading.gravity = Gravity.CENTER; addView(heading)
        details.textSize = 16f; details.setTextColor(Color.LTGRAY); details.gravity = Gravity.CENTER; addView(details)
        userId.hint = "User ID (example: rony)"; userId.isSingleLine = true
        userId.setTextColor(Color.WHITE); userId.setHintTextColor(Color.GRAY)
        userId.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        addView(userId, LayoutParams(dp(420).coerceAtMost(resources.displayMetrics.widthPixels - dp(48)), LayoutParams.WRAP_CONTENT))
        signup.text = "Request access"; signup.setOnClickListener {
            val value = userId.text.toString().trim()
            if (!value.matches(Regex("[A-Za-z0-9_.-]{3,32}"))) userId.error = "Use 3–32 letters, numbers, dot, underscore or hyphen"
            else register(value)
        }; addView(signup)
        recheck.text = "Check again"; recheck.setOnClickListener { retry() }; addView(recheck)
        support.textSize = 17f; support.setTextColor(Color.WHITE); support.gravity = Gravity.CENTER; addView(support)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    fun render(state: AuthState, number: String, id: String?) {
        val entering = state == AuthState.UNREGISTERED || state == AuthState.WRONG_DEVICE
        userId.visibility = if (entering) View.VISIBLE else View.GONE
        signup.visibility = if (entering) View.VISIBLE else View.GONE
        heading.text = when (state) {
            AuthState.UNREGISTERED -> "Request your TV access"
            AuthState.PENDING -> "Waiting for approval"
            AuthState.BLOCKED -> "Access blocked"
            AuthState.WRONG_DEVICE -> "User ID is already linked"
            else -> "Checking access"
        }
        details.text = when (state) {
            AuthState.UNREGISTERED -> "One User ID works on one TV or mobile. No password needed."
            AuthState.PENDING -> "${id ?: "Your request"}\nThe admin must approve this device."
            AuthState.BLOCKED -> "${id ?: "Your account"}\nContact support to restore access."
            AuthState.WRONG_DEVICE -> "Use another ID, or ask the admin to reset your old device."
            else -> "An online access check is required. Try again when your connection returns."
        }
        support.text = if (number.isBlank()) "" else "Support: $number"
        support.setOnClickListener { if (number.isNotBlank()) runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number))) } }
        if (entering) userId.requestFocus() else recheck.requestFocus()
    }
}
