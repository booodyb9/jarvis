package com.jarvis.assistant.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.jarvis.assistant.R

/** A selectable cyan chip, tagged with [tag] so the selection can be read back. */
fun ChipGroup.addChoice(label: String, tag: String): Chip = Chip(context).apply {
    text = label
    this.tag = tag
    id = View.generateViewId()
    isCheckable = true
    isCheckedIconVisible = false
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    chipBackgroundColor = ColorStateList(states, intArrayOf(context.color(R.color.accent), context.color(R.color.surface_high)))
    setTextColor(ColorStateList(states, intArrayOf(context.color(R.color.on_accent), context.color(R.color.text))))
    chipStrokeColor = ColorStateList(states, intArrayOf(context.color(R.color.accent), context.color(R.color.stroke)))
    chipStrokeWidth = resources.displayMetrics.density
    this@addChoice.addView(this)
}

fun ChipGroup.select(tag: String) {
    for (i in 0 until childCount) (getChildAt(i) as Chip).let { it.isChecked = it.tag == tag }
}

val ChipGroup.selectedTag: String?
    get() = (0 until childCount).map { getChildAt(it) as Chip }.firstOrNull { it.isChecked }?.tag as? String

fun Context.color(id: Int) = ContextCompat.getColor(this, id)
