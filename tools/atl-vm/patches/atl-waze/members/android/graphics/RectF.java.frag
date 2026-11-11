	/* AOSP: parcelling */
	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(android.os.Parcel out, int flags) {
		out.writeFloat(left);
		out.writeFloat(top);
		out.writeFloat(right);
		out.writeFloat(bottom);
	}

	public void readFromParcel(android.os.Parcel in) {
		left = in.readFloat();
		top = in.readFloat();
		right = in.readFloat();
		bottom = in.readFloat();
	}

	public static final android.os.Parcelable.Creator<RectF> CREATOR = new android.os.Parcelable.Creator<RectF>() {
		@Override
		public RectF createFromParcel(android.os.Parcel in) {
			RectF r = new RectF();
			r.readFromParcel(in);
			return r;
		}

		@Override
		public RectF[] newArray(int size) {
			return new RectF[size];
		}
	};
