# Jellyfin Android TV - Subtitle Investigation and Dual Subtitle Implementation

## Current Subtitle Implementation

### Architecture Overview

The Jellyfin Android TV client uses a layered subtitle architecture with the following components:

1. **Core Layer**: `PlayerSubtitleView` (`playback/core/src/main/kotlin/ui/PlayerSubtitleView.kt:12`)
   - A `FrameLayout` that serves as the container for subtitle rendering
   - Automatically attaches to `PlaybackManager.backendService` when added to window

2. **Backend Integration**: `BackendService` (`playback/core/src/main/kotlin/backend/BackendService.kt:53`)
   - Manages subtitle view attachment to player backends via `attachSubtitleView()`
   - Handles automatic cleanup when views are detached
   - Only supports one subtitle view at a time currently

3. **ExoPlayer Backend**: (`playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt:188`)
   - Uses Media3's `SubtitleView` for standard subtitle formats
   - Optional ASS subtitle support via `AssSubtitleView` overlay for advanced styling
   - Receives subtitle cues from ExoPlayer and renders them via `setCues(cueGroup.cues)`

4. **Legacy Implementation**: `VideoManager.java`
   - Also handles subtitle styling and configuration
   - Contains ExoPlayer setup and subtitle view styling

### Current Rendering Mechanism

- **Single View**: Only one `PlayerSubtitleView` is attached per playback session
- **Standard Rendering**: ExoPlayer's `SubtitleView` handles most subtitle formats (SRT, VTT, TTML, etc.)
- **Advanced Rendering**: ASS subtitles use a separate `AssSubtitleView` overlay for complex styling
- **Positioning**: Subtitles are positioned at the bottom with configurable offset
- **Styling**: Comprehensive styling options including colors, fonts, backgrounds

### Supported Subtitle Formats

From `playback/media3/exoplayer/src/main/kotlin/mapping/subtitle.kt`:
- **Text-based**: SRT, VTT, TTML, SSA/ASS
- **Image-based**: PGS, VobSub, DVB subtitles
- **Container formats**: MP4, MKV embedded subtitles

### Configuration Options

The app supports extensive subtitle customization through `UserPreferences.kt`:

```kotlin
// Text appearance
var subtitlesTextColor = longPreference("subtitles_text_color", 0xFFFFFFFF)
var subtitleTextStrokeColor = longPreference("subtitles_text_stroke_color", 0xFF000000)  
var subtitlesTextSize = floatPreference("subtitles_text_size", 1f)

// Background
var subtitlesBackgroundColor = longPreference("subtitles_background_color", 0x00FFFFFF)

// Positioning  
var subtitlesOffsetPosition = floatPreference("subtitles_offset_position", 0.08f)
```

### Current UI Integration

In `VideoPlayerScreen.kt:50`, subtitles are rendered as an overlay:

```kotlin
PlayerSubtitles(
    playbackManager = playbackManager,
    modifier = Modifier
        .aspectRatio(aspectRatio, videoSize.height < videoSize.width)
        .fillMaxSize()
        .align(Alignment.Center)
)
```

## Approach for Dual Subtitle Rendering

### Option 1: Extend Current Architecture (Recommended)

This approach maintains compatibility with existing code while adding dual subtitle support.

#### Implementation Strategy

1. **Create Positioning-Aware Subtitle Views**
   ```kotlin
   // New positioning enum
   enum class SubtitlePosition { PRIMARY, SECONDARY }
   
   // Enhanced PlayerSubtitleView
   class PlayerSubtitleView(
       context: Context,
       private val position: SubtitlePosition = SubtitlePosition.PRIMARY
   ) : FrameLayout(context) {
       // Position-specific styling and layout
   }
   ```

2. **Backend Service Enhancement**
   ```kotlin
   class BackendService {
       private var _primarySubtitleView: PlayerSubtitleView? = null
       private var _secondarySubtitleView: PlayerSubtitleView? = null
       
       fun attachSubtitleView(subtitleView: PlayerSubtitleView, position: SubtitlePosition)
       fun attachPrimarySubtitleView(subtitleView: PlayerSubtitleView)
       fun attachSecondarySubtitleView(subtitleView: PlayerSubtitleView)
   }
   ```

3. **Player Backend Updates**
   ```kotlin
   interface PlayerBackend {
       fun setPrimarySubtitleView(surfaceView: PlayerSubtitleView?)
       fun setSecondarySubtitleView(surfaceView: PlayerSubtitleView?)
   }
   ```

4. **ExoPlayer Backend Implementation**
   ```kotlin
   class ExoPlayerBackend {
       private var primarySubtitleView: SubtitleView? = null
       private var secondarySubtitleView: SubtitleView? = null
       private var selectedPrimaryTrackId: String? = null
       private var selectedSecondaryTrackId: String? = null
       
       // Route specific tracks to specific views based on user selection
       private fun routeSubtitleTracks(cueGroup: CueGroup) {
           val primaryCues = cueGroup.cues.filter { /* matches primary track */ }
           val secondaryCues = cueGroup.cues.filter { /* matches secondary track */ }
           
           primarySubtitleView?.setCues(primaryCues)
           secondarySubtitleView?.setCues(secondaryCues)
       }
       
       fun setSelectedTracks(primaryTrackId: String?, secondaryTrackId: String?) {
           this.selectedPrimaryTrackId = primaryTrackId
           this.selectedSecondaryTrackId = secondaryTrackId
       }
   }
   ```

