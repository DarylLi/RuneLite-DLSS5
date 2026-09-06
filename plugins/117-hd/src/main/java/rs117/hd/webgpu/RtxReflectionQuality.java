package rs117.hd.webgpu;

/** Controls water-reflection ray density, reach and roughness coverage. */
public enum RtxReflectionQuality
{
	PERFORMANCE(0, "Performance"),
	BALANCED(1, "Balanced"),
	HIGH(2, "High");

	public final int nativeValue;
	private final String displayName;

	RtxReflectionQuality(int nativeValue, String displayName)
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
