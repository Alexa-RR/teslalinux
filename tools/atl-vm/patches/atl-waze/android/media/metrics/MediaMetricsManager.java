/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.media.metrics;

import android.annotation.NonNull;
import android.os.PersistableBundle;
import android.util.Log;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * teslalinux adaptation of AOSP's MediaMetricsManager. On Android the reports go over binder to the
 * media metrics system service, which forwards them to statsd. This runtime has no statsd: the
 * manager mints session ids locally (same format as the service: 16 random bytes, base64url) and
 * records every report in the log at debug level. The public API and session semantics are AOSP's.
 */
public final class MediaMetricsManager {
	private static final String TAG = "MediaMetricsManager";
	public static final long INVALID_TIMESTAMP = -1;
	private static final SecureRandom RANDOM = new SecureRandom();

	public MediaMetricsManager() {}

	private static String newSessionId() {
		byte[] raw = new byte[16];
		RANDOM.nextBytes(raw);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
	}

	public void reportPlaybackMetrics(@NonNull String sessionId, @NonNull PlaybackMetrics metrics) {
		Log.d(TAG, "playback metrics " + sessionId + ": " + metrics);
	}

	public void reportBundleMetrics(@NonNull String sessionId, @NonNull PersistableBundle metrics) {
		Log.d(TAG, "bundle metrics " + sessionId + ": " + metrics);
	}

	public void reportNetworkEvent(@NonNull String sessionId, @NonNull NetworkEvent event) {
		Log.d(TAG, "network event " + sessionId + ": " + event);
	}

	public void reportPlaybackStateEvent(@NonNull String sessionId, @NonNull PlaybackStateEvent event) {
		Log.d(TAG, "playback state " + sessionId + ": " + event);
	}

	public void reportTrackChangeEvent(@NonNull String sessionId, @NonNull TrackChangeEvent event) {
		Log.d(TAG, "track change " + sessionId + ": " + event);
	}

	public void reportPlaybackErrorEvent(@NonNull String sessionId, @NonNull PlaybackErrorEvent event) {
		Log.d(TAG, "playback error " + sessionId + ": " + event);
	}

	/** @hide */
	public void releaseSessionId(@NonNull String sessionId) {
		Log.d(TAG, "session released: " + sessionId);
	}

	@NonNull
	public PlaybackSession createPlaybackSession() {
		return new PlaybackSession(newSessionId(), this);
	}

	@NonNull
	public RecordingSession createRecordingSession() {
		return new RecordingSession(newSessionId(), this);
	}

	@NonNull
	public TranscodingSession createTranscodingSession() {
		return new TranscodingSession(newSessionId(), this);
	}

	@NonNull
	public EditingSession createEditingSession() {
		return new EditingSession(newSessionId(), this);
	}

	@NonNull
	public BundleSession createBundleSession() {
		return new BundleSession(newSessionId(), this);
	}
}
