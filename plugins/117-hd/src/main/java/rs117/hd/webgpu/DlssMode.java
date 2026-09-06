package rs117.hd.webgpu;

public enum DlssMode {
	OFF(0, "Off"),
	MAX_PERFORMANCE(1, "Performance"),
	BALANCED(2, "Balanced"),
	MAX_QUALITY(3, "Quality"),
	ULTRA_PERFORMANCE(4, "Ultra Performance"),
	ULTRA_QUALITY(5, "Ultra Quality"),
	DLAA(6, "DLAA");

	public final int nativeValue;
	private final String label;

	DlssMode(int nativeValue, String label) {
		this.nativeValue = nativeValue;
		this.label = label;
	}

	@Override
	public String toString() {
		return label;
	}
}
