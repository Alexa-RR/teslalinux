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

import android.content.Context;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * A class for handling geocoding and reverse geocoding (AOSP API).
 *
 * teslalinux: AOSP forwards every query to the platform's geocode provider (a bound service,
 * normally Google Play services). This device has no geocode provider, so this is AOSP's
 * behaviour without one: {@link #isPresent()} is false, the synchronous queries throw
 * IOException("Service not Available") after validating their arguments, and the listener
 * variants report that message through {@link GeocodeListener#onError(String)}. Apps (Waze
 * included) check isPresent() and use their own geocoding.
 */
public final class Geocoder {
    private static final String TAG = "Geocoder";

    /** The error AOSP's LocationManagerService reports when no geocode provider is bound. */
    private static final String SERVICE_NOT_AVAILABLE = "Service not Available";

    /** Listener for geocode results or errors (API 33). */
    public interface GeocodeListener {
        void onGeocode(List<Address> addresses);

        default void onError(String errorMessage) {}
    }

    private final Context mContext;
    private final Locale mLocale;

    /** Returns true if there is a geocode provider: there is none on this device. */
    public static boolean isPresent() {
        return false;
    }

    public Geocoder(Context context, Locale locale) {
        if (locale == null) {
            throw new NullPointerException("locale == null");
        }
        mContext = context;
        mLocale = locale;
    }

    public Geocoder(Context context) {
        this(context, Locale.getDefault());
    }

    private static void checkLatLon(String what, double latitude, double longitude) {
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException(what + "atitude == " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException(what + "ongitude == " + longitude);
        }
    }

    @Deprecated
    public List<Address> getFromLocation(double latitude, double longitude, int maxResults)
            throws IOException {
        checkLatLon("l", latitude, longitude);
        if (maxResults < 0) {
            throw new IllegalArgumentException("maxResults == " + maxResults);
        }
        throw new IOException(SERVICE_NOT_AVAILABLE);
    }

    public void getFromLocation(double latitude, double longitude, int maxResults,
            GeocodeListener listener) {
        checkLatLon("l", latitude, longitude);
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults == " + maxResults);
        }
        if (listener == null) {
            throw new NullPointerException("listener == null");
        }
        listener.onError(SERVICE_NOT_AVAILABLE);
    }

    @Deprecated
    public List<Address> getFromLocationName(String locationName, int maxResults)
            throws IOException {
        if (locationName == null) {
            throw new IllegalArgumentException("locationName == null");
        }
        if (maxResults < 0) {
            throw new IllegalArgumentException("maxResults == " + maxResults);
        }
        throw new IOException(SERVICE_NOT_AVAILABLE);
    }

    public void getFromLocationName(String locationName, int maxResults,
            GeocodeListener listener) {
        getFromLocationName(locationName, maxResults, -90, -180, 90, 180, listener);
    }

    @Deprecated
    public List<Address> getFromLocationName(String locationName, int maxResults,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude) throws IOException {
        if (locationName == null) {
            throw new IllegalArgumentException("locationName == null");
        }
        checkLatLon("lowerLeftL", lowerLeftLatitude, lowerLeftLongitude);
        checkLatLon("upperRightL", upperRightLatitude, upperRightLongitude);
        throw new IOException(SERVICE_NOT_AVAILABLE);
    }

    public void getFromLocationName(String locationName, int maxResults,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude,
            GeocodeListener listener) {
        if (locationName == null) {
            throw new IllegalArgumentException("locationName == null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults == " + maxResults);
        }
        checkLatLon("lowerLeftL", lowerLeftLatitude, lowerLeftLongitude);
        checkLatLon("upperRightL", upperRightLatitude, upperRightLongitude);
        if (listener == null) {
            throw new NullPointerException("listener == null");
        }
        listener.onError(SERVICE_NOT_AVAILABLE);
    }
}
