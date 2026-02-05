package org.jellyfin.androidtv.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.subrip.SubripParser;
import androidx.media3.extractor.text.webvtt.WebvttParser;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.sdk.api.client.ApiClient;
// Removed unused import - using direct HTTP calls instead
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.koin.java.KoinJavaComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import timber.log.Timber;

/**
 * Manages dual subtitle display by fetching and parsing the first available subtitle track
 * independently of ExoPlayer's primary subtitle selection.
 */
@OptIn(markerClass = UnstableApi.class)
public class DualSubtitleManager {
    // Retry configuration matching ExoPlayer's DefaultLoadErrorHandlingPolicy
    private static final int MAX_RETRY_COUNT = 5;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 5000;

    private final Context context;
    private final UserPreferences userPreferences;
    private final Handler mainHandler;
    private final ExecutorService backgroundExecutor;
    private final OkHttpClient httpClient;

    private SubtitleView secondarySubtitleView;
    private MediaStream selectedSubtitleTrack;
    private ApiClient apiClient;
    private StreamInfo streamInfo;
    private int selectedTrackId = -1; // -1 means disabled
    private List<MediaStream> availableSubtitleTracks = new ArrayList<>();

    private final List<TimedCueGroup> currentTimedCueGroups = new ArrayList<>();

    // Custom wrapper for cues with timing information
    private static class TimedCueGroup {
        public final long startTimeUs;
        public final long endTimeUs;
        public final List<Cue> cues;

        public TimedCueGroup(long startTimeUs, long endTimeUs, List<Cue> cues) {
            this.startTimeUs = startTimeUs;
            this.endTimeUs = endTimeUs;
            this.cues = new ArrayList<>(cues);
        }
    }

