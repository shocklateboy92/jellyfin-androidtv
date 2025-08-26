package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class SubtitleTrackInfo(
	val id: String,
	val displayName: String,
	val language: String? = null,
	val isForced: Boolean = false,
)

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend() {
	companion object {
		const val TS_SEARCH_BYTES_LM = TsExtractor.TS_PACKET_SIZE * 1800
		const val TS_SEARCH_BYTES_HM = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
	}

	private var currentStream: PlayableMediaStream? = null
	private var primarySubtitleView: SubtitleView? = null
	private var secondarySubtitleView: SubtitleView? = null
	private var audioPipeline = ExoPlayerAudioPipeline()
	
	private var selectedPrimaryTrackId: String? = null
	private var selectedSecondaryTrackId: String? = null

	private val assHandler by lazy {
		AssHandler(AssRenderType.OVERLAY)
	}

	private val exoPlayer by lazy {
		val dataSourceFactory = DefaultDataSource.Factory(
			context,
			exoPlayerOptions.baseDataSourceFactory,
		)
		val extractorsFactory = DefaultExtractorsFactory().apply {
			val isLowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
			setTsExtractorTimestampSearchBytes(
				when (isLowRamDevice) {
					true -> TS_SEARCH_BYTES_LM
					false -> TS_SEARCH_BYTES_HM
				}
			)
			setConstantBitrateSeekingEnabled(true)
			setConstantBitrateSeekingAlwaysEnabled(true)
		}

		val mediaSourceFactory = if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				setSubtitleParserFactory(assSubtitleParserFactory)
			}
		} else DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

		val renderersFactory = DefaultRenderersFactory(context).apply {
			setEnableDecoderFallback(true)
			setExtensionRendererMode(
				when (exoPlayerOptions.preferFfmpeg) {
					true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
					false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
				}
			)
		}.let { renderersFactory ->
			if (exoPlayerOptions.enableLibass) AssRenderersFactory(assHandler, renderersFactory)
			else renderersFactory
		}

		ExoPlayer.Builder(context)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
						}.build()
					)
					setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				})
			})
			.setMediaSourceFactory(mediaSourceFactory)
			.setAudioAttributes(AudioAttributes.Builder().apply {
				setUsage(C.USAGE_MEDIA)
			}.build(), true)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
				player.addListener(PlayerListener())

				if (exoPlayerOptions.enableDebugLogging) {
					player.addAnalyticsListener(EventLogger())
				}

				if (exoPlayerOptions.enableLibass) {
					assHandler.init(player)
				}
			}
	}

	inner class PlayerListener : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			val state = when {
				isPlaying -> PlayState.PLAYING
				exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED -> PlayState.STOPPED
				else -> PlayState.PAUSED
			}
			listener?.onPlayStateChange(state)
		}

		override fun onPlayerError(error: PlaybackException) {
			listener?.onPlayStateChange(PlayState.ERROR)
		}

		override fun onVideoSizeChanged(size: VideoSize) {
			if (size != VideoSize.UNKNOWN) {
				listener?.onVideoSizeChange(size.width, size.height)
			}
		}

		override fun onCues(cueGroup: CueGroup) {
			// Route cues based on track selection
			if (selectedPrimaryTrackId == null && selectedSecondaryTrackId == null) {
				// Default behavior: show all cues in primary view
				primarySubtitleView?.setCues(cueGroup.cues)
				secondarySubtitleView?.setCues(emptyList())
			} else {
				// Filter cues based on selection
				val primaryCues = if (selectedPrimaryTrackId != null) {
					// TODO: Filter cues by track ID when ExoPlayer provides track info in cues
					cueGroup.cues
				} else emptyList()
				
				val secondaryCues = if (selectedSecondaryTrackId != null && selectedSecondaryTrackId != selectedPrimaryTrackId) {
					// TODO: Filter cues by track ID when ExoPlayer provides track info in cues  
					cueGroup.cues
				} else emptyList()
				
				primarySubtitleView?.setCues(primaryCues)
				secondarySubtitleView?.setCues(secondaryCues)
			}
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			onIsPlayingChanged(exoPlayer.isPlaying)
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
				listener?.onMediaStreamEnd(requireNotNull(currentStream))
			}
		}

		override fun onAudioSessionIdChanged(audioSessionId: Int) {
			audioPipeline.setAudioSessionId(audioSessionId)
		}

		override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
			val queueEntry = mediaItem?.localConfiguration?.tag as? QueueEntry
			audioPipeline.normalizationGain = queueEntry?.normalizationGain
		}
	}

	override fun supportsStream(
		stream: MediaStream
	): PlaySupportReport = exoPlayer.getPlaySupportReport(stream.toFormats())

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		exoPlayer.setVideoSurfaceView(surfaceView?.surface)
	}

	override fun setPrimarySubtitleView(subtitleView: PlayerSubtitleView?) {
		if (subtitleView != null) {
			if (primarySubtitleView == null) {
				primarySubtitleView = SubtitleView(subtitleView.context).apply {
					if (exoPlayerOptions.enableLibass) {
						addView(AssSubtitleView(subtitleView.context, assHandler))
					}
				}
			}

			subtitleView.addView(primarySubtitleView)
		} else {
			(primarySubtitleView?.parent as? ViewGroup)?.removeView(primarySubtitleView)
			primarySubtitleView = null
		}
	}

	override fun setSecondarySubtitleView(subtitleView: PlayerSubtitleView?) {
		if (subtitleView != null) {
			if (secondarySubtitleView == null) {
				secondarySubtitleView = SubtitleView(subtitleView.context).apply {
					if (exoPlayerOptions.enableLibass) {
						addView(AssSubtitleView(subtitleView.context, assHandler))
					}
				}
			}

			subtitleView.addView(secondarySubtitleView)
		} else {
			(secondarySubtitleView?.parent as? ViewGroup)?.removeView(secondarySubtitleView)
			secondarySubtitleView = null
		}
	}

	override fun prepareItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		val mediaItem = MediaItem.Builder().apply {
			setTag(item)
			setMediaId(stream.hashCode().toString())
			setUri(stream.url)
		}.build()

		// Remove any old preloaded items (skips the first which is the playing item)
		while (exoPlayer.mediaItemCount > 1) exoPlayer.removeMediaItem(0)
		// Add new item
		exoPlayer.addMediaItem(mediaItem)

		exoPlayer.prepare()
	}

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		currentStream = stream

		val streamIsPrepared = (0 until exoPlayer.mediaItemCount).any { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		if (!streamIsPrepared) prepareItem(item)

		Timber.i("Playing ${item.mediaStream?.url}")
		exoPlayer.seekToNextMediaItem()
		exoPlayer.play()
	}

	override fun play() {
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
		exoPlayer.play()
	}

	override fun pause() {
		exoPlayer.pause()
	}

	override fun stop() {
		exoPlayer.stop()
		currentStream = null
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.seekTo(position.inWholeMilliseconds)
	}

	override fun setScrubbing(scrubbing: Boolean) {
		exoPlayer.isScrubbingModeEnabled = scrubbing
	}

	override fun setSpeed(speed: Float) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
			Timber.w("Trying to change speed but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.setPlaybackSpeed(speed)
	}

	override fun getPositionInfo(): PositionInfo = PositionInfo(
		active = exoPlayer.currentPosition.milliseconds,
		buffer = exoPlayer.bufferedPosition.milliseconds,
		duration = if (exoPlayer.duration == C.TIME_UNSET) Duration.ZERO else exoPlayer.duration.milliseconds,
	)

	fun setSubtitleTracks(primaryTrackId: String?, secondaryTrackId: String?) {
		selectedPrimaryTrackId = primaryTrackId
		selectedSecondaryTrackId = secondaryTrackId
		Timber.d("Subtitle tracks updated: primary=$primaryTrackId, secondary=$secondaryTrackId")
	}

	fun getAvailableSubtitleTracks(): List<SubtitleTrackInfo> {
		val trackSelector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return emptyList()
		val mappedTrackInfo = trackSelector.currentMappedTrackInfo ?: return emptyList()
		
		val tracks = mutableListOf<SubtitleTrackInfo>()
		
		for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
			if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_TEXT) {
				val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
				
				for (groupIndex in 0 until trackGroups.length) {
					val trackGroup = trackGroups[groupIndex]
					
					for (trackIndex in 0 until trackGroup.length) {
						val format = trackGroup.getFormat(trackIndex)
						tracks.add(
							SubtitleTrackInfo(
								id = "${rendererIndex}-${groupIndex}-${trackIndex}",
								displayName = format.label ?: format.language ?: "Track ${tracks.size + 1}",
								language = format.language,
								isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
							)
						)
					}
				}
			}
		}
		
		return tracks
	}
}
