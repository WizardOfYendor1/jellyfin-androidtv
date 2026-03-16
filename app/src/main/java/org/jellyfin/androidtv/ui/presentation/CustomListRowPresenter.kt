package org.jellyfin.androidtv.ui.presentation

import android.view.View
import androidx.core.view.isVisible
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.RowPresenter

open class CustomListRowPresenter : ListRowPresenter {
	private val topPadding: Int?

	@JvmOverloads
	constructor(topPadding: Int? = null) : super() {
		this.topPadding = topPadding
	}

	protected constructor(topPadding: Int?, focusZoomFactor: Int, useFocusDimmer: Boolean)
		: super(focusZoomFactor, useFocusDimmer) {
		this.topPadding = topPadding
	}
	init {
		headerPresenter = CustomRowHeaderPresenter()
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)

		val view = holder.view?.parent as? View ?: return
		if (topPadding != null) view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)

		// Hide header view when the item doesn't have one
		holder.headerViewHolder.view.isVisible = !(item is ListRow && item.headerItem == null)
	}
}
