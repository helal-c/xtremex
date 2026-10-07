package com.xtremex.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val onSelected: (Int) -> Unit,
    private val onFavorite: (Int) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    private var rows: List<ChannelRow> = emptyList()
    private var favorites: Set<String> = emptySet()

    fun submit(newRows: List<ChannelRow>, favoriteIds: Set<String>) {
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
                dp(context, 66)
            )
            isFocusable = true
            isClickable = true
            isLongClickable = true
            background = rowBackground(false)
        }

        val number = TextView(context).apply {
            setTextColor(Color.rgb(244, 196, 93))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        row.addView(number, LinearLayout.LayoutParams(dp(context, 62), ViewGroup.LayoutParams.WRAP_CONTENT))

        val name = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val status = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.rgb(66, 233, 167))
        }
        row.addView(status, LinearLayout.LayoutParams(dp(context, 72), ViewGroup.LayoutParams.WRAP_CONTENT))

        return Holder(row, number, name, status)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val channel = row.channel
        val favorite = favorites.contains(channel.id)

        holder.number.text = "%03d".format(row.globalIndex + 1)
        holder.name.text = channel.name + "   " + channel.category
        holder.status.text = when {
            favorite -> "★"
            channel.sources.size > 1 -> "+" + (channel.sources.size - 1)
            channel.hasBdixSource -> "BDIX"
            else -> ""
        }

        holder.itemView.background = rowBackground(holder.itemView.hasFocus())
        holder.itemView.setOnClickListener { onSelected(row.globalIndex) }
        holder.itemView.setOnLongClickListener {
            onFavorite(row.globalIndex)
            true
        }
        holder.itemView.setOnFocusChangeListener { view, focused ->
            view.background = rowBackground(focused)
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
        val status: TextView,
    ) : RecyclerView.ViewHolder(view)

    private fun rowBackground(focused: Boolean) = GradientDrawable().apply {
        setColor(
            if (focused) Color.argb(170, 20, 68, 58)
            else Color.argb(170, 11, 17, 27)
        )
        cornerRadius = 12f
        setStroke(
            if (focused) 2 else 1,
            if (focused) Color.rgb(66, 233, 167) else Color.argb(38, 255, 255, 255)
        )
    }

    companion object {
        private fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }
}
