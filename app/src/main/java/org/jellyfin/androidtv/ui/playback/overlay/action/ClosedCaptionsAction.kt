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
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class ClosedCaptionsAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	private var popup: PopupMenu? = null
	private var isSecondaryMode = false

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
			with(menu) {
				var order = 0

				// Add static entry to toggle between primary/secondary mode
				add(1, -999, order++, if (isSecondaryMode) "Select primary subtitles" else "Select secondary subtitles").apply {
					isEnabled = true
				}

				// Add separator
				add(1, -998, order++, "────────────────").apply {
					isEnabled = false
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
					-999 -> {
						// Toggle mode
						isSecondaryMode = !isSecondaryMode
						// Reopen the popup in the new mode
						removePopup()
						handleClickAction(playbackController, videoPlayerAdapter, context, view)
					}
					-998 -> {
						// Separator - do nothing
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
