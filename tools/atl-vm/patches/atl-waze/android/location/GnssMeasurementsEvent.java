package android.location;

import java.util.Collection;
import java.util.Collections;

public final class GnssMeasurementsEvent {
	public static abstract class Callback {
		public static final int STATUS_NOT_SUPPORTED = 0, STATUS_READY = 1, STATUS_LOCATION_DISABLED = 2, STATUS_NOT_ALLOWED = 3;
		public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {}
		public void onStatusChanged(int status) {}
	}
	public Collection<Object> getMeasurements() { return Collections.emptyList(); }
}
