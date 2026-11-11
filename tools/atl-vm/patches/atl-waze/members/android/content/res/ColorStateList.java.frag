	/* AOSP: the parcel creator (writeToParcel exists in ATL) */
	public static final android.os.Parcelable.Creator<ColorStateList> CREATOR = new android.os.Parcelable.Creator<ColorStateList>() {
		@Override
		public ColorStateList[] newArray(int size) {
			return new ColorStateList[size];
		}

		@Override
		public ColorStateList createFromParcel(android.os.Parcel source) {
			final int N = source.readInt();
			final int[][] stateSpecs = new int[N][];
			for (int i = 0; i < N; i++) {
				stateSpecs[i] = source.createIntArray();
			}
			final int[] colors = source.createIntArray();
			return new ColorStateList(stateSpecs, colors);
		}
	};
