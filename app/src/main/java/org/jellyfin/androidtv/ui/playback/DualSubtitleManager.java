package org.jellyfin.androidtv.ui.playback;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;

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
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod;
import org.koin.java.KoinJavaComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final Context context;
    private final UserPreferences userPreferences;
    private final Handler mainHandler;
    private final ExecutorService backgroundExecutor;
    private final OkHttpClient httpClient;

    private SubtitleView secondarySubtitleView;
    private MediaStream firstSubtitleTrack;
    private ApiClient apiClient;
    private StreamInfo streamInfo;
    private boolean isEnabled = true; // Hardcoded to true as requested

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
        this.httpClient = new OkHttpClient();
    }

    /**
     * Initialize the dual subtitle system with media information
     */
    public void initialize(ApiClient api, StreamInfo streamInfo) {
        this.apiClient = api;
        this.streamInfo = streamInfo;

        if (!isEnabled) return;

        // Find the first subtitle track
        findFirstSubtitleTrack();

        if (firstSubtitleTrack != null) {
            Timber.d("DualSubtitleManager: Found first subtitle track - %s (%s)",
                firstSubtitleTrack.getDisplayTitle(), firstSubtitleTrack.getCodec());

            // Start fetching and parsing subtitle content
            fetchAndParseSubtitleContent();
        } else {
            Timber.d("DualSubtitleManager: No subtitle tracks found");
        }
    }

    /**
     * Create and attach the secondary subtitle view to the player container
     */
    public void attachSecondarySubtitleView(@NonNull FrameLayout playerContainer, @NonNull CaptionStyleCompat style) {
        if (!isEnabled || firstSubtitleTrack == null) return;

        if (secondarySubtitleView == null) {
            secondarySubtitleView = new SubtitleView(context);
            secondarySubtitleView.setFractionalTextSize(0.0533f * userPreferences.get(UserPreferences.Companion.getSubtitlesTextSize()));
            // Position secondary subtitles above primary ones
            secondarySubtitleView.setBottomPaddingFraction(userPreferences.get(UserPreferences.Companion.getSubtitlesOffsetPosition()) + 0.15f);
            secondarySubtitleView.setStyle(style);
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
        if (!isEnabled || secondarySubtitleView == null || currentTimedCueGroups.isEmpty()) return;

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

    private void findFirstSubtitleTrack() {
        if (streamInfo == null) return;

        for (MediaStream stream : streamInfo.getMediaSource().getMediaStreams()) {
            if (stream.getType() == MediaStreamType.SUBTITLE) {
                firstSubtitleTrack = stream;
                Timber.d("DualSubtitleManager: Selected first subtitle track - Index: %d, Title: %s, Language: %s, External: %s",
                    stream.getIndex(), stream.getTitle(), stream.getLanguage(), stream.isExternal());
                return;
            }
        }
    }

    private void fetchAndParseSubtitleContent() {
        if (apiClient == null || streamInfo == null || firstSubtitleTrack == null) return;

        backgroundExecutor.execute(() -> {
            try {
                String subtitleContent;

                if (firstSubtitleTrack.getDeliveryMethod() == SubtitleDeliveryMethod.EXTERNAL) {
                    // Fetch external subtitle file
                    subtitleContent = fetchExternalSubtitle();
                } else {
                    // Fetch embedded subtitle via Jellyfin API
                    subtitleContent = fetchEmbeddedSubtitle();
                }

                if (subtitleContent != null && !subtitleContent.isEmpty()) {
                    parseSubtitleContent(subtitleContent);
                } else {
                    Timber.w("DualSubtitleManager: No subtitle content retrieved");
                }

            } catch (Exception e) {
                Timber.e(e, "DualSubtitleManager: Error fetching subtitle content");
            }
        });
    }

    @Nullable
    private String fetchExternalSubtitle() {
        if (firstSubtitleTrack.getDeliveryUrl() == null) return null;

        try {
            String url = apiClient.createUrl(firstSubtitleTrack.getDeliveryUrl(),
                java.util.Collections.emptyMap(), java.util.Collections.emptyMap(), true);

            Request request = new Request.Builder()
                .url(url)
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (IOException e) {
            Timber.e(e, "DualSubtitleManager: Failed to fetch external subtitle");
        }

        return null;
    }

    @Nullable
    private String fetchEmbeddedSubtitle() {
        try {
            // Use Jellyfin's subtitle API to get embedded subtitle content
            // This requires making an HTTP call to the Jellyfin server
            String subtitleUrl = String.format("%s/Videos/%s/%s/Subtitles/%d/Stream.srt",
                apiClient.getBaseUrl(),
                streamInfo.getItemId(),
                streamInfo.getMediaSourceId(),
                firstSubtitleTrack.getIndex());

            Request request = new Request.Builder()
                .url(subtitleUrl)
                .addHeader("Authorization", "MediaBrowser Token=" + apiClient.getAccessToken())
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (IOException e) {
            Timber.e(e, "DualSubtitleManager: Failed to fetch embedded subtitle");
        }

        return null;
    }

    private void parseSubtitleContent(@NonNull String content) {
        try {
            // Determine parser based on subtitle format
            String codec = firstSubtitleTrack.getCodec();
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
                TimedCueGroup timedCueGroup = new TimedCueGroup(
                    cuesWithTiming.startTimeUs,
                    cuesWithTiming.endTimeUs,
                    cuesWithTiming.cues
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

    public boolean isEnabled() {
        return isEnabled && firstSubtitleTrack != null;
    }

}