5. **UI Layer Updates**
   ```kotlin
   @Composable
   fun VideoPlayerScreen() {
       Box {
           // Video surface
           PlayerSurface(...)
           
           // Primary subtitles (bottom)
           PlayerSubtitles(
               position = SubtitlePosition.PRIMARY,
               modifier = Modifier.align(Alignment.BottomCenter)
           )
           
           // Secondary subtitles (top) - conditional
           if (secondarySubtitlesEnabled) {
               PlayerSubtitles(
                   position = SubtitlePosition.SECONDARY,
                   modifier = Modifier.align(Alignment.TopCenter)
               )
           }
       }
   }
   ```

#### User Preferences Extensions

```kotlin
// Secondary subtitle preferences
var enableSecondarySubtitles = booleanPreference("enable_secondary_subtitles", false)
var secondarySubtitlesTextColor = longPreference("secondary_subtitles_text_color", 0xFFFFFFFF)
var secondarySubtitlesTextSize = floatPreference("secondary_subtitles_text_size", 0.8f)
var secondarySubtitlesTopOffset = floatPreference("secondary_subtitles_top_offset", 0.1f)
var secondarySubtitlesBackgroundColor = longPreference("secondary_subtitles_background_color", 0x00FFFFFF)

// Track selection (stored per-item, not globally)
// primarySubtitleTrackId and secondarySubtitleTrackId would be stored in playback state
```

### Option 2: Custom Dual Subtitle View

Alternative approach using a single container view:

```kotlin
class DualPlayerSubtitleView : FrameLayout {
    private val primarySubtitleView: SubtitleView
    private val secondarySubtitleView: SubtitleView
    
    init {
        // Position primary at bottom
        primarySubtitleView = SubtitleView(context).apply {
            // Bottom positioning logic
        }
        
        // Position secondary at top  
        secondarySubtitleView = SubtitleView(context).apply {
            // Top positioning logic
        }
        
        addView(primarySubtitleView)
        addView(secondarySubtitleView)
    }
    
    fun setPrimaryCues(cues: List<Cue>) { primarySubtitleView.setCues(cues) }
    fun setSecondaryCues(cues: List<Cue>) { secondarySubtitleView.setCues(cues) }
}
```

## Implementation Considerations

### Track Management
- **Manual Selection Only**: User explicitly selects which track goes to primary vs secondary
- **Simple UI**: Two separate track selectors - one for primary, one for secondary subtitles

### Styling Independence
- Independent font sizes, colors, positions for each subtitle set
- Different background/stroke styling options
- Collision detection to prevent overlap

### Configuration Requirements
```kotlin
data class DualSubtitleConfig(
    val enabled: Boolean,
    val primaryTrackId: String?,    // Selected primary subtitle track
    val secondaryTrackId: String?,  // Selected secondary subtitle track
    val primaryConfig: SubtitleStyleConfig,
    val secondaryConfig: SubtitleStyleConfig,
    val overlapPrevention: Boolean
)
```

### UI/UX Considerations
- **Settings Screen**: New preference category for dual subtitles styling
- **Player Controls**: Quick toggle for secondary subtitles on/off
- **Track Selection**: Two separate subtitle track pickers (Primary/Secondary)
- **Conflict Resolution**: Handle overlapping subtitle timing with offset or fade

## Key Files to Modify

### Core Infrastructure
- `playback/core/src/main/kotlin/ui/PlayerSubtitleView.kt` - Core subtitle view component
- `playback/core/src/main/kotlin/backend/BackendService.kt` - Service management layer
- `playback/core/src/main/kotlin/backend/PlayerBackend.kt` - Backend interface

### Player Implementation  
- `playback/media3/exoplayer/src/main/kotlin/ExoPlayerBackend.kt` - ExoPlayer integration
- `playback/media3/exoplayer/src/main/kotlin/mapping/subtitle.kt` - Subtitle format handling

### UI Layer
- `app/src/main/java/org/jellyfin/androidtv/ui/player/video/VideoPlayerScreen.kt` - Main player UI
- `app/src/main/java/org/jellyfin/androidtv/ui/player/base/PlayerSubtitles.kt` - Subtitle composable

### Configuration
- `app/src/main/java/org/jellyfin/androidtv/preference/UserPreferences.kt` - User settings
- New preference screens for dual subtitle configuration

### Legacy Support
- `app/src/main/java/org/jellyfin/androidtv/ui/playback/VideoManager.java` - Legacy player support

## Simplified Implementation Plan

### Phase 1: Core Infrastructure
1. Extend `PlayerSubtitleView` with positioning support (PRIMARY/SECONDARY)
2. Update `BackendService` to manage two subtitle views simultaneously
3. Add basic user preferences for secondary subtitle styling

### Phase 2: Backend Integration  
1. Update `PlayerBackend` interface to support dual subtitle views
2. Implement track routing in `ExoPlayerBackend` based on manual track selection
3. Add methods to set which tracks go to which views

### Phase 3: UI Integration
1. Update `VideoPlayerScreen` with conditional secondary subtitle view
2. Create simple dual track selector UI (two dropdown menus)
3. Add toggle button in player controls for secondary subtitles

### Phase 4: Polish & Settings
1. Add comprehensive styling options for secondary subtitles
2. Implement overlap prevention (simple vertical offset)
3. Create settings screen for dual subtitle preferences

## Key Simplifications

- **Manual Track Selection Only**: No automatic language detection or content-type splitting
- **Simple Track Routing**: Direct mapping of selected track IDs to views
- **Basic UI**: Two separate track selectors instead of complex assignment logic
- **Minimal Configuration**: Focus on essential styling options first

## Conclusion

The current Jellyfin Android TV subtitle architecture is well-designed for extension to support dual subtitle rendering. **Option 1 (Extend Current Architecture)** is recommended as it:

- Maintains backward compatibility
- Leverages existing subtitle processing pipeline
- Allows independent styling and positioning
- Supports gradual implementation phases
- Integrates cleanly with the existing codebase structure

The modular design means dual subtitle support can be added incrementally without disrupting the current single subtitle functionality.