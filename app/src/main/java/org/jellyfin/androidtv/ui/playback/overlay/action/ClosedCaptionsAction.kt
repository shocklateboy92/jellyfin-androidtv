package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.ui.playback.setSecondarySubtitleIndex
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.playback.core.ui.SubtitlePosition
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber


class ClosedCaptionsAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null
	private var position: SubtitlePosition = SubtitlePosition.PRIMARY

	companion object {
		private const val ITEM_ID_SWITCH_POSITION = -2
		private const val ITEM_ID_NONE = -1
	}

	init {
		initializeWithIcon(R.drawable.ic_select_subtitle)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		if (playbackController.currentStreamInfo == null) {
			Timber.w("StreamInfo null trying to obtain subtitles")
			Toast.makeText(context, "Unable to obtain subtitle info", Toast.LENGTH_LONG).show()
			return
		}

		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		removePopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			val currentSubtitleIndex = if (position == SubtitlePosition.SECONDARY) {
				playbackController.secondarySubtitleStreamIndex
			} else {
				playbackController.subtitleStreamIndex
			}
			with(menu) {
				var order = 0

				add(
					1,
					ITEM_ID_SWITCH_POSITION,
					order++,
					context.getString(if (position == SubtitlePosition.PRIMARY) R.string.lbl_subtitle_primary else R.string.lbl_subtitle_secondary)
				).apply {
					isCheckable = false
				}

				add(0, ITEM_ID_NONE, order++, context.getString(R.string.lbl_none)).apply {
					isChecked = currentSubtitleIndex == ITEM_ID_NONE
				}

				for (sub in playbackController.currentMediaSource.mediaStreams.orEmpty()) {
					if (sub.type != MediaStreamType.SUBTITLE) continue

					add(0, sub.index, order++, sub.displayTitle).apply {
						isChecked = sub.index == currentSubtitleIndex
					}
				}

				setGroupCheckable(0, true, false)
			}
			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				if (item.itemId == ITEM_ID_SWITCH_POSITION) {
					position = if (position == SubtitlePosition.PRIMARY) SubtitlePosition.SECONDARY else SubtitlePosition.PRIMARY
					// Re-open the popup to reflect the change
					handleClickAction(playbackController, videoPlayerAdapter, context, view)
				} else {
					if (position == SubtitlePosition.PRIMARY) {
						playbackController.setSubtitleIndex(item.itemId)
					} else {
						playbackController.setSecondarySubtitleIndex(item.itemId)
					}
				}
				true
			}
		}
		popup?.show()
	}

	fun removePopup() {
		popup?.dismiss()
	}
}
