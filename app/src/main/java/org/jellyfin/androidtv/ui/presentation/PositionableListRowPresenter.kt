package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter
import androidx.recyclerview.widget.RecyclerView

private const val ITEM_VIEW_CACHE_SIZE = 11
private const val MAX_PENDING_MOVES = 2
private const val SMOOTH_SCROLL_SPEED_FACTOR = 4.5f

/** Must exceed the DPAD auto-repeat interval (~50 ms) so brief IDLE gaps don't break [isScrolling]. */
private const val HELD_KEY_TIMEOUT_MS = 250L

class PositionableListRowPresenter : CustomListRowPresenter {
	private var viewHolder: ViewHolder? = null
	private var pendingPosition: Int = -1
	private val trapFocus: Boolean
	private var scrollState: Int = RecyclerView.SCROLL_STATE_IDLE
	private var lastKeyRepeatTime: Long = 0L

	/** True while the user is actively scrolling (scroll animation running or key recently held). */
	val isScrolling: Boolean
		get() = scrollState != RecyclerView.SCROLL_STATE_IDLE ||
			System.currentTimeMillis() - lastKeyRepeatTime < HELD_KEY_TIMEOUT_MS

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
			grid.setSmoothScrollMaxPendingMoves(MAX_PENDING_MOVES)
			grid.setSmoothScrollSpeedFactor(SMOOTH_SCROLL_SPEED_FACTOR)

			grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
					scrollState = newState
				}
			})

			// Trap focus at boundaries and track held-key timing for isScrolling.
			grid.setOnKeyInterceptListener { event ->
				val adapter = grid.adapter as? ObjectAdapter
				val pos = grid.selectedPosition
				val size = adapter?.size() ?: 0

				val isDpad = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
					event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

				if (isDpad && event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0) {
					lastKeyRepeatTime = System.currentTimeMillis()
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
			grid.post { grid.selectedPosition = pos }
		}
	}

	override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
		if (holder === viewHolder) viewHolder = null
		super.onUnbindRowViewHolder(holder)
	}

	/** Clear cached viewHolder so the next [position] set uses [pendingPosition]. */
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
				pendingPosition = -1
				grid.post { grid.selectedPosition = value }
			} else {
				pendingPosition = value
			}
		}
}
