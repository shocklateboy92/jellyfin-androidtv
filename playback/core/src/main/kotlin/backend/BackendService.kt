package org.jellyfin.playback.core.backend

import androidx.core.view.doOnDetach
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.core.ui.SubtitlePosition

/**
 * Service keeping track of the current playback backend and its related surface view.
 */
class BackendService {
	private var _backend: PlayerBackend? = null
	val backend get() = _backend

	private var listeners = mutableListOf<PlayerBackendEventListener>()
	private var _surfaceView: PlayerSurfaceView? = null
	private var _subtitleViews = mutableMapOf<SubtitlePosition, PlayerSubtitleView>()

	fun switchBackend(backend: PlayerBackend) {
		_backend?.stop()
		_backend?.setListener(null)
		_backend?.setSurfaceView(null)
		_subtitleViews.keys.forEach { position ->
			_backend?.setSubtitleView(position, null)
		}

		_backend = backend.apply {
			_surfaceView?.let(::setSurfaceView)
			_subtitleViews.forEach { (position, view) ->
				setSubtitleView(position, view)
			}
			setListener(BackendEventListener())
		}
	}

	fun attachSurfaceView(surfaceView: PlayerSurfaceView) {
		// Remove existing surface view
		if (_surfaceView != null) {
			_backend?.setSurfaceView(null)
		}

		// Apply new surface view
		_surfaceView = surfaceView.apply {
			_backend?.setSurfaceView(surfaceView)

			// Automatically detach
			doOnDetach {
				if (surfaceView == _surfaceView) {
					_surfaceView = null
					_backend?.setSurfaceView(null)
				}
			}
		}
	}

	fun attachSubtitleView(subtitleView: PlayerSubtitleView, position: SubtitlePosition = SubtitlePosition.PRIMARY) {
		// Remove existing subtitle view for the given position
		_subtitleViews[position]?.let {
			_backend?.setSubtitleView(position, null)
		}

		// Apply new subtitle view
		_subtitleViews[position] = subtitleView.apply {
			_backend?.setSubtitleView(position, subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _subtitleViews[position]) {
					_subtitleViews.remove(position)
					_backend?.setSubtitleView(position, null)
				}
			}
		}
	}

	fun addListener(listener: PlayerBackendEventListener) {
		listeners.add(listener)
	}

	fun removeListener(listener: PlayerBackendEventListener) {
		listeners.remove(listener)
	}

	inner class BackendEventListener : PlayerBackendEventListener {
		private fun <T> callListeners(
			body: PlayerBackendEventListener.() -> T
		): List<T> = listeners.map { listener -> listener.body() }

		override fun onPlayStateChange(state: PlayState) {
			callListeners { onPlayStateChange(state) }
		}

		override fun onVideoSizeChange(width: Int, height: Int) {
			callListeners { onVideoSizeChange(width, height) }
		}

		override fun onMediaStreamEnd(mediaStream: PlayableMediaStream) {
			callListeners { onMediaStreamEnd(mediaStream) }
		}
	}
}
