# Dual Subtitle Testing Strategy

## Current Testing Infrastructure Analysis

### Existing Test Setup
The Jellyfin Android TV project has a **basic unit testing infrastructure** with:

- **Testing Framework**: [Kotest](https://kotest.io/) with JUnit5 runner
- **Mocking**: MockK for Kotlin 
- **Test Types**: Unit tests only (no Android instrumented/UI tests)
- **CI/CD**: GitHub Actions with automated test runs on PRs and master
- **Coverage**: Minimal - only 7 test files covering basic utilities and playback controls

### Test Infrastructure Components
```kotlin
// From build.gradle.kts
testImplementation(libs.kotest.runner.junit5)
testImplementation(libs.kotest.assertions) 
testImplementation(libs.mockk)

// GitHub Actions runs: ./gradlew test
```

### Existing Test Examples
- `VideoSpeedControllerTests.kt` - Unit tests with MockK for playback controls
- `TimeUtilsTests.kt` - Pure unit tests for utility functions
- `PreferenceStoreTests.kt` - Unit tests for preference management

## Testing Strategy for Dual Subtitles

### 1. **Unit Tests (Automated)**

#### Core Infrastructure Tests
**File**: `playback/core/src/test/kotlin/ui/PlayerSubtitleViewTests.kt`
```kotlin
class PlayerSubtitleViewTests : FunSpec({
    test("PlayerSubtitleView attaches to PlaybackManager correctly") {
        val mockPlaybackManager = mockk<PlaybackManager>(relaxed = true)
        val mockBackendService = mockk<BackendService>(relaxed = true)
        
        every { mockPlaybackManager.backendService } returns mockBackendService
        
        val subtitleView = PlayerSubtitleView(mockk(relaxed = true))
        subtitleView.playbackManager = mockPlaybackManager
        
        // Simulate onAttachedToWindow
        subtitleView.onAttachedToWindow()
        
        verify { mockBackendService.attachSubtitleView(subtitleView) }
    }
    
    test("PlayerSubtitleView supports positioning modes") {
        val primaryView = PlayerSubtitleView(mockk(relaxed = true), SubtitlePosition.PRIMARY)
        val secondaryView = PlayerSubtitleView(mockk(relaxed = true), SubtitlePosition.SECONDARY)
        
        primaryView.position shouldBe SubtitlePosition.PRIMARY
        secondaryView.position shouldBe SubtitlePosition.SECONDARY
    }
})
```

#### Backend Service Tests  
**File**: `playback/core/src/test/kotlin/backend/BackendServiceTests.kt`
```kotlin
class BackendServiceTests : FunSpec({
    test("BackendService manages dual subtitle views correctly") {
        val backendService = BackendService()
        val mockBackend = mockk<PlayerBackend>(relaxed = true)
        val primaryView = mockk<PlayerSubtitleView>(relaxed = true)
        val secondaryView = mockk<PlayerSubtitleView>(relaxed = true)
        
        backendService.switchBackend(mockBackend)
        
        // Test attaching primary subtitle view
        backendService.attachPrimarySubtitleView(primaryView)
        verify { mockBackend.setPrimarySubtitleView(primaryView) }
        
        // Test attaching secondary subtitle view
        backendService.attachSecondarySubtitleView(secondaryView) 
        verify { mockBackend.setSecondarySubtitleView(secondaryView) }
        
        // Test that both views are maintained
        backendService.attachPrimarySubtitleView(primaryView)
        backendService.attachSecondarySubtitleView(secondaryView)
        
        verify { mockBackend.setPrimarySubtitleView(primaryView) }
        verify { mockBackend.setSecondarySubtitleView(secondaryView) }
    }
})
```

#### ExoPlayer Backend Tests
**File**: `playback/media3/exoplayer/src/test/kotlin/ExoPlayerBackendTests.kt`
```kotlin
class ExoPlayerBackendTests : FunSpec({
    test("ExoPlayerBackend routes subtitle tracks correctly") {
        val backend = ExoPlayerBackend(mockk(relaxed = true), mockk(relaxed = true))
        val primaryView = mockk<PlayerSubtitleView>(relaxed = true)
        val secondaryView = mockk<PlayerSubtitleView>(relaxed = true)
        
        backend.setPrimarySubtitleView(primaryView)
        backend.setSecondarySubtitleView(secondaryView)
        backend.setSelectedTracks("track_1", "track_2")
        
        // Mock subtitle cues with different track IDs
        val cueGroup = mockk<CueGroup> {
            every { cues } returns listOf(
                mockk { every { trackId } returns "track_1" },
                mockk { every { trackId } returns "track_2" },
                mockk { every { trackId } returns "track_3" }
            )
        }
        
        // This would test the internal routing logic
        // backend.routeSubtitleTracks(cueGroup)
        
        // Verify correct cues sent to correct views
        // verify { primarySubtitleView.setCues(match { it.any { cue -> cue.trackId == "track_1" } }) }
        // verify { secondarySubtitleView.setCues(match { it.any { cue -> cue.trackId == "track_2" } }) }
    }
})
```

#### Preference Management Tests
**File**: `app/src/test/kotlin/preference/DualSubtitlePreferencesTests.kt`
```kotlin
class DualSubtitlePreferencesTests : FunSpec({
    test("Dual subtitle preferences store and retrieve correctly") {
        val userPreferences = mockk<UserPreferences>(relaxed = true)
        
        // Test secondary subtitle preferences
        every { userPreferences.enableSecondarySubtitles } returns true
        every { userPreferences.secondarySubtitlesTextColor } returns 0xFFFF0000
        every { userPreferences.secondarySubtitlesTextSize } returns 0.8f
        
        userPreferences.enableSecondarySubtitles shouldBe true
        userPreferences.secondarySubtitlesTextColor shouldBe 0xFFFF0000
        userPreferences.secondarySubtitlesTextSize shouldBe 0.8f
    }
    
    test("Dual subtitle configuration validation") {
        val config = DualSubtitleConfig(
            enabled = true,
            primaryTrackId = "track_1", 
            secondaryTrackId = "track_2",
            primaryConfig = mockk(relaxed = true),
            secondaryConfig = mockk(relaxed = true),
            overlapPrevention = true
        )
        
        config.enabled shouldBe true
        config.primaryTrackId shouldBe "track_1"
        config.secondaryTrackId shouldBe "track_2"
        config.overlapPrevention shouldBe true
    }
})
```

### 2. **Integration Tests (Semi-Automated)**

These would require Android Test infrastructure (currently missing from project):

#### Subtitle View Integration
```kotlin
// Would require adding androidTest source set and Espresso
@RunWith(AndroidJUnit4::class)
class DualSubtitleIntegrationTests {
    
    @Test
    fun testDualSubtitleViewsRenderCorrectly() {
        // Test that both subtitle views are positioned correctly
        // Test that different content appears in each view
        // Test styling independence
    }
    
    @Test  
    fun testSubtitleTrackSelection() {
        // Test track selection UI
        // Test that selected tracks route to correct views
    }
}
```

### 3. **Manual Testing Requirements**

#### Visual/UI Testing (Must be Manual)
1. **Subtitle Positioning**
   - Primary subtitles appear at bottom with correct offset
   - Secondary subtitles appear at top with correct offset  
   - No overlapping between primary and secondary
   - Proper alignment with video content

2. **Multiple Subtitle Formats**
   - SRT files display correctly in both views
   - VTT files display correctly in both views
   - ASS/SSA advanced styling works independently
   - Different languages display with correct fonts/encoding

3. **Styling Independence** 
   - Different colors for primary vs secondary
   - Different font sizes work correctly
   - Different background colors/opacity
   - Stroke/outline colors apply correctly

4. **Track Selection UI**
   - Two separate subtitle track dropdowns work
   - "None" option works for both tracks
   - Track changes update views immediately
   - UI reflects current selections accurately

#### Playback Scenarios (Must be Manual)
1. **Content Variety**
   - Movies with multiple subtitle languages
   - TV shows with forced subtitles + full subtitles
   - Content with signs/songs tracks + dialogue tracks
   - Live TV with closed captions

2. **Timing and Synchronization**
   - Both subtitle tracks stay synchronized with video
   - Seeking maintains correct subtitle positioning
   - Pause/resume doesn't affect subtitle display
   - Speed changes maintain synchronization

3. **Edge Cases**
   - Very long subtitle lines don't overlap
   - Rapid subtitle changes display correctly  
   - Subtitle tracks with different timing
   - Missing or corrupted subtitle tracks

#### Device/Platform Testing (Must be Manual)
1. **Android TV Devices**
   - Different screen sizes and resolutions
   - Different Android TV versions
   - Hardware-specific rendering differences
   - Performance impact on older devices

2. **Input Methods**
   - D-pad navigation in track selection
   - Remote control responsiveness
   - Voice search compatibility
   - Game controller input

### 4. **Performance Testing (Semi-Automated)**

#### Automated Performance Tests
```kotlin
class DualSubtitlePerformanceTests : FunSpec({
    test("Dual subtitle rendering doesn't impact playback performance") {
        val startTime = System.currentTimeMillis()
        
        // Setup dual subtitle views with mock content
        val primaryView = PlayerSubtitleView(mockk(relaxed = true))
        val secondaryView = PlayerSubtitleView(mockk(relaxed = true))
        
        // Simulate heavy subtitle rendering
        repeat(100) {
            primaryView.setCues(mockSubtitleCues(50))
            secondaryView.setCues(mockSubtitleCues(50))
        }
        
        val duration = System.currentTimeMillis() - startTime
        duration shouldBeLessThan 1000 // Should complete within 1 second
    }
    
    test("Memory usage doesn't increase significantly with dual subtitles") {
        val runtime = Runtime.getRuntime()
        val startMemory = runtime.totalMemory() - runtime.freeMemory()
        
        // Create and destroy dual subtitle views multiple times
        repeat(10) {
            val primaryView = PlayerSubtitleView(mockk(relaxed = true))
            val secondaryView = PlayerSubtitleView(mockk(relaxed = true))
            // Simulate usage and cleanup
        }
        
        System.gc()
        val endMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryIncrease = endMemory - startMemory
        
        memoryIncrease shouldBeLessThan (10 * 1024 * 1024) // Less than 10MB increase
    }
})
```

#### Manual Performance Testing
1. **CPU/GPU Usage**: Monitor system resources during dual subtitle playback
2. **Battery Impact**: Test battery drain with dual subtitles enabled
3. **Thermal Performance**: Check device heating during extended playback
4. **Network Impact**: Measure additional bandwidth for subtitle tracks

### 5. **Automated Test Implementation Plan**

#### Phase 1: Core Unit Tests
```bash
# New test files to create
playback/core/src/test/kotlin/ui/PlayerSubtitleViewTests.kt
playback/core/src/test/kotlin/backend/BackendServiceTests.kt  
playback/media3/exoplayer/src/test/kotlin/ExoPlayerBackendTests.kt
app/src/test/kotlin/preference/DualSubtitlePreferencesTests.kt
app/src/test/kotlin/ui/player/video/VideoPlayerScreenTests.kt
```

#### Phase 2: Integration Test Setup (Optional Enhancement)
```bash
# Would require adding to build.gradle.kts:
androidTestImplementation 'androidx.test.ext:junit:1.1.5'
androidTestImplementation 'androidx.test.espresso:espresso-core:3.5.1'
androidTestImplementation 'androidx.compose.ui:ui-test-junit4:$compose_version'

# New test files:
app/src/androidTest/kotlin/ui/player/DualSubtitleIntegrationTests.kt
```

#### Phase 3: Performance Testing
```bash
# Additional dependencies:
testImplementation 'org.junit.jupiter:junit-jupiter-params:5.9.2'
testImplementation 'org.openjdk.jmh:jmh-core:1.36'

# New test files:
playback/core/src/test/kotlin/performance/DualSubtitlePerformanceTests.kt
```

### 6. **Testing Workflow Integration**

#### GitHub Actions Enhancement
```yaml
# Addition to .github/workflows/app-test.yaml
- name: Run dual subtitle tests
  run: ./gradlew test --tests "*DualSubtitle*"

- name: Run performance tests  
  run: ./gradlew test --tests "*Performance*"
```

#### Manual Testing Checklist
Create comprehensive manual testing checklist:
```markdown
# Dual Subtitle Manual Testing Checklist

## Pre-Testing Setup
- [ ] Test device prepared with test content
- [ ] Multiple subtitle language files available
- [ ] Different subtitle formats ready (SRT, VTT, ASS)

## Core Functionality
- [ ] Primary subtitles display at bottom
- [ ] Secondary subtitles display at top  
- [ ] Track selection works for both channels
- [ ] Both tracks can be disabled independently
- [ ] Settings apply to correct subtitle channel

## Visual Quality
- [ ] No overlapping between subtitle sets
- [ ] Correct colors and sizes applied
- [ ] Background/stroke styles work independently
- [ ] Text remains readable in all scenarios

## Performance
- [ ] No stuttering or lag during playback
- [ ] Smooth seeking with dual subtitles
- [ ] No memory leaks over extended use
```

## **Recommended Testing Approach**

### **Automated Testing (60% Coverage)**
- **Unit Tests**: All core logic, preferences, track routing
- **Component Tests**: Individual subtitle view behavior  
- **Performance Tests**: Memory usage, rendering performance
- **CI Integration**: Run on every PR and merge

### **Manual Testing (40% Coverage)** 
- **Visual Validation**: Positioning, styling, readability
- **Device Compatibility**: Multiple Android TV devices
- **Content Variety**: Different subtitle formats and languages
- **User Experience**: Navigation, controls, edge cases

### **Testing Priority**
1. **High**: Core functionality unit tests (track selection, routing, preferences)
2. **High**: Manual visual validation (positioning, no overlap)
3. **Medium**: Performance and memory impact testing
4. **Medium**: Multiple device and content format testing
5. **Low**: Edge cases and stress testing

The existing project infrastructure supports adding comprehensive unit tests, but **manual testing will be essential** for validating the visual aspects and user experience of dual subtitle rendering.