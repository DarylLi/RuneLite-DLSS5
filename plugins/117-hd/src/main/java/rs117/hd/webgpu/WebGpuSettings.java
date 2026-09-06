package rs117.hd.webgpu;

import net.runelite.client.config.ConfigManager;

public final class WebGpuSettings
{
	private static final String GROUP = "117hd-webgpu";
	/**
	 * The extended RuneLite scene is 184 tiles wide.  A 72-tile camera radius
	 * leaves enough guard space for the client rebase while still being a useful
	 * step beyond the old 56-tile preview limit.
	 */
	public static final int MAX_DRAW_DISTANCE = 72;

	public WebGpuStyleMode styleMode = WebGpuStyleMode.HD_117;
	public int drawDistance = 50;
	public int renderScalePercent = 100;
	public int fpsLimit = 60;
	public boolean vsync = true;
	public boolean texturesEnabled = true;
	public boolean smoothTerrainMaterials = true;
	public boolean smoothModelShading = true;
	public int modelSmoothingStrengthPercent = 90;
	public float normalStrength = 1.0f;
	public float saturation = 1.0f;
	public float contrast = 1.05f;
	public float ambientIntensity = 0.42f;
	public float exposure = 1.0f;
	public float timeOfDay = 12.0f;
	public WebGpuWeatherMode weatherMode = WebGpuWeatherMode.AUTO;
	public int weatherIntensityPercent = 55;
	public int windStrengthPercent = 35;
	public WebGpuWaterQuality waterQuality = WebGpuWaterQuality.ENHANCED;
	public float waterWaveStrength = 1.0f;
	public int waterClarityPercent = 58;
	public boolean rtxEnabled;
	public int rtxShadowStrengthPercent = 82;
	public boolean dynamicRtxShadows;
	public boolean rtxReflections;
	public int rtxReflectionStrengthPercent = 58;
	public RtxReflectionQuality rtxReflectionQuality = RtxReflectionQuality.BALANCED;
	public DlssMode dlssMode = DlssMode.OFF;
	public boolean neuralRendering;
	public boolean frameGeneration;
	public boolean rayReconstruction;
	public int sharpnessPercent = 20;

	public static WebGpuSettings load(ConfigManager config)
	{
		WebGpuSettings settings = new WebGpuSettings();
		settings.styleMode = value(config, "styleMode", WebGpuStyleMode.class, settings.styleMode);
		settings.drawDistance = clamp(
			value(config, "drawDistance", Integer.class, settings.drawDistance),
			10, MAX_DRAW_DISTANCE);
		settings.renderScalePercent = clamp(value(config, "renderScale", Integer.class, settings.renderScalePercent), 50, 100);
		settings.fpsLimit = clamp(value(config, "fpsLimit", Integer.class, settings.fpsLimit), 30, 240);
		settings.vsync = value(config, "vsync", Boolean.class, settings.vsync);
		settings.texturesEnabled = value(config, "textures", Boolean.class, settings.texturesEnabled);
		settings.smoothTerrainMaterials = value(
			config, "smoothTerrainMaterials", Boolean.class, settings.smoothTerrainMaterials);
		settings.smoothModelShading = value(
			config, "smoothModelShading", Boolean.class, settings.smoothModelShading);
		settings.modelSmoothingStrengthPercent = clamp(value(
			config, "modelSmoothingStrength", Integer.class,
			settings.modelSmoothingStrengthPercent), 0, 100);
		settings.normalStrength = clamp(value(config, "normalStrength", Float.class, settings.normalStrength), 0.0f, 2.0f);
		settings.saturation = clamp(value(config, "saturation", Float.class, settings.saturation), 0.0f, 2.0f);
		settings.contrast = clamp(value(config, "contrast", Float.class, settings.contrast), 0.5f, 1.5f);
		settings.ambientIntensity = clamp(value(config, "ambient", Float.class, settings.ambientIntensity), 0.05f, 1.0f);
		settings.exposure = clamp(value(config, "exposure", Float.class, settings.exposure), 0.25f, 2.0f);
		settings.timeOfDay = clamp(value(config, "timeOfDay", Float.class, settings.timeOfDay), 0.0f, 24.0f);
		settings.weatherMode = value(config, "weatherMode", WebGpuWeatherMode.class, settings.weatherMode);
		settings.weatherIntensityPercent = clamp(value(
			config, "weatherIntensity", Integer.class, settings.weatherIntensityPercent), 0, 100);
		settings.windStrengthPercent = clamp(value(
			config, "windStrength", Integer.class, settings.windStrengthPercent), 0, 100);
		settings.waterQuality = value(config, "waterQuality", WebGpuWaterQuality.class, settings.waterQuality);
		settings.waterWaveStrength = clamp(value(
			config, "waterWaveStrength", Float.class, settings.waterWaveStrength), 0.0f, 2.0f);
		settings.waterClarityPercent = clamp(value(
			config, "waterClarity", Integer.class, settings.waterClarityPercent), 0, 100);
		settings.rtxEnabled = value(config, "rtx", Boolean.class, false);
		settings.rtxShadowStrengthPercent = clamp(value(
			config, "rtxShadowStrength", Integer.class, settings.rtxShadowStrengthPercent), 0, 100);
		settings.dynamicRtxShadows = value(config, "rtxDynamicShadows", Boolean.class, false);
		settings.rtxReflections = value(config, "rtxReflections", Boolean.class, false);
		settings.rtxReflectionStrengthPercent = clamp(value(
			config, "rtxReflectionStrength", Integer.class,
			settings.rtxReflectionStrengthPercent), 0, 100);
		settings.rtxReflectionQuality = value(
			config, "rtxReflectionQuality", RtxReflectionQuality.class,
			settings.rtxReflectionQuality);
		settings.dlssMode = value(config, "dlssMode", DlssMode.class, DlssMode.OFF);
		// NVIDIA keeps the legacy Ultra Quality enum value for compatibility, but
		// current Streamline rejects it for ordinary RuneLite viewport sizes.
		// Preserve the enum so old profiles still parse, then migrate them to the
		// supported Quality preset before the native request is made.
		if (settings.dlssMode == DlssMode.ULTRA_QUALITY)
			settings.dlssMode = DlssMode.MAX_QUALITY;
		settings.neuralRendering = value(config, "dlssNeuralRendering", Boolean.class, false);
		// Frame Generation remains hard-disabled until the native Present hook is
		// complete. The settings panel explains the exact missing contract.
		settings.frameGeneration = false;
		settings.rayReconstruction = value(config, "rayReconstruction", Boolean.class, false);
		settings.sharpnessPercent = clamp(value(config, "sharpness", Integer.class, settings.sharpnessPercent), 0, 100);
		return settings;
	}

