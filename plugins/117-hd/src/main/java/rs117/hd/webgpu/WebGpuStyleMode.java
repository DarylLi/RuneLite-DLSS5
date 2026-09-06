package rs117.hd.webgpu;

public enum WebGpuStyleMode
{
	CLASSIC(0, "Classic"),
	HD_117(1, "117 HD"),
	CINEMATIC(2, "Cinematic");

	public final int nativeValue;
	private final String displayName;

	WebGpuStyleMode(int nativeValue, String displayName)
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
