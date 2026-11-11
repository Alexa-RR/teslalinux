/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Location provider properties. (AOSP, API 31.)
 */
public final class ProviderProperties implements Parcelable {
	/** A constant indicating low power usage. */
	public static final int POWER_USAGE_LOW = 1;
	/** A constant indicating a medium power usage. */
	public static final int POWER_USAGE_MEDIUM = 2;
	/** A constant indicating high power usage. */
	public static final int POWER_USAGE_HIGH = 3;

	/** A constant indicating a finer location accuracy. */
	public static final int ACCURACY_FINE = 1;
	/** A constant indicating a coarser location accuracy. */
	public static final int ACCURACY_COARSE = 2;

	private final boolean mHasNetworkRequirement;
	private final boolean mHasSatelliteRequirement;
	private final boolean mHasCellRequirement;
	private final boolean mHasMonetaryCost;
	private final boolean mHasAltitudeSupport;
	private final boolean mHasSpeedSupport;
	private final boolean mHasBearingSupport;
	private final int mPowerUsage;
	private final int mAccuracy;

	private ProviderProperties(boolean hasNetworkRequirement, boolean hasSatelliteRequirement, boolean hasCellRequirement, boolean hasMonetaryCost,
	                           boolean hasAltitudeSupport, boolean hasSpeedSupport, boolean hasBearingSupport, int powerUsage, int accuracy) {
		mHasNetworkRequirement = hasNetworkRequirement;
		mHasSatelliteRequirement = hasSatelliteRequirement;
		mHasCellRequirement = hasCellRequirement;
		mHasMonetaryCost = hasMonetaryCost;
		mHasAltitudeSupport = hasAltitudeSupport;
		mHasSpeedSupport = hasSpeedSupport;
		mHasBearingSupport = hasBearingSupport;
		mPowerUsage = powerUsage;
		mAccuracy = accuracy;
	}

	public boolean hasNetworkRequirement() { return mHasNetworkRequirement; }
	public boolean hasSatelliteRequirement() { return mHasSatelliteRequirement; }
	public boolean hasCellRequirement() { return mHasCellRequirement; }
	public boolean hasMonetaryCost() { return mHasMonetaryCost; }
	public boolean hasAltitudeSupport() { return mHasAltitudeSupport; }
	public boolean hasSpeedSupport() { return mHasSpeedSupport; }
	public boolean hasBearingSupport() { return mHasBearingSupport; }
	public int getPowerUsage() { return mPowerUsage; }
	public int getAccuracy() { return mAccuracy; }