	public void save(ConfigManager config)
	{
		config.setConfiguration(GROUP, "styleMode", styleMode);
		config.setConfiguration(GROUP, "drawDistance", drawDistance);
		config.setConfiguration(GROUP, "renderScale", renderScalePercent);
		config.setConfiguration(GROUP, "fpsLimit", fpsLimit);
		config.setConfiguration(GROUP, "vsync", vsync);
		config.setConfiguration(GROUP, "textures", texturesEnabled);
		config.setConfiguration(GROUP, "smoothTerrainMaterials", smoothTerrainMaterials);
		config.setConfiguration(GROUP, "smoothModelShading", smoothModelShading);
		config.setConfiguration(GROUP, "modelSmoothingStrength", modelSmoothingStrengthPercent);
		config.setConfiguration(GROUP, "normalStrength", normalStrength);
		config.setConfiguration(GROUP, "saturation", saturation);
		config.setConfiguration(GROUP, "contrast", contrast);
		config.setConfiguration(GROUP, "ambient", ambientIntensity);
		config.setConfiguration(GROUP, "exposure", exposure);
		config.setConfiguration(GROUP, "timeOfDay", timeOfDay);
		config.setConfiguration(GROUP, "weatherMode", weatherMode);
		config.setConfiguration(GROUP, "weatherIntensity", weatherIntensityPercent);
		config.setConfiguration(GROUP, "windStrength", windStrengthPercent);
		config.setConfiguration(GROUP, "waterQuality", waterQuality);
		config.setConfiguration(GROUP, "waterWaveStrength", waterWaveStrength);
		config.setConfiguration(GROUP, "waterClarity", waterClarityPercent);
		config.setConfiguration(GROUP, "rtx", rtxEnabled);
		config.setConfiguration(GROUP, "rtxShadowStrength", rtxShadowStrengthPercent);
		config.setConfiguration(GROUP, "rtxDynamicShadows", dynamicRtxShadows);
		config.setConfiguration(GROUP, "rtxReflections", rtxReflections);
		config.setConfiguration(GROUP, "rtxReflectionStrength", rtxReflectionStrengthPercent);
		config.setConfiguration(GROUP, "rtxReflectionQuality", rtxReflectionQuality);
		config.setConfiguration(GROUP, "dlssMode", dlssMode);
		config.setConfiguration(GROUP, "dlssNeuralRendering", neuralRendering);
		config.setConfiguration(GROUP, "frameGeneration", false);
		config.setConfiguration(GROUP, "rayReconstruction", rayReconstruction);
		config.setConfiguration(GROUP, "sharpness", sharpnessPercent);
	}

	public void applyPreset(WebGpuStyleMode mode)
	{
		styleMode = mode;
		texturesEnabled = true;
		switch (mode)
		{
			case CLASSIC:
				normalStrength = 0.35f;
				saturation = 0.90f;
				contrast = 0.95f;
				ambientIntensity = 0.62f;
				exposure = 0.92f;
				break;
			case CINEMATIC:
				normalStrength = 1.25f;
				saturation = 0.92f;
				contrast = 1.16f;
				ambientIntensity = 0.30f;
				exposure = 1.08f;
				break;
			case HD_117:
			default:
				normalStrength = 1.0f;
				saturation = 1.0f;
				contrast = 1.05f;
				ambientIntensity = 0.42f;
				exposure = 1.0f;
				break;
		}
	}

	private static <T> T value(ConfigManager config, String key, Class<T> type, T fallback)
	{
		String raw = config.getConfiguration(GROUP, key);
		if (raw == null || raw.trim().isEmpty())
			return fallback;
		try
		{
			String text = raw.trim();
			Object result;
			if (type == Integer.class)
				result = Integer.valueOf(text);
			else if (type == Float.class)
			{
				Float number = Float.valueOf(text);
				if (!Float.isFinite(number))
					return fallback;
				result = number;
			}
			else if (type == Boolean.class)
			{
				if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text))
					return fallback;
				result = Boolean.valueOf(text);
			}
			else if (type.isEnum())
				result = Enum.valueOf(type.asSubclass(Enum.class), text);
			else
				return fallback;
			return type.cast(result);
		}
		catch (IllegalArgumentException ex)
		{
			return fallback;
		}
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static float clamp(float value, float minimum, float maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}
}
