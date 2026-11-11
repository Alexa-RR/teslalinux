package android.util;

public final class Range<T extends Comparable<? super T>> {
	private final T mLower, mUpper;

	public Range(T lower, T upper) {
		if (lower == null || upper == null) throw new NullPointerException("lower/upper must not be null");
		if (lower.compareTo(upper) > 0) throw new IllegalArgumentException("lower must be less than or equal to upper");
		mLower = lower; mUpper = upper;
	}
	public static <T extends Comparable<? super T>> Range<T> create(T lower, T upper) { return new Range<>(lower, upper); }
	public T getLower() { return mLower; }
	public T getUpper() { return mUpper; }
	public boolean contains(T value) { return value.compareTo(mLower) >= 0 && value.compareTo(mUpper) <= 0; }
	public boolean contains(Range<T> range) { return range.mLower.compareTo(mLower) >= 0 && range.mUpper.compareTo(mUpper) <= 0; }
	public T clamp(T value) { return value.compareTo(mLower) < 0 ? mLower : (value.compareTo(mUpper) > 0 ? mUpper : value); }
	public Range<T> intersect(Range<T> range) {
		T l = range.mLower.compareTo(mLower) >= 0 ? range.mLower : mLower;
		T u = range.mUpper.compareTo(mUpper) <= 0 ? range.mUpper : mUpper;
		return create(l, u);
	}
	public Range<T> intersect(T lower, T upper) { return intersect(create(lower, upper)); }
	public Range<T> extend(Range<T> range) {
		T l = range.mLower.compareTo(mLower) <= 0 ? range.mLower : mLower;
		T u = range.mUpper.compareTo(mUpper) >= 0 ? range.mUpper : mUpper;
		return create(l, u);
	}
	public Range<T> extend(T lower, T upper) { return extend(create(lower, upper)); }
	public Range<T> extend(T value) { return extend(create(value, value)); }
	@Override public boolean equals(Object obj) { return obj instanceof Range && mLower.equals(((Range<?>) obj).mLower) && mUpper.equals(((Range<?>) obj).mUpper); }
	@Override public int hashCode() { return 31 * mLower.hashCode() + mUpper.hashCode(); }
	@Override public String toString() { return "[" + mLower + ", " + mUpper + "]"; }
}
