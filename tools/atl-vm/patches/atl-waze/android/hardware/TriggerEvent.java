/*
 * Copyright (C) 2013 The Android Open Source Project
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
 * This class represents a Trigger Event - the event associated with a Trigger Sensor. (AOSP.)
 */
public final class TriggerEvent {
	/** The length and contents of the values array depends on which sensor type is being monitored. */
	public final float[] values;

	/** The sensor that generated this event. */
	public Sensor sensor;

	/** The time in nanosecond at which the event happened. */
	public long timestamp;

	TriggerEvent(int size) {
		values = new float[size];
	}
}
