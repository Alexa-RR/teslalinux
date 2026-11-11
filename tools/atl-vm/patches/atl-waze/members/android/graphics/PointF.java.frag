	/* AOSP: parcelling */
	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(android.os.Parcel out, int flags) {
		out.writeFloat(x);
		out.writeFloat(y);
	}

	public void readFromParcel(android.os.Parcel in) {
		x = in.readFloat();
		y = in.readFloat();
	}

	public static final android.os.Parcelable.Creator<PointF> CREATOR = new android.os.Parcelable.Creator<PointF>() {
		@Override
		public PointF createFromParcel(android.os.Parcel in) {
			PointF r = new PointF();
			r.readFromParcel(in);
			return r;
		}

		@Override
		public PointF[] newArray(int size) {
			return new PointF[size];
		}
	};
