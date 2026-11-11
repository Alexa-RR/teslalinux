/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

import android.os.Bundle;

import java.util.List;

/**
 * Used for receiving notifications when the device location has changed. (AOSP; the default
 * methods are the API 26-31 additions.)
 */
public interface LocationListener {
	/** Called when the location has changed. */
	void onLocationChanged(Location location);

	/** Called when the location has changed and locations are being delivered in batches. */
	default void onLocationChanged(List<Location> locations) {
		final int size = locations.size();
		for (int i = 0; i < size; i++) {
			onLocationChanged(locations.get(i));
		}
	}

	/** Invoked when a flush operation is complete and after flushed locations have been delivered. */
	default void onFlushComplete(int requestCode) {}

	/** @deprecated This callback will never be invoked on Android Q and above. */
	@Deprecated
	default void onStatusChanged(String provider, int status, Bundle extras) {}

	/** Called when a provider this listener is registered with becomes enabled. */
	default void onProviderEnabled(String provider) {}

	/** Called when the provider this listener is registered with becomes disabled. */
	default void onProviderDisabled(String provider) {}
}