	public static final Parcelable.Creator<ProviderProperties> CREATOR = new Parcelable.Creator<ProviderProperties>() {
		@Override
		public ProviderProperties createFromParcel(Parcel in) {
			return new ProviderProperties(in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readBoolean(), in.readInt(), in.readInt());
		}

		@Override
		public ProviderProperties[] newArray(int size) {
			return new ProviderProperties[size];
		}
	};

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel parcel, int flags) {
		parcel.writeBoolean(mHasNetworkRequirement);
		parcel.writeBoolean(mHasSatelliteRequirement);
		parcel.writeBoolean(mHasCellRequirement);
		parcel.writeBoolean(mHasMonetaryCost);
		parcel.writeBoolean(mHasAltitudeSupport);
		parcel.writeBoolean(mHasSpeedSupport);
		parcel.writeBoolean(mHasBearingSupport);
		parcel.writeInt(mPowerUsage);
		parcel.writeInt(mAccuracy);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ProviderProperties)) return false;
		ProviderProperties that = (ProviderProperties) o;
		return mHasNetworkRequirement == that.mHasNetworkRequirement && mHasSatelliteRequirement == that.mHasSatelliteRequirement
				&& mHasCellRequirement == that.mHasCellRequirement && mHasMonetaryCost == that.mHasMonetaryCost
				&& mHasAltitudeSupport == that.mHasAltitudeSupport && mHasSpeedSupport == that.mHasSpeedSupport
				&& mHasBearingSupport == that.mHasBearingSupport && mPowerUsage == that.mPowerUsage && mAccuracy == that.mAccuracy;
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hash(mHasNetworkRequirement, mHasSatelliteRequirement, mHasCellRequirement, mHasMonetaryCost, mHasAltitudeSupport, mHasSpeedSupport, mHasBearingSupport, mPowerUsage, mAccuracy);
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder("ProviderProperties[");
		b.append("powerUsage=").append(mPowerUsage == POWER_USAGE_LOW ? "low" : mPowerUsage == POWER_USAGE_MEDIUM ? "medium" : "high").append(", ");
		b.append("accuracy=").append(mAccuracy == ACCURACY_FINE ? "fine" : "coarse");
		if (mHasNetworkRequirement || mHasSatelliteRequirement || mHasCellRequirement) {
			b.append(", requires=");
			if (mHasNetworkRequirement) b.append("network,");
			if (mHasSatelliteRequirement) b.append("satellite,");
			if (mHasCellRequirement) b.append("cell,");
			b.setLength(b.length() - 1);
		}
		if (mHasMonetaryCost) b.append(", hasMonetaryCost");
		if (mHasBearingSupport || mHasSpeedSupport || mHasAltitudeSupport) {
			b.append(", supports=[");
			if (mHasBearingSupport) b.append("bearing, ");
			if (mHasSpeedSupport) b.append("speed, ");
			if (mHasAltitudeSupport) b.append("altitude, ");
			b.setLength(b.length() - 2);
			b.append("]");
		}
		b.append("]");
		return b.toString();
	}

	/** Builder for ProviderProperties. */
	public static final class Builder {
		private boolean mHasNetworkRequirement;
		private boolean mHasSatelliteRequirement;
		private boolean mHasCellRequirement;
		private boolean mHasMonetaryCost;
		private boolean mHasAltitudeSupport;
		private boolean mHasSpeedSupport;
		private boolean mHasBearingSupport;
		private int mPowerUsage;
		private int mAccuracy;

		public Builder() {
			mPowerUsage = POWER_USAGE_HIGH;
			mAccuracy = ACCURACY_COARSE;
		}

		public Builder(ProviderProperties providerProperties) {
			mHasNetworkRequirement = providerProperties.mHasNetworkRequirement;
			mHasSatelliteRequirement = providerProperties.mHasSatelliteRequirement;
			mHasCellRequirement = providerProperties.mHasCellRequirement;
			mHasMonetaryCost = providerProperties.mHasMonetaryCost;
			mHasAltitudeSupport = providerProperties.mHasAltitudeSupport;
			mHasSpeedSupport = providerProperties.mHasSpeedSupport;
			mHasBearingSupport = providerProperties.mHasBearingSupport;
			mPowerUsage = providerProperties.mPowerUsage;
			mAccuracy = providerProperties.mAccuracy;
		}

		public Builder setHasNetworkRequirement(boolean requiresNetwork) { mHasNetworkRequirement = requiresNetwork; return this; }
		public Builder setHasSatelliteRequirement(boolean requiresSatellite) { mHasSatelliteRequirement = requiresSatellite; return this; }
		public Builder setHasCellRequirement(boolean requiresCell) { mHasCellRequirement = requiresCell; return this; }
		public Builder setHasMonetaryCost(boolean monetaryCost) { mHasMonetaryCost = monetaryCost; return this; }
		public Builder setHasAltitudeSupport(boolean supportsAltitude) { mHasAltitudeSupport = supportsAltitude; return this; }
		public Builder setHasSpeedSupport(boolean supportsSpeed) { mHasSpeedSupport = supportsSpeed; return this; }
		public Builder setHasBearingSupport(boolean supportsBearing) { mHasBearingSupport = supportsBearing; return this; }
		public Builder setPowerUsage(int powerUsage) { mPowerUsage = powerUsage; return this; }
		public Builder setAccuracy(int accuracy) { mAccuracy = accuracy; return this; }

		public ProviderProperties build() {
			return new ProviderProperties(mHasNetworkRequirement, mHasSatelliteRequirement, mHasCellRequirement, mHasMonetaryCost, mHasAltitudeSupport, mHasSpeedSupport, mHasBearingSupport, mPowerUsage, mAccuracy);
		}
	}
}
