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
 * This class is the listener used to handle Trigger Sensors. Trigger Sensors are sensors that
 * trigger an event and are automatically disabled. (AOSP.)
 */
public abstract class TriggerEventListener {
	/** The method that is called when the sensor is triggered. */
	public abstract void onTrigger(TriggerEvent event);
}
