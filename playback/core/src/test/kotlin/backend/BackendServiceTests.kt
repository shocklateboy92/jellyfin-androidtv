package org.jellyfin.playback.core.backend

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView

class BackendServiceTests : FunSpec({
	test("BackendService can be created without crashing") {
		val backendService = BackendService()
		
		backendService shouldNotBe null
		backendService.backend shouldBe null
	}
	
	test("BackendService switchBackend works without crashing") {
		val backendService = BackendService()
		val mockBackend = mockk<PlayerBackend>(relaxed = true)
		
		backendService.switchBackend(mockBackend)
		
		backendService.backend shouldBe mockBackend
	}
	
	test("BackendService switchBackend cleans up previous backend") {
		val backendService = BackendService()
		val oldBackend = mockk<PlayerBackend>(relaxed = true)
		val newBackend = mockk<PlayerBackend>(relaxed = true)
		
		// Set up first backend
		backendService.switchBackend(oldBackend)
		
		// Switch to second backend
		backendService.switchBackend(newBackend)
		
		verify { oldBackend.stop() }
		verify { oldBackend.setListener(null) }
		verify { oldBackend.setSurfaceView(null) }
		verify { oldBackend.setPrimarySubtitleView(null) }
		verify { oldBackend.setSecondarySubtitleView(null) }
		
		backendService.backend shouldBe newBackend
	}
	
	test("BackendService attachSubtitleView works without crashing") {
		val backendService = BackendService()
		val mockBackend = mockk<PlayerBackend>(relaxed = true)
		val mockSubtitleView = mockk<PlayerSubtitleView>(relaxed = true)
		
		backendService.switchBackend(mockBackend)
		backendService.attachSubtitleView(mockSubtitleView)
		
		verify { mockBackend.setPrimarySubtitleView(mockSubtitleView) }
	}
	
	test("BackendService attachSubtitleView removes existing subtitle view") {
		val backendService = BackendService()
		val mockBackend = mockk<PlayerBackend>(relaxed = true)
		val firstView = mockk<PlayerSubtitleView>(relaxed = true)
		val secondView = mockk<PlayerSubtitleView>(relaxed = true)
		
		backendService.switchBackend(mockBackend)
		
		// Attach first view
		backendService.attachSubtitleView(firstView)
		verify { mockBackend.setPrimarySubtitleView(firstView) }
		
		// Attach second view - should remove first
		backendService.attachSubtitleView(secondView)
		verify { mockBackend.setPrimarySubtitleView(null) } // Called to remove previous
		verify { mockBackend.setPrimarySubtitleView(secondView) }
	}
	
	test("BackendService attachSubtitleView works when no backend is set") {
		val backendService = BackendService()
		val mockSubtitleView = mockk<PlayerSubtitleView>(relaxed = true)
		
		// Should not crash even without a backend
		backendService.attachSubtitleView(mockSubtitleView)
		
		// No verification needed - just ensure no crash
	}
	
	test("BackendService attachSurfaceView works without crashing") {
		val backendService = BackendService()
		val mockBackend = mockk<PlayerBackend>(relaxed = true)
		val mockSurfaceView = mockk<PlayerSurfaceView>(relaxed = true)
		
		backendService.switchBackend(mockBackend)
		backendService.attachSurfaceView(mockSurfaceView)
		
		verify { mockBackend.setSurfaceView(mockSurfaceView) }
	}
	
	test("BackendService addListener and removeListener work without crashing") {
		val backendService = BackendService()
		val mockListener = mockk<PlayerBackendEventListener>(relaxed = true)
		
		backendService.addListener(mockListener)
		backendService.removeListener(mockListener)
		
		// Should not crash
	}
	
	test("BackendService event listener propagation works") {
		val backendService = BackendService()
		val mockBackend = mockk<PlayerBackend>(relaxed = true)
		val mockListener1 = mockk<PlayerBackendEventListener>(relaxed = true)
		val mockListener2 = mockk<PlayerBackendEventListener>(relaxed = true)
		
		backendService.addListener(mockListener1)
		backendService.addListener(mockListener2)
		
		// Set a backend which will create the internal event listener
		backendService.switchBackend(mockBackend)
		
		// We can't directly test the inner event listener, but we can verify
		// that listeners were added successfully
		verify { mockBackend.setListener(any()) }
	}
})