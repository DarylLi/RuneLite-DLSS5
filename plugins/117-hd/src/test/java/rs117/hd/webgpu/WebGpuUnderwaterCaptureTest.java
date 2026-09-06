package rs117.hd.webgpu;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebGpuUnderwaterCaptureTest
{
	@Test
	public void compactDeepWaterStencilMatchesFullSceneAndOwnsItsArrays() throws Exception
	{
		assertSectorDepthsMatch(24, 32, false);
	}

	@Test
	public void compactShorelineStencilMatchesFullSceneAtSceneBoundary() throws Exception
	{
		assertSectorDepthsMatch(0, 16, true);
	}

	private static void assertSectorDepthsMatch(int localX, int localZ, boolean shoreline)
		throws Exception
	{
		Scene scene = mock(Scene.class);
		when(scene.getWorldViewId()).thenReturn(1);
		when(scene.getExtendedTiles()).thenReturn(new Tile[1][64][64]);
		WebGpuSceneBuffer.UnderwaterTerrainCapture source =
			WebGpuSceneBuffer.beginUnderwaterTerrainCapture(scene, 32 * 128f, 32 * 128f, 72);
		boolean[][][] water = mask(source, "waterTiles");
		boolean[][][] occupied = mask(source, "occupiedTiles");
		for (int x = 0; x < 64; x++)
			for (int z = 0; z < 64; z++)
			{
				occupied[0][x][z] = true;
				water[0][x][z] = !shoreline || z >= 19 + x / 4;
			}
		WebGpuSceneBuffer full = new WebGpuSceneBuffer();
		full.sharePreparedUnderwaterTerrain(WebGpuSceneBuffer.finalizeUnderwaterTerrain(source));
		WebGpuSceneBuffer.UnderwaterTerrainCapture detached =
			WebGpuSceneBuffer.copyUnderwaterSectorCapture(source, localX, localZ, 8);
		int maximumSpan = 8 + 2 * WebGpuSceneBuffer.underwaterCapturePaddingTiles();
		assertTrue(mask(detached, "waterTiles")[0].length <= maximumSpan);
		assertTrue(mask(detached, "waterTiles")[0][0].length <= maximumSpan);

		// A rebase or continuing scene scan must not modify an already-published
		// sector's worker input. Destroy the source classification before finalizing.
		for (int x = 0; x < 64; x++)
		{
			Arrays.fill(water[0][x], false);
			Arrays.fill(occupied[0][x], false);
		}
		WebGpuSceneBuffer compact = new WebGpuSceneBuffer();
		compact.sharePreparedUnderwaterTerrain(
			WebGpuSceneBuffer.finalizeUnderwaterTerrain(detached));
		Method depthAt = WebGpuSceneBuffer.class.getDeclaredMethod(
			"underwaterDepthAt", int.class, int.class, int.class);
		depthAt.setAccessible(true);
		for (int x = localX; x <= localX + 8; x++)
			for (int z = localZ; z <= localZ + 8; z++)
				assertEquals("depth at " + x + "," + z,
					(float) depthAt.invoke(full, 0, x, z),
					(float) depthAt.invoke(compact, 0, x, z), 0f);
	}

	private static boolean[][][] mask(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (boolean[][][]) field.get(target);
	}
}
