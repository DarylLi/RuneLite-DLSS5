package rs117.hd;

import net.runelite.api.Actor;
import net.runelite.api.DynamicObject;
import net.runelite.api.Renderable;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class WebGpuPluginCacheTest
{
	@Test
	public void actorsAndDynamicObjectsNeverEnterTheStaticSceneCache()
	{
		assertFalse(WebGpuPlugin.isCacheable(mock(Actor.class)));
		assertFalse(WebGpuPlugin.isCacheable(mock(DynamicObject.class)));
		assertTrue(WebGpuPlugin.isCacheable(mock(Renderable.class)));
	}
}
