/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware;

/**
 * This class represents a {@link android.hardware.Sensor Sensor} event and holds information such
 * as the sensor's type, the time-stamp, accuracy and of course the sensor's data. (AOSP fields;
 * the (float[], Sensor) constructor is the one ATL's native accelerometer source uses.)
 */
public class SensorEvent {
	/** The length and contents of the values array depends on which sensor type is being monitored. */
	public final float[] values;

	/** The sensor that generated this event. */
	public Sensor sensor;

	/** The accuracy of this event. See {@link SensorManager SensorManager} for details. */
	public int accuracy;

	/** The time in nanoseconds at which the event happened (SystemClock.elapsedRealtimeNanos base). */
	public long timestamp;

	/** Whether this is the first event following a sensor's activation (or re-activation). */
	public boolean firstEventAfterDiscontinuity;

	SensorEvent(int valueSize) {
		values = new float[valueSize];
	}

	public SensorEvent(float[] values, Sensor sensor) {
		this.values = values;
		this.sensor = sensor;
		this.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
		this.timestamp = android.os.SystemClock.elapsedRealtimeNanos();
	}
}
