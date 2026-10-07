package com.xtremex.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val onSelected: (Int) -> Unit,
    private val onFavorite: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var rows: List<ChannelRow> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var playingIndex = -1

    fun submit(newRows: List<ChannelRow>, favoriteIds: Set<String>, currentIndex: Int) {
        playingIndex = currentIndex
        rows = newRows
        favorites = favoriteIds
        notifyDataSetChanged()
    }

    fun globalIndexAt(adapterPosition: Int): Int? =
        rows.getOrNull(adapterPosition)?.globalIndex

    fun focusGlobalIndex(recyclerView: RecyclerView, globalIndex: Int) {
        val position = rows.indexOfFirst { it.globalIndex == globalIndex }
        if (position < 0) {
            focusFirst(recyclerView)
            return
        }

        recyclerView.scrollToPosition(position)
        recyclerView.post {
            recyclerView.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
                ?: focusFirst(recyclerView)
        }
    }

    fun focusFirst(recyclerView: RecyclerView) {
        if (rows.isEmpty()) return
        recyclerView.scrollToPosition(0)
        recyclerView.post {
            recyclerView.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 52)
            )
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = rowBackground(false)
        }

        val tile = FrameLayout(context).apply { background = rowBackground(false) }
        val initials = TextView(context).apply { setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER }
        tile.addView(initials, FrameLayout.LayoutParams(-1, -1))
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
            setBackgroundColor(Color.rgb(18, 25, 34))
        }
        tile.addView(logo, FrameLayout.LayoutParams(-1, -1))
        row.addView(tile, LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply { marginEnd = dp(context, 8) })

        val number = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(number, LinearLayout.LayoutParams(dp(context, 30), ViewGroup.LayoutParams.WRAP_CONTENT))

        val name = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val favorite = ImageButton(context).apply {
            setImageResource(R.drawable.player_favorite_outline)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rowBackground(true))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            contentDescription = "Save favorite"
        }
        row.addView(favorite, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        return Holder(row, number, name, favorite, logo, initials)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val channel = row.channel
        val favorite = favorites.contains(channel.id)

        holder.number.setTextColor(if (row.globalIndex == playingIndex) Color.rgb(238, 51, 78) else Color.WHITE)
        holder.number.text = "%03d".format(row.globalIndex + 1)
        holder.name.text = channel.name
        holder.initials.text = channel.name.take(2).uppercase()
        holder.logo.contentDescription = channel.name + " logo"
        ChannelLogo.show(holder.logo, channel.logo)
        holder.favorite.setImageResource(if (favorite) R.drawable.player_favorite else R.drawable.player_favorite_outline)
        holder.favorite.contentDescription = (if (favorite) "Remove favorite: " else "Save favorite: ") + channel.name
        holder.favorite.setOnClickListener { onFavorite(row.globalIndex) }
        holder.itemView.background = rowBackground(holder.itemView.hasFocus(), row.globalIndex == playingIndex)
        holder.itemView.setOnClickListener { onSelected(row.globalIndex) }
        holder.itemView.setOnLongClickListener {
            onFavorite(row.globalIndex)
            true
        }
        holder.itemView.setOnFocusChangeListener { view, focused ->
            view.background = rowBackground(focused, row.globalIndex == playingIndex)
            view.animate()
                .scaleX(if (focused) 1.025f else 1f)
                .scaleY(if (focused) 1.025f else 1f)
                .setDuration(90)
                .start()
        }
    }

    class Holder(
        view: View,
        val number: TextView,
        val name: TextView,
        val favorite: ImageButton,
        val logo: ImageView,
        val initials: TextView,
    ) : RecyclerView.ViewHolder(view)

    private fun rowBackground(focused: Boolean, playing: Boolean = false) = GradientDrawable().apply {
        setColor(
            if (focused || playing) Color.argb(150, 105, 18, 36)
            else Color.argb(25, 11, 17, 27)
        )
        cornerRadius = 12f
        setStroke(
            if (focused) 2 else 1,
            if (focused || playing) Color.rgb(238, 51, 78) else Color.argb(20, 255, 255, 255)
        )
    }

    companion object {
        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