    public DualSubtitleManager(@NonNull Context context) {
        this.context = context;
        this.userPreferences = KoinJavaComponent.get(UserPreferences.class);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.backgroundExecutor = Executors.newSingleThreadExecutor();
        // Configure HTTP client with longer timeouts for subtitle fetching
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Initialize the dual subtitle system with media information
     */
    public void initialize(ApiClient api, StreamInfo streamInfo) {
        this.apiClient = api;
        this.streamInfo = streamInfo;

        // Collect all available subtitle tracks
        collectAvailableSubtitleTracks();

        if (!availableSubtitleTracks.isEmpty()) {
            // Auto-select Japanese subtitles as secondary if primary isn't Japanese
            autoSelectDefaultSecondaryTrack();

            // Set the selected track based on selectedTrackId
            updateSelectedTrack();

            Timber.d("DualSubtitleManager: Found %d subtitle tracks, selected track ID: %d",
                    availableSubtitleTracks.size(), selectedTrackId);
        } else {
            Timber.d("DualSubtitleManager: No subtitle tracks found");
        }
    }

    /**
     * Auto-select Japanese subtitles as secondary track by default,
     * unless the primary subtitle is already Japanese.
     */
    private void autoSelectDefaultSecondaryTrack() {
        if (streamInfo == null || availableSubtitleTracks.isEmpty()) return;

        // Get the primary subtitle language
        String primarySubtitleLanguage = getPrimarySubtitleLanguage();
        Timber.d("DualSubtitleManager: Primary subtitle language: %s", primarySubtitleLanguage);

        // If primary is already Japanese, don't auto-select secondary
        if (isJapanese(primarySubtitleLanguage)) {
            Timber.d("DualSubtitleManager: Primary subtitle is Japanese, not auto-selecting secondary");
            selectedTrackId = -1;
            return;
        }

        // Find the first Japanese text-based subtitle track
        for (int i = 0; i < availableSubtitleTracks.size(); i++) {
            MediaStream track = availableSubtitleTracks.get(i);
            if (isJapanese(track.getLanguage())) {
                selectedTrackId = i;
                Timber.d("DualSubtitleManager: Auto-selected Japanese subtitle as secondary - Index: %d, Title: %s",
                        track.getIndex(), track.getTitle());
                return;
            }
        }

        // No Japanese track found, keep disabled
        Timber.d("DualSubtitleManager: No Japanese subtitle track found for auto-selection");
        selectedTrackId = -1;
    }

    /**
     * Get the language of the primary (default) subtitle track
     */
    @Nullable
    private String getPrimarySubtitleLanguage() {
        if (streamInfo == null || streamInfo.getMediaSource() == null) return null;

        Integer primaryIndex = streamInfo.getMediaSource().getDefaultSubtitleStreamIndex();
        if (primaryIndex == null || primaryIndex < 0) return null;

        for (MediaStream stream : streamInfo.getMediaSource().getMediaStreams()) {
            if (stream.getType() == MediaStreamType.SUBTITLE && stream.getIndex() == primaryIndex) {
                return stream.getLanguage();
            }
        }
        return null;
    }

    /**
     * Check if a language code represents Japanese
     */
    private boolean isJapanese(@Nullable String language) {
        if (language == null) return false;
        String lang = language.toLowerCase();
        return lang.equals("ja") || lang.equals("jpn") || lang.equals("japanese");
    }

    /**
     * Create and attach the secondary subtitle view to the player container
     */
    public void attachSecondarySubtitleView(@NonNull FrameLayout playerContainer, @NonNull CaptionStyleCompat style) {
        if (secondarySubtitleView == null) {
            secondarySubtitleView = new SubtitleView(context);
            float fractionalTextSize = 0.0533f * userPreferences.get(UserPreferences.Companion.getSubtitlesTextSize());
            secondarySubtitleView.setFractionalTextSize(fractionalTextSize);
            // Secondary subtitles are positioned via explicit Cue positioning
            secondarySubtitleView.setStyle(style);
            secondarySubtitleView.setApplyEmbeddedStyles(true);
        }

        // Remove from previous parent if attached
        if (secondarySubtitleView.getParent() != null) {
            ((FrameLayout) secondarySubtitleView.getParent()).removeView(secondarySubtitleView);
        }

        // Add to new container
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        playerContainer.addView(secondarySubtitleView, params);

        Timber.d("DualSubtitleManager: Secondary subtitle view attached");
    }

    /**
     * Update subtitle display based on current playback position
     */
    public void updateSubtitles(long positionMs) {
        if (selectedTrackId == -1 || secondarySubtitleView == null || currentTimedCueGroups.isEmpty()) return;

        // Convert position to microseconds for comparison
        long positionUs = positionMs * 1000;

        // Filter cues for current position based on timing
        List<Cue> activeCues = new ArrayList<>();
        for (TimedCueGroup timedCueGroup : currentTimedCueGroups) {
            // Check if current position falls within this cue's time range
            if (positionUs >= timedCueGroup.startTimeUs && positionUs <= timedCueGroup.endTimeUs) {
                activeCues.addAll(timedCueGroup.cues);
            }
        }

        // Update subtitle view on main thread
        mainHandler.post(() -> {
            if (secondarySubtitleView != null) {
                secondarySubtitleView.setCues(activeCues);
            }
        });
    }

    /**
     * Clean up resources
     */
    public void destroy() {
        if (secondarySubtitleView != null && secondarySubtitleView.getParent() != null) {
            ((FrameLayout) secondarySubtitleView.getParent()).removeView(secondarySubtitleView);
            secondarySubtitleView = null;
        }

        currentTimedCueGroups.clear();
        backgroundExecutor.shutdown();

        Timber.d("DualSubtitleManager: Destroyed");
    }

    /**
     * Set which subtitle track to display on secondary view
     *
     * @param trackId The index of the subtitle track to display, or -1 to disable
     */
    public void setSelectedTrack(int trackId) {
        if (this.selectedTrackId == trackId) return; // No change needed

        this.selectedTrackId = trackId;

        // Clear current subtitle content
        currentTimedCueGroups.clear();

        if (secondarySubtitleView != null) {
            mainHandler.post(() -> {
                if (secondarySubtitleView != null) {
                    secondarySubtitleView.setCues(new ArrayList<>());
                }
            });
        }

        // Update selected track and fetch new content
        updateSelectedTrack();

        Timber.d("DualSubtitleManager: Selected track changed to ID: %d", trackId);
    }

    /**
     * Get the currently selected subtitle track ID
     *
     * @return The selected track ID, or -1 if disabled
     */
    public int getSelectedTrack() {
        return selectedTrackId;
    }

    /**
     * Get list of available subtitle tracks
     *
     * @return List of available subtitle tracks
     */
    public List<MediaStream> getAvailableSubtitleTracks() {
        return new ArrayList<>(availableSubtitleTracks);
    }

    private void collectAvailableSubtitleTracks() {
        availableSubtitleTracks.clear();

        if (streamInfo == null) return;

        for (MediaStream stream : streamInfo.getMediaSource().getMediaStreams()) {
            if (stream.getType() == MediaStreamType.SUBTITLE) {
                if (!isTextBasedSubtitle(stream)) {
                    Timber.d("DualSubtitleManager: Skipping image-based subtitle track - Index: %d, Title: %s, Language: %s, Codec: %s",
                            stream.getIndex(), stream.getTitle(), stream.getLanguage(), stream.getCodec());
                    continue;
                }

                availableSubtitleTracks.add(stream);
                Timber.d("DualSubtitleManager: Found text-based subtitle track - Index: %d, Title: %s, Language: %s, Codec: %s, External: %s",
                        stream.getIndex(), stream.getTitle(), stream.getLanguage(), stream.getCodec(), stream.isExternal());
            }
        }
    }

    /**
     * Check if a subtitle stream is text-based (not image-based)
     */
    private boolean isTextBasedSubtitle(@NonNull MediaStream stream) {
        String codec = stream.getCodec();
        if (codec == null) return true; // Assume text-based if codec is unknown

        switch (codec.toLowerCase()) {
            // Image-based subtitle formats
            case "dvdsub":
            case "dvd_subtitle":
            case "vobsub":
            case "pgssub":
            case "pgs":
            case "sup":
            case "hdmv_pgs_subtitle":

                // Local renderer can't handle these yet
            case "ass":
            case "ssa":
                return false;

            // Text-based subtitle formats
            case "srt":
            case "subrip":
            case "vtt":
            case "webvtt":
            case "ttml":
            case "smi":
            case "cc_dec":
            case "text":
                return true;

            // So we only present the user with options where we can provide a good experience
            default:
                return false;
        }
    }

    private void updateSelectedTrack() {
        selectedSubtitleTrack = null;

        if (selectedTrackId == -1 || availableSubtitleTracks.isEmpty()) {
            return; // Disabled or no tracks available
        }

        // Find track by ID (using list index for now)
        if (selectedTrackId >= 0 && selectedTrackId < availableSubtitleTracks.size()) {
            selectedSubtitleTrack = availableSubtitleTracks.get(selectedTrackId);

            Timber.d("DualSubtitleManager: Selected track - Index: %d, Title: %s, Language: %s",
                    selectedSubtitleTrack.getIndex(), selectedSubtitleTrack.getTitle(), selectedSubtitleTrack.getLanguage());

            // Start fetching and parsing subtitle content for the selected track
            fetchAndParseSubtitleContent();
        } else {
            Timber.w("DualSubtitleManager: Invalid track ID: %d, available tracks: %d", selectedTrackId, availableSubtitleTracks.size());
        }
    }

    private void fetchAndParseSubtitleContent() {
        fetchAndParseSubtitleContentWithRetry(0);
    }

    private void fetchAndParseSubtitleContentWithRetry(int attemptNumber) {
        if (apiClient == null || streamInfo == null || selectedSubtitleTrack == null) return;

        backgroundExecutor.execute(() -> {
            try {
                String subtitleContent = fetchSubtitle();

                if (subtitleContent != null && !subtitleContent.isEmpty()) {
                    parseSubtitleContent(subtitleContent);
                } else {
                    Timber.w("DualSubtitleManager: No subtitle content retrieved");
                    handleFetchFailure(attemptNumber, null);
                }

            } catch (Exception e) {
                Timber.e(e, "DualSubtitleManager: Error fetching subtitle content (attempt %d)", attemptNumber + 1);
                handleFetchFailure(attemptNumber, e);
            }
        });
    }

    private void handleFetchFailure(int attemptNumber, @Nullable Exception error) {
        if (attemptNumber < MAX_RETRY_COUNT - 1) {
            // Calculate retry delay: Math.min((attemptNumber) * 1000, 5000)
            long retryDelayMs = Math.min(attemptNumber * BASE_RETRY_DELAY_MS, MAX_RETRY_DELAY_MS);

            // Show toast on main thread
            mainHandler.post(() -> {
                String message = String.format("Secondary subtitles failed to load, retrying in %ds...", retryDelayMs / 1000);
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            });

            Timber.i("DualSubtitleManager: Retrying subtitle fetch in %dms (attempt %d/%d)",
                    retryDelayMs, attemptNumber + 2, MAX_RETRY_COUNT);

            // Schedule retry
            mainHandler.postDelayed(() -> fetchAndParseSubtitleContentWithRetry(attemptNumber + 1), retryDelayMs);
        } else {
            // All retries exhausted
            mainHandler.post(() -> {
                Toast.makeText(context, "Secondary subtitles failed to load", Toast.LENGTH_LONG).show();
            });
            Timber.e("DualSubtitleManager: Failed to fetch subtitles after %d attempts", MAX_RETRY_COUNT);
        }
    }

    @Nullable
    private String fetchSubtitle() {
        try {
            String url = selectedSubtitleTrack.getDeliveryUrl() != null
                    ? apiClient.createUrl(
                    selectedSubtitleTrack.getDeliveryUrl(),
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    true)
                    : apiClient.createUrl(
                    "Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.srt",
                    Map.of("itemId", streamInfo.getItemId(),
                            "mediaSourceId", streamInfo.getMediaSourceId(),
                            "index", selectedSubtitleTrack.getIndex()),
                    java.util.Collections.emptyMap(),
                    false);

            Request request = new Request.Builder()
                    .url(url)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (IOException e) {
            Timber.e(e, "DualSubtitleManager: Failed to fetch subtitle");
        }

        return null;
    }

    private void parseSubtitleContent(@NonNull String content) {
        try {
            // Determine parser based on subtitle format
            String codec = selectedSubtitleTrack.getCodec();
            if (codec == null) codec = "srt"; // Default to SRT

            SubtitleParser parser = createParserForCodec(codec.toLowerCase());
            if (parser == null) {
                Timber.w("DualSubtitleManager: Unsupported subtitle format: %s", codec);
                return;
            }

            // Parse subtitle content using Media3's parsing API
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            SubtitleParser.OutputOptions outputOptions = SubtitleParser.OutputOptions.allCues();

            // Parse with callback to collect cues with timing
            currentTimedCueGroups.clear();
            parser.parse(contentBytes, outputOptions, cuesWithTiming -> {
                // Create top-positioned cues
                List<Cue> topPositionedCues = new ArrayList<>();
                for (Cue originalCue : cuesWithTiming.cues) {
                    Cue.Builder builder = originalCue.buildUpon();
                    builder.setLine(userPreferences.get(UserPreferences.Companion.getSubtitlesOffsetPosition()), Cue.LINE_TYPE_FRACTION);  // Position near top (10% from top)
                    builder.setLineAnchor(Cue.ANCHOR_TYPE_START);   // Anchor to top
                    topPositionedCues.add(builder.build());
                }

                TimedCueGroup timedCueGroup = new TimedCueGroup(
                        cuesWithTiming.startTimeUs,
                        cuesWithTiming.endTimeUs,
                        topPositionedCues
                );
                currentTimedCueGroups.add(timedCueGroup);
                Timber.d("DualSubtitleManager: Parsed subtitle segment with %d cues at %d-%d us",
                        cuesWithTiming.cues.size(), cuesWithTiming.startTimeUs, cuesWithTiming.endTimeUs);
            });

            // Log total parsed segments
            int totalCues = currentTimedCueGroups.stream().mapToInt(tcg -> tcg.cues.size()).sum();
            Timber.d("DualSubtitleManager: Total parsed %d segments with %d cues", currentTimedCueGroups.size(), totalCues);

        } catch (Exception e) {
            Timber.e(e, "DualSubtitleManager: Error parsing subtitle content");
        }
    }

    @Nullable
    private SubtitleParser createParserForCodec(@NonNull String codec) {
        switch (codec.toLowerCase()) {
            case "srt":
            case "subrip":
                return new SubripParser();
            case "vtt":
            case "webvtt":
                return new WebvttParser();
            default:
                // For other text-based formats, try SRT parser as fallback
                return new SubripParser();
        }
    }
}
