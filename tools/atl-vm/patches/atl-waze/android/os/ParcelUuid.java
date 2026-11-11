package android.os;

import java.util.UUID;

public final class ParcelUuid implements Parcelable {
	private final UUID mUuid;
	public ParcelUuid(UUID uuid) { mUuid = uuid; }
	public static ParcelUuid fromString(String uuid) { return new ParcelUuid(UUID.fromString(uuid)); }
	public UUID getUuid() { return mUuid; }
	@Override public String toString() { return mUuid.toString(); }
	@Override public int hashCode() { return mUuid.hashCode(); }
	@Override public boolean equals(Object o) { return o instanceof ParcelUuid && mUuid.equals(((ParcelUuid) o).mUuid); }
	@Override public int describeContents() { return 0; }
	@Override public void writeToParcel(Parcel dest, int flags) { dest.writeString(mUuid.toString()); }
	public static final Parcelable.Creator<ParcelUuid> CREATOR = new Parcelable.Creator<ParcelUuid>() {
		public ParcelUuid createFromParcel(Parcel source) { return ParcelUuid.fromString(source.readString()); }
		public ParcelUuid[] newArray(int size) { return new ParcelUuid[size]; }
	};
}
