package org.jellyfin.androidtv.ui.presentation

import android.os.SystemClock
import android.view.KeyEvent
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter

/** Minimum interval (ms) between scroll events when the user holds a DPAD key. */
private const val KEY_REPEAT_THROTTLE_MS = 175L

/** Extra off-screen views kept in RecyclerView's cache to reduce bind overhead during scrolling. */
private const val ITEM_VIEW_CACHE_SIZE = 8

class PositionableListRowPresenter : CustomListRowPresenter {
	private var viewHolder: ViewHolder? = null
	private var pendingPosition: Int = -1
	private val trapFocus: Boolean
	private var lastScrollTime: Long = 0L

	constructor() : this(padding = null, trapFocus = false)
	constructor(padding: Int?) : this(padding, trapFocus = false)
	constructor(padding: Int? = null, trapFocus: Boolean = false) : super(padding) {
		this.trapFocus = trapFocus
	}

	init {
		shadowEnabled = false
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)
		if (holder !is ViewHolder) return

		viewHolder = holder
		val grid = holder.gridView
		if (trapFocus) {
			grid.setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)
			grid.setHasFixedSize(true)

			// Prevent focus from escaping the grid at either boundary so the user
			// stays inside the popup (channel changer / chapter selector).
			// Uses the adapter size to detect boundaries rather than hard-coding
			// position 0, so this works regardless of the adapter's centering strategy.
			// Held-key repeats are throttled so items don't scroll too fast to read.
			grid.setOnKeyInterceptListener { event ->
				val adapter = grid.adapter as? ObjectAdapter
				val pos = grid.selectedPosition
				val size = adapter?.size() ?: 0

				val isDpad = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
					event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

				// Throttle rapid key repeats so items don't fly by too fast to read
				if (isDpad && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
					val now = SystemClock.uptimeMillis()
					if (now - lastScrollTime < KEY_REPEAT_THROTTLE_MS) {
						return@setOnKeyInterceptListener true
					}
					lastScrollTime = now
				}

				when (event.keyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> pos <= 0
					KeyEvent.KEYCODE_DPAD_RIGHT -> size > 0 && pos >= size - 1
					else -> false
				}
			}
		}

		if (pendingPosition >= 0) {
			val pos = pendingPosition
			pendingPosition = -1
			// Defer until after layout so the grid has items to scroll to.
			grid.post {
				grid.selectedPosition = pos
			}
		}
	}

	override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
		if (holder === viewHolder) viewHolder = null
		super.onUnbindRowViewHolder(holder)
	}

	/**
	 * Clear the cached viewHolder so the next [position] set falls through
	 * to [pendingPosition]. Call this after removing a row from the adapter
	 * when RecyclerView defers the actual unbind to the next layout pass.
	 */
	fun invalidate() {
		viewHolder = null
	}

	var position: Int
		get() = viewHolder?.gridView?.selectedPosition ?: pendingPosition
		set(value) {
			val grid = viewHolder?.gridView
			if (grid != null && grid.isAttachedToWindow) {
				grid.selectedPosition = value
				pendingPosition = -1
			} else if (grid != null) {
				// Grid is bound but not yet attached to the window (e.g. the
				// row was just added to the adapter and layout hasn't run).
				// Post so the position is applied once the grid is laid out.
				pendingPosition = -1
				grid.post { grid.selectedPosition = value }
			} else {
				// Grid not bound yet — store for onBindRowViewHolder.
				pendingPosition = value
			}
		}
}
