/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.os.Parcel;
import android.os.Parcelable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes the capabilities of a particular input device.
 *
 * teslalinux: AOSP's InputDevice (android15) with the input manager's device list kept here. ATL
 * reported one made-up device (id 0, "FIXME-name", every key present, no motion ranges, every source
 * supported) for every event. The devices are the ones this platform really has, described the way
 * AOSP's InputReader describes them:
 * <ul>
 * <li>{@link KeyCharacterMap#VIRTUAL_KEYBOARD} (-1), "Virtual": EventHub's virtual keyboard, the device
 *     of every KeyEvent ATL creates (GDK key events from the car page's keyboard and the built-in
 *     on-screen keyboard); alphabetic, keyboard + dpad sources, isVirtual().</li>
 * <li>{@link #TL_TOUCHSCREEN_ID} (1): the touchscreen (the car browser's touches, through the
 *     compositor), a TouchInputMapper device without size/orientation calibration: X/Y over the
 *     display, pressure 0..1.</li>
 * <li>{@link #TL_POINTER_ID} (2): a mouse, a CursorInputMapper device; it is listed once a pointer
 *     event has been seen (the test VM's compositor pointer; the car has none).</li>
 * </ul>
 */
public final class InputDevice implements Parcelable {
	/** @hide teslalinux: the device id of the touchscreen */
	public static final int TL_TOUCHSCREEN_ID = 1;
	/** @hide teslalinux: the device id of the pointer (mouse) device */
	public static final int TL_POINTER_ID = 2;

	private final int mId;
	private final int mGeneration;
	private final int mControllerNumber;
	private final String mName;
	private final int mVendorId;
	private final int mProductId;
	private final int mDeviceBus;
	private final String mDescriptor;
	private final boolean mIsExternal;
	private final int mSources;
	private final int mKeyboardType;
	private final boolean mHasVibrator;
	private final boolean mHasMicrophone;
	private final boolean mHasButtonUnderPad;
	private final boolean mHasSensor;
	private final boolean mHasBattery;
	private final int mAssociatedDisplayId;
	private final boolean mEnabled;
	private final ArrayList<MotionRange> mMotionRanges = new ArrayList<MotionRange>();
	private final ViewBehavior mViewBehavior = new ViewBehavior(this);
	private KeyCharacterMap mKeyCharacterMap;

	public static final int SOURCE_CLASS_MASK = 0x000000ff;
	public static final int SOURCE_CLASS_NONE = 0x00000000;
	public static final int SOURCE_CLASS_BUTTON = 0x00000001;
	public static final int SOURCE_CLASS_POINTER = 0x00000002;
	public static final int SOURCE_CLASS_TRACKBALL = 0x00000004;
	public static final int SOURCE_CLASS_POSITION = 0x00000008;
	public static final int SOURCE_CLASS_JOYSTICK = 0x00000010;
	public static final int SOURCE_UNKNOWN = 0x00000000;
	public static final int SOURCE_KEYBOARD = 0x00000100 | SOURCE_CLASS_BUTTON;
	public static final int SOURCE_DPAD = 0x00000200 | SOURCE_CLASS_BUTTON;
	public static final int SOURCE_GAMEPAD = 0x00000400 | SOURCE_CLASS_BUTTON;
	public static final int SOURCE_TOUCHSCREEN = 0x00001000 | SOURCE_CLASS_POINTER;
	public static final int SOURCE_MOUSE = 0x00002000 | SOURCE_CLASS_POINTER;
	public static final int SOURCE_STYLUS = 0x00004000 | SOURCE_CLASS_POINTER;
	public static final int SOURCE_BLUETOOTH_STYLUS = 0x00008000 | SOURCE_STYLUS;
	public static final int SOURCE_TRACKBALL = 0x00010000 | SOURCE_CLASS_TRACKBALL;
	public static final int SOURCE_MOUSE_RELATIVE = 0x00020000 | SOURCE_CLASS_TRACKBALL;
	public static final int SOURCE_TOUCHPAD = 0x00100000 | SOURCE_CLASS_POSITION;
	public static final int SOURCE_TOUCH_NAVIGATION = 0x00200000 | SOURCE_CLASS_NONE;
	public static final int SOURCE_ROTARY_ENCODER = 0x00400000 | SOURCE_CLASS_NONE;
	public static final int SOURCE_JOYSTICK = 0x01000000 | SOURCE_CLASS_JOYSTICK;
	public static final int SOURCE_HDMI = 0x02000000 | SOURCE_CLASS_BUTTON;
	public static final int SOURCE_SENSOR = 0x04000000 | SOURCE_CLASS_NONE;
	public static final int SOURCE_ANY = 0xffffff00;

	@Deprecated
	public static final int MOTION_RANGE_X = MotionEvent.AXIS_X;
	@Deprecated
	public static final int MOTION_RANGE_Y = MotionEvent.AXIS_Y;
	@Deprecated
	public static final int MOTION_RANGE_PRESSURE = MotionEvent.AXIS_PRESSURE;
	@Deprecated
	public static final int MOTION_RANGE_SIZE = MotionEvent.AXIS_SIZE;
	@Deprecated
	public static final int MOTION_RANGE_TOUCH_MAJOR = MotionEvent.AXIS_TOUCH_MAJOR;
	@Deprecated
	public static final int MOTION_RANGE_TOUCH_MINOR = MotionEvent.AXIS_TOUCH_MINOR;
	@Deprecated
	public static final int MOTION_RANGE_TOOL_MAJOR = MotionEvent.AXIS_TOOL_MAJOR;
	@Deprecated
	public static final int MOTION_RANGE_TOOL_MINOR = MotionEvent.AXIS_TOOL_MINOR;
	@Deprecated
	public static final int MOTION_RANGE_ORIENTATION = MotionEvent.AXIS_ORIENTATION;

	public static final int KEYBOARD_TYPE_NONE = 0;
	public static final int KEYBOARD_TYPE_NON_ALPHABETIC = 1;
	public static final int KEYBOARD_TYPE_ALPHABETIC = 2;

	private static final int MAX_RANGES = 1000;

	public static final Parcelable.Creator<InputDevice> CREATOR = new Parcelable.Creator<InputDevice>() {
		public InputDevice createFromParcel(Parcel in) {
			return new InputDevice(in);
		}
		public InputDevice[] newArray(int size) {
			return new InputDevice[size];
		}
	};

	private InputDevice(int id, int generation, int controllerNumber, String name, int vendorId,
			    int productId, int deviceBus, String descriptor, boolean isExternal, int sources,
			    int keyboardType, boolean hasVibrator, boolean hasMicrophone,
			    boolean hasButtonUnderPad, boolean hasSensor, boolean hasBattery,
			    int associatedDisplayId, boolean enabled) {
		mId = id;
		mGeneration = generation;
		mControllerNumber = controllerNumber;
		mName = name;
		mVendorId = vendorId;
		mProductId = productId;
		mDeviceBus = deviceBus;
		mDescriptor = descriptor;
		mIsExternal = isExternal;
		mSources = sources;
		mKeyboardType = keyboardType;
		mHasVibrator = hasVibrator;
		mHasMicrophone = hasMicrophone;
		mHasButtonUnderPad = hasButtonUnderPad;
		mHasSensor = hasSensor;
		mHasBattery = hasBattery;
		mAssociatedDisplayId = associatedDisplayId;
		mEnabled = enabled;
	}

	private InputDevice(Parcel in) {
		mId = in.readInt();
		mGeneration = in.readInt();
		mControllerNumber = in.readInt();
		mName = in.readString();
		mVendorId = in.readInt();
		mProductId = in.readInt();
		mDeviceBus = in.readInt();
		mDescriptor = in.readString();
		mIsExternal = in.readInt() != 0;
		mSources = in.readInt();
		mKeyboardType = in.readInt();
		mHasVibrator = in.readInt() != 0;
		mHasMicrophone = in.readInt() != 0;
		mHasButtonUnderPad = in.readInt() != 0;
		mHasSensor = in.readInt() != 0;
		mHasBattery = in.readInt() != 0;
		mAssociatedDisplayId = in.readInt();
		mEnabled = in.readInt() != 0;

		int numRanges = in.readInt();
		if (numRanges > MAX_RANGES) {
			numRanges = MAX_RANGES;
		}
		for (int i = 0; i < numRanges; i++) {
			addMotionRange(in.readInt(), in.readInt(), in.readFloat(), in.readFloat(),
				       in.readFloat(), in.readFloat(), in.readFloat());
		}
		mViewBehavior.mShouldSmoothScroll = in.readInt() != 0;
	}

	/**
	 * InputDevice builder used to create an InputDevice for tests in Java.
	 *
	 * @hide
	 */
	public static class Builder {
		private int mId = 0;
		private int mGeneration = 0;
		private int mControllerNumber = 0;
		private String mName = "";
		private int mVendorId = 0;
		private int mProductId = 0;
		private int mDeviceBus = 0;
		private String mDescriptor = "";
		private boolean mIsExternal = false;
		private int mSources = 0;
		private int mKeyboardType = 0;
		private boolean mHasVibrator = false;
		private boolean mHasMicrophone = false;
		private boolean mHasButtonUnderPad = false;
		private boolean mHasSensor = false;
		private boolean mHasBattery = false;
		private int mAssociatedDisplayId = -1; // Display.INVALID_DISPLAY
		private boolean mEnabled = true;
		private List<MotionRange> mMotionRanges = new ArrayList<>();
		private boolean mShouldSmoothScroll;

		public Builder setId(int id) { mId = id; return this; }
		public Builder setGeneration(int generation) { mGeneration = generation; return this; }
		public Builder setControllerNumber(int controllerNumber) { mControllerNumber = controllerNumber; return this; }
		public Builder setName(String name) { mName = name; return this; }
		public Builder setVendorId(int vendorId) { mVendorId = vendorId; return this; }
		public Builder setProductId(int productId) { mProductId = productId; return this; }
		public Builder setDeviceBus(int deviceBus) { mDeviceBus = deviceBus; return this; }
		public Builder setDescriptor(String descriptor) { mDescriptor = descriptor; return this; }
		public Builder setExternal(boolean external) { mIsExternal = external; return this; }
		public Builder setSources(int sources) { mSources = sources; return this; }
		public Builder setKeyboardType(int keyboardType) { mKeyboardType = keyboardType; return this; }
		public Builder setHasVibrator(boolean hasVibrator) { mHasVibrator = hasVibrator; return this; }
		public Builder setHasMicrophone(boolean hasMicrophone) { mHasMicrophone = hasMicrophone; return this; }
		public Builder setHasButtonUnderPad(boolean hasButtonUnderPad) { mHasButtonUnderPad = hasButtonUnderPad; return this; }
		public Builder setHasSensor(boolean hasSensor) { mHasSensor = hasSensor; return this; }
		public Builder setHasBattery(boolean hasBattery) { mHasBattery = hasBattery; return this; }
		public Builder setAssociatedDisplayId(int displayId) { mAssociatedDisplayId = displayId; return this; }
		public Builder setEnabled(boolean enabled) { mEnabled = enabled; return this; }
		public Builder setShouldSmoothScroll(boolean shouldSmoothScroll) { mShouldSmoothScroll = shouldSmoothScroll; return this; }

		public Builder addMotionRange(int axis, int source, float min, float max, float flat, float fuzz, float resolution) {
			mMotionRanges.add(new MotionRange(axis, source, min, max, flat, fuzz, resolution));
			return this;
		}

		public InputDevice build() {
			InputDevice device = new InputDevice(mId, mGeneration, mControllerNumber, mName,
							     mVendorId, mProductId, mDeviceBus, mDescriptor, mIsExternal, mSources,
							     mKeyboardType, mHasVibrator, mHasMicrophone, mHasButtonUnderPad,
							     mHasSensor, mHasBattery, mAssociatedDisplayId, mEnabled);
			final int numRanges = mMotionRanges.size();
			for (int i = 0; i < numRanges; i++) {
				final MotionRange range = mMotionRanges.get(i);
				device.addMotionRange(range.getAxis(), range.getSource(), range.getMin(),
						      range.getMax(), range.getFlat(), range.getFuzz(), range.getResolution());
			}
			device.setShouldSmoothScroll(mShouldSmoothScroll);
			return device;
		}
	}

	// --- the input manager's device list (InputManagerGlobal.getInputDevice/getInputDeviceIds) -------

	private static final Object sLock = new Object();
	private static boolean sPointerSeen;
	private static int sGeneration = 1;
	private static int sDisplayWidth, sDisplayHeight;
	private static InputDevice sTouchscreen, sPointer, sVirtualKeyboard;

	/** AOSP's descriptor: a SHA-1 of the device identity, stable across boots */
	private static String descriptorFor(String identity) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] d = md.digest(identity.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for (byte b : d)
				sb.append(String.format("%02x", b & 0xff));
			return sb.toString();
		} catch (Exception e) {
			return identity;
		}
	}

	/** The pointer devices' X/Y ranges cover the display: rebuilt when its size changes, with a new generation. */
	private static void refreshLocked() {
		final int w = Math.max(1, Display.window_width);
		final int h = Math.max(1, Display.window_height);
		if (sVirtualKeyboard == null) {
			// EventHub::createVirtualKeyboardLocked: "Virtual", keyboard + alpha keys + dpad
			sVirtualKeyboard = new Builder()
						   .setId(KeyCharacterMap.VIRTUAL_KEYBOARD)
						   .setGeneration(sGeneration)
						   .setName("Virtual")
						   .setDescriptor(descriptorFor("<virtual>"))
						   .setSources(SOURCE_KEYBOARD | SOURCE_DPAD)
						   .setKeyboardType(KEYBOARD_TYPE_ALPHABETIC)
						   .build();
		}
		if (sTouchscreen == null || w != sDisplayWidth || h != sDisplayHeight) {
			if (sTouchscreen != null)
				sGeneration++;
			sDisplayWidth = w;
			sDisplayHeight = h;
			// TouchInputMapper without size/orientation calibration: X, Y and pressure
			sTouchscreen = new Builder()
					       .setId(TL_TOUCHSCREEN_ID)
					       .setGeneration(sGeneration)
					       .setName("teslalinux touchscreen")
					       .setDescriptor(descriptorFor("teslalinux:touchscreen"))
					       .setSources(SOURCE_TOUCHSCREEN)
					       .setAssociatedDisplayId(0)
					       .addMotionRange(MotionEvent.AXIS_X, SOURCE_TOUCHSCREEN, 0, w - 1, 0, 0, 0)
					       .addMotionRange(MotionEvent.AXIS_Y, SOURCE_TOUCHSCREEN, 0, h - 1, 0, 0, 0)
					       .addMotionRange(MotionEvent.AXIS_PRESSURE, SOURCE_TOUCHSCREEN, 0, 1, 0, 0, 0)
					       .build();
			if (sPointerSeen)
				sPointer = buildPointer(w, h);
		}
		if (sPointerSeen && sPointer == null)
			sPointer = buildPointer(w, h);
	}

	/** CursorInputMapper in pointer mode, with a vertical and a horizontal wheel (GDK scroll events) */
	private static InputDevice buildPointer(int w, int h) {
		return new Builder()
			.setId(TL_POINTER_ID)
			.setGeneration(sGeneration)
			.setName("teslalinux pointer")
			.setDescriptor(descriptorFor("teslalinux:pointer"))
			.setSources(SOURCE_MOUSE)
			.setAssociatedDisplayId(0)
			.addMotionRange(MotionEvent.AXIS_X, SOURCE_MOUSE, 0, w - 1, 0, 0, 0)
			.addMotionRange(MotionEvent.AXIS_Y, SOURCE_MOUSE, 0, h - 1, 0, 0, 0)
			.addMotionRange(MotionEvent.AXIS_PRESSURE, SOURCE_MOUSE, 0, 1, 0, 0, 0)
			.addMotionRange(MotionEvent.AXIS_VSCROLL, SOURCE_MOUSE, -1, 1, 0, 0, 0)
			.addMotionRange(MotionEvent.AXIS_HSCROLL, SOURCE_MOUSE, -1, 1, 0, 0, 0)
			.build();
	}

	/** @hide teslalinux: a pointer (mouse) event was created: the pointer device is connected */
	public static void tlPointerSeen() {
		if (sPointerSeen)
			return;
		synchronized (sLock) {
			sPointerSeen = true;
		}
	}

	/**
	 * Gets information about the input device with the specified id.
	 * @return The input device or null if not found.
	 */
	public static InputDevice getDevice(int id) {
		synchronized (sLock) {
			refreshLocked();
			if (id == KeyCharacterMap.VIRTUAL_KEYBOARD)
				return sVirtualKeyboard;
			if (id == TL_TOUCHSCREEN_ID)
				return sTouchscreen;
			if (id == TL_POINTER_ID)
				return sPointer;
			return null;
		}
	}

	/** Gets the ids of all input devices in the system. */
	public static int[] getDeviceIds() {
		synchronized (sLock) {
			refreshLocked();
			if (sPointer != null)
				return new int[] {KeyCharacterMap.VIRTUAL_KEYBOARD, TL_TOUCHSCREEN_ID, TL_POINTER_ID};
			return new int[] {KeyCharacterMap.VIRTUAL_KEYBOARD, TL_TOUCHSCREEN_ID};
		}
	}

	public int getId() {
		return mId;
	}

	/** No device here is a game controller. */
	public int getControllerNumber() {
		return mControllerNumber;
	}

	/** @hide */
	public int getGeneration() {
		return mGeneration;
	}

	public int getVendorId() {
		return mVendorId;
	}

	public int getProductId() {
		return mProductId;
	}

	/** @hide */
	public int getDeviceBus() {
		return mDeviceBus;
	}

	public String getDescriptor() {
		return mDescriptor;
	}

	/** Virtual input devices are always created by the system and never represent physical hardware. */
	public boolean isVirtual() {
		return mId < 0;
	}

	public boolean isExternal() {
		return mIsExternal;
	}

	/** @hide */
	public boolean isFullKeyboard() {
		return (mSources & SOURCE_KEYBOARD) == SOURCE_KEYBOARD
		    && mKeyboardType == KEYBOARD_TYPE_ALPHABETIC;
	}

	public String getName() {
		return mName;
	}

	public int getSources() {
		return mSources;
	}

	public boolean supportsSource(int source) {
		return (mSources & source) == source;
	}

	public int getKeyboardType() {
		return mKeyboardType;
	}

	public KeyCharacterMap getKeyCharacterMap() {
		if (mKeyCharacterMap == null)
			mKeyCharacterMap = KeyCharacterMap.load(mId);
		return mKeyCharacterMap;
	}

	/**
	 * Gets whether the device is capable of producing the list of keycodes. AOSP asks the device's
	 * key layout: the virtual keyboard uses Generic.kl, which maps the key codes KeyEvent defines;
	 * the touchscreen and the pointer have no keys.
	 */
	public boolean[] hasKeys(int... keys) {
		boolean[] ret = new boolean[keys.length];
		final boolean keyboard = (mSources & SOURCE_KEYBOARD) == SOURCE_KEYBOARD;
		for (int i = 0; i < keys.length; i++) {
			ret[i] = keyboard && keys[i] > KeyEvent.KEYCODE_UNKNOWN && keys[i] <= KeyEvent.getMaxKeyCode();
		}
		return ret;
	}

	/** The virtual keyboard's layout is the identity: a location key code maps to itself. */
	public int getKeyCodeForKeyLocation(int locationKeyCode) {
		if ((mSources & SOURCE_KEYBOARD) != SOURCE_KEYBOARD)
			return KeyEvent.KEYCODE_UNKNOWN;
		return locationKeyCode;
	}

	public MotionRange getMotionRange(int axis) {
		final int numRanges = mMotionRanges.size();
		for (int i = 0; i < numRanges; i++) {
			final MotionRange range = mMotionRanges.get(i);
			if (range.mAxis == axis) {
				return range;
			}
		}
		return null;
	}

	public MotionRange getMotionRange(int axis, int source) {
		final int numRanges = mMotionRanges.size();
		for (int i = 0; i < numRanges; i++) {
			final MotionRange range = mMotionRanges.get(i);
			if (range.mAxis == axis && range.mSource == source) {
				return range;
			}
		}
		return null;
	}

	public List<MotionRange> getMotionRanges() {
		return mMotionRanges;
	}

	private void addMotionRange(int axis, int source, float min, float max, float flat, float fuzz, float resolution) {
		mMotionRanges.add(new MotionRange(axis, source, min, max, flat, fuzz, resolution));
	}

	private void setShouldSmoothScroll(boolean shouldSmoothScroll) {
		mViewBehavior.mShouldSmoothScroll = shouldSmoothScroll;
	}

	public ViewBehavior getViewBehavior() {
		return mViewBehavior;
	}

	/** @hide */
	public int getAssociatedDisplayId() {
		return mAssociatedDisplayId;
	}

	/** Returns true if input device is enabled. */
	public boolean isEnabled() {
		return mEnabled;
	}

	/** Whether the device has a vibrator: none of these does. */
	public boolean hasVibrator() {
		return mHasVibrator;
	}

	/** Reports whether the device has a built-in microphone. */
	public boolean hasMicrophone() {
		return mHasMicrophone;
	}

	/** @hide */
	public boolean hasButtonUnderPad() {
		return mHasButtonUnderPad;
	}

	/** @hide */
	public boolean hasSensor() {
		return mHasSensor;
	}

	/** @hide */
	public boolean hasBattery() {
		return mHasBattery;
	}

	/**
	 * Provides information about the range of values for a particular {@link MotionEvent} axis.
	 */
	public static final class MotionRange {
		private int mAxis;
		private int mSource;
		private float mMin;
		private float mMax;
		private float mFlat;
		private float mFuzz;
		private float mResolution;

		private MotionRange(int axis, int source, float min, float max, float flat, float fuzz, float resolution) {
			mAxis = axis;
			mSource = source;
			mMin = min;
			mMax = max;
			mFlat = flat;
			mFuzz = fuzz;
			mResolution = resolution;
		}

		public int getAxis() {
			return mAxis;
		}

		public int getSource() {
			return mSource;
		}

		public boolean isFromSource(int source) {
			return (getSource() & source) == source;
		}

		public float getMin() {
			return mMin;
		}

		public float getMax() {
			return mMax;
		}

		public float getRange() {
			return mMax - mMin;
		}

		public float getFlat() {
			return mFlat;
		}

		public float getFuzz() {
			return mFuzz;
		}

		public float getResolution() {
			return mResolution;
		}
	}

	/**
	 * Provides information on how views processing {@link MotionEvent}s generated by this input
	 * device should respond to the events.
	 */
	public static final class ViewBehavior {
		private static final boolean DEFAULT_SHOULD_SMOOTH_SCROLL = false;

		private final InputDevice mInputDevice;
		private boolean mShouldSmoothScroll = DEFAULT_SHOULD_SMOOTH_SCROLL;

		public ViewBehavior(InputDevice inputDevice) {
			mInputDevice = inputDevice;
		}

		public boolean shouldSmoothScroll(int axis, int source) {
			if (mInputDevice.getMotionRange(axis, source) == null) {
				return false;
			}
			return mShouldSmoothScroll;
		}
	}

	@Override
	public void writeToParcel(Parcel out, int flags) {
		out.writeInt(mId);
		out.writeInt(mGeneration);
		out.writeInt(mControllerNumber);
		out.writeString(mName);
		out.writeInt(mVendorId);
		out.writeInt(mProductId);
		out.writeInt(mDeviceBus);
		out.writeString(mDescriptor);
		out.writeInt(mIsExternal ? 1 : 0);
		out.writeInt(mSources);
		out.writeInt(mKeyboardType);
		out.writeInt(mHasVibrator ? 1 : 0);
		out.writeInt(mHasMicrophone ? 1 : 0);
		out.writeInt(mHasButtonUnderPad ? 1 : 0);
		out.writeInt(mHasSensor ? 1 : 0);
		out.writeInt(mHasBattery ? 1 : 0);
		out.writeInt(mAssociatedDisplayId);
		out.writeInt(mEnabled ? 1 : 0);

		int numRanges = mMotionRanges.size();
		numRanges = numRanges > MAX_RANGES ? MAX_RANGES : numRanges;
		out.writeInt(numRanges);
		for (int i = 0; i < numRanges; i++) {
			MotionRange range = mMotionRanges.get(i);
			out.writeInt(range.mAxis);
			out.writeInt(range.mSource);
			out.writeFloat(range.mMin);
			out.writeFloat(range.mMax);
			out.writeFloat(range.mFlat);
			out.writeFloat(range.mFuzz);
			out.writeFloat(range.mResolution);
		}
		out.writeInt(mViewBehavior.mShouldSmoothScroll ? 1 : 0);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public String toString() {
		StringBuilder description = new StringBuilder();
		description.append("Input Device ").append(mId).append(": ").append(mName).append("\n");
		description.append("  Descriptor: ").append(mDescriptor).append("\n");
		description.append("  Generation: ").append(mGeneration).append("\n");
		description.append("  Location: ").append(mIsExternal ? "external" : "built-in").append("\n");
		description.append("  Enabled: ").append(isEnabled()).append("\n");
		description.append("  Keyboard Type: ");
		switch (mKeyboardType) {
			case KEYBOARD_TYPE_NONE:
				description.append("none");
				break;
			case KEYBOARD_TYPE_NON_ALPHABETIC:
				description.append("non-alphabetic");
				break;
			case KEYBOARD_TYPE_ALPHABETIC:
				description.append("alphabetic");
				break;
		}
		description.append("\n");
		description.append("  Has Vibrator: ").append(mHasVibrator).append("\n");
		description.append("  Has mic: ").append(mHasMicrophone).append("\n");
		description.append("  Sources: 0x").append(Integer.toHexString(mSources)).append("\n");
		final int numAxes = mMotionRanges.size();
		for (int i = 0; i < numAxes; i++) {
			MotionRange range = mMotionRanges.get(i);
			description.append("    ").append(MotionEvent.axisToString(range.mAxis));
			description.append(": source=0x").append(Integer.toHexString(range.mSource));
			description.append(" min=").append(range.mMin);
			description.append(" max=").append(range.mMax);
			description.append(" flat=").append(range.mFlat);
			description.append(" fuzz=").append(range.mFuzz);
			description.append(" resolution=").append(range.mResolution);
			description.append("\n");
		}
		return description.toString();
	}
}
