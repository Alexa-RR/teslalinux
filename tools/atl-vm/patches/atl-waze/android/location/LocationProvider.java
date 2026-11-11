package android.location;

public class LocationProvider {
	public static final int OUT_OF_SERVICE = 0, TEMPORARILY_UNAVAILABLE = 1, AVAILABLE = 2;
	private final String name;
	public LocationProvider(String name) { this.name = name; }
	public String getName() { return name; }
	public boolean meetsCriteria(Criteria criteria) { return true; }
	public boolean requiresNetwork() { return !"gps".equals(name); }
	public boolean requiresSatellite() { return "gps".equals(name); }
	public boolean requiresCell() { return false; }
	public boolean hasMonetaryCost() { return false; }
	public boolean supportsAltitude() { return true; }
	public boolean supportsSpeed() { return true; }
	public boolean supportsBearing() { return true; }
	public int getPowerRequirement() { return 1; }
	public int getAccuracy() { return 1; }
}
