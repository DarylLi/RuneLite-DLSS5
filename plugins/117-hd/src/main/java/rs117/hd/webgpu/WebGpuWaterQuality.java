package rs117.hd.webgpu;

public enum WebGpuWaterQuality
{
	STANDARD(0, "Standard"),
	ENHANCED(1, "Enhanced"),
	CINEMATIC(2, "Cinematic");

	public final int nativeValue;
	private final String displayName;

	WebGpuWaterQuality(int nativeValue, String displayName)
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
