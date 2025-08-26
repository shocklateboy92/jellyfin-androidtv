package org.jellyfin.playback.core.backend

import androidx.core.view.doOnDetach
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView

/**
 * Service keeping track of the current playback backend and its related surface view.
 */
class BackendService {
	private var _backend: PlayerBackend? = null
	val backend get() = _backend

	private var listeners = mutableListOf<PlayerBackendEventListener>()
	private var _surfaceView: PlayerSurfaceView? = null
	private var _primarySubtitleView: PlayerSubtitleView? = null
	private var _secondarySubtitleView: PlayerSubtitleView? = null

	fun switchBackend(backend: PlayerBackend) {
		_backend?.stop()
		_backend?.setListener(null)
		_backend?.setSurfaceView(null)
		_backend?.setPrimarySubtitleView(null)
		_backend?.setSecondarySubtitleView(null)

		_backend = backend.apply {
			_surfaceView?.let(::setSurfaceView)
			_primarySubtitleView?.let(::setPrimarySubtitleView)
			_secondarySubtitleView?.let(::setSecondarySubtitleView)
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

	fun attachPrimarySubtitleView(subtitleView: PlayerSubtitleView) {
		// Remove existing primary subtitle view
		if (_primarySubtitleView != null) {
			_backend?.setPrimarySubtitleView(null)
		}

		// Apply new primary subtitle view
		_primarySubtitleView = subtitleView.apply {
			_backend?.setPrimarySubtitleView(subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _primarySubtitleView) {
					_primarySubtitleView = null
					_backend?.setPrimarySubtitleView(null)
				}
			}
		}
	}

	fun attachSecondarySubtitleView(subtitleView: PlayerSubtitleView) {
		// Remove existing secondary subtitle view
		if (_secondarySubtitleView != null) {
			_backend?.setSecondarySubtitleView(null)
		}

		// Apply new secondary subtitle view
		_secondarySubtitleView = subtitleView.apply {
			_backend?.setSecondarySubtitleView(subtitleView)

			// Automatically detach
			doOnDetach {
				if (subtitleView == _secondarySubtitleView) {
					_secondarySubtitleView = null
					_backend?.setSecondarySubtitleView(null)
				}
			}
		}
	}

	// Keep legacy method for backward compatibility
	@Deprecated("Use attachPrimarySubtitleView instead", ReplaceWith("attachPrimarySubtitleView(subtitleView)"))
	fun attachSubtitleView(subtitleView: PlayerSubtitleView) {
		attachPrimarySubtitleView(subtitleView)
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
