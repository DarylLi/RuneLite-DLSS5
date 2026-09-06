package rs117.hd.webgpu;

public enum WebGpuWeatherMode
{
	CLEAR(0, "Clear"),
	CLOUDY(1, "Cloudy"),
	RAIN(2, "Rain"),
	STORM(3, "Storm"),
	AUTO(4, "Auto");

	public final int nativeValue;
	private final String displayName;

	WebGpuWeatherMode(int nativeValue, String displayName)
	{
		this.nativeValue = nativeValue;
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
