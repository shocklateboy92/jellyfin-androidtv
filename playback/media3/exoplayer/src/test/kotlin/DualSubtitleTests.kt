package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.media3.exoplayer.SubtitleTrackInfo

class DualSubtitleTests : FunSpec({
    test("SubtitleTrackInfo can be created without crashing") {
        val trackInfo = SubtitleTrackInfo(
            id = "test-track",
            displayName = "Test Track",
            language = "en",
            isForced = false
        )
        
        trackInfo.id shouldBe "test-track"
        trackInfo.displayName shouldBe "Test Track"
        trackInfo.language shouldBe "en"
        trackInfo.isForced shouldBe false
    }
    
    test("SubtitleTrackInfo handles null language") {
        val trackInfo = SubtitleTrackInfo(
            id = "test-track",
            displayName = "Test Track"
        )
        
        trackInfo.language shouldBe null
        trackInfo.isForced shouldBe false
    }
    
    test("SubtitleTrackInfo handles forced tracks") {
        val trackInfo = SubtitleTrackInfo(
            id = "forced-track",
            displayName = "Forced Track",
            isForced = true
        )
        
        trackInfo.isForced shouldBe true
    }
})