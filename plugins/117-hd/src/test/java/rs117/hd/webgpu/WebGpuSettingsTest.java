package rs117.hd.webgpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebGpuSettingsTest
{
	@Test
	public void presetsChangeVisualsWithoutChangingPerformance()
	{
		WebGpuSettings settings = new WebGpuSettings();
		settings.drawDistance = 31;
		settings.renderScalePercent = 72;
		settings.applyPreset(WebGpuStyleMode.CINEMATIC);

		assertEquals(WebGpuStyleMode.CINEMATIC, settings.styleMode);
		assertEquals(31, settings.drawDistance);
		assertEquals(72, settings.renderScalePercent);
		assertTrue(settings.normalStrength > 1.0f);
		assertTrue(settings.contrast > 1.0f);
	}
}
