package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

private const val MODE_TOGGLE_ITEM_ID = -999

class ClosedCaptionsAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null

	init {
		initializeWithIcon(R.drawable.ic_select_subtitle)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		showSubtitleMenu(playbackController, videoPlayerAdapter, context, view, isSecondaryMode = false)
	}

	private fun showSubtitleMenu(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
		isSecondaryMode: Boolean,
	) {
		if (playbackController.currentStreamInfo == null) {
			Timber.w("StreamInfo null trying to obtain subtitles")
			Toast.makeText(context, R.string.msg_unable_load_subs, Toast.LENGTH_LONG).show()
			return
		}

		videoPlayerAdapter.leanbackOverlayFragment.setFading(false)
		removePopup()
		popup = PopupMenu(context, view, Gravity.END).apply {
			with(menu) {
				var order = 0

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					setForceShowIcon(true)
				}

				// Add option to switch to secondary mode (only when in primary mode)
				if (!isSecondaryMode) {
					add(1, MODE_TOGGLE_ITEM_ID, order++, context.getString(R.string.lbl_select_secondary_subtitles)).apply {
						isEnabled = true
						icon = ContextCompat.getDrawable(context, R.drawable.ic_select_subtitle)
					}
				}

				if (isSecondaryMode) {
					// Secondary subtitle selection mode
					val dualSubtitleManager = playbackController.videoManager?.dualSubtitleManager
					if (dualSubtitleManager != null) {
						val currentSelectedTrack = dualSubtitleManager.selectedTrack

						add(0, -1, order++, context.getString(R.string.lbl_none)).apply {
							isChecked = currentSelectedTrack == -1
						}

						val availableTracks = dualSubtitleManager.availableSubtitleTracks
						for ((index, track) in availableTracks.withIndex()) {
							add(0, index, order++, track.displayTitle ?: "Track ${index + 1}").apply {
								isChecked = index == currentSelectedTrack
							}
						}
					}
				} else {
					// Primary subtitle selection mode (existing behavior)
					add(0, -1, order++, context.getString(R.string.lbl_none)).apply {
						isChecked = playbackController.subtitleStreamIndex == -1
					}

					for (sub in playbackController.currentMediaSource.mediaStreams.orEmpty()) {
						if (sub.type != MediaStreamType.SUBTITLE) continue

						add(0, sub.index, order++, sub.displayTitle).apply {
							isChecked = sub.index == playbackController.subtitleStreamIndex
						}
					}
				}

				setGroupCheckable(0, true, false)
			}
			setOnDismissListener {
				videoPlayerAdapter.leanbackOverlayFragment.setFading(true)
				popup = null
			}
			setOnMenuItemClickListener { item ->
				when (item.itemId) {
					MODE_TOGGLE_ITEM_ID -> {
						// Switch to secondary mode
						removePopup()
						showSubtitleMenu(playbackController, videoPlayerAdapter, context, view, isSecondaryMode = true)
					}
					else -> {
						if (isSecondaryMode) {
							// Handle secondary subtitle selection
							val dualSubtitleManager = playbackController.videoManager?.dualSubtitleManager
							dualSubtitleManager?.setSelectedTrack(item.itemId)
						} else {
							// Handle primary subtitle selection (existing behavior)
							playbackController.setSubtitleIndex(item.itemId)
						}
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
