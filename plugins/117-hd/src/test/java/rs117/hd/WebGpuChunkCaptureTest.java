package rs117.hd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WebGpuChunkCaptureTest
{
	@Test
	public void nearRiverPublishesBeforeFarGeometryAndSurvivesRebase() throws Exception
	{
		WebGpuPlugin plugin = plugin();
		Tile[][][] tiles = new Tile[1][80][8];
		SceneTilePaint river = paint(1);
		SceneTilePaint land = paint(-1);
		for (int x = 0; x < 16; x++)
			for (int z = 0; z < 8; z++)
				tiles[0][x][z] = tile(x, z, x < 8 ? river : land);
		Tile distant = tile(64, 0, land);
		tiles[0][64][0] = distant;
		Scene scene = scene(tiles);
		when(scene.getTileHeights()).thenReturn(new int[1][81][9]);
		Object job = createJob(plugin, scene);
		setField(plugin, "staticCaptureJob", job);
		Map<Long, ?> snapshots = field(plugin, "staticSectorSnapshots");
		for (int step = 0; step < 10 && !snapshots.containsKey(0L); step++)
			advanceScan(plugin, job, Long.MAX_VALUE);

		Object riverSnapshot = snapshots.get(0L);
		assertNotNull("The nearest river must not wait for the full scene", riverSnapshot);
		assertNotNull(field(riverSnapshot, "underwaterCapture"));
		List<?> pending = field(job, "work");
		assertTrue((int) field(job, "scanWorkIndex") < pending.size());
		verify(distant, never()).getSceneLocation();

		Method rebase = WebGpuPlugin.class.getDeclaredMethod("softInvalidateStaticCapture");
		rebase.setAccessible(true);
		rebase.invoke(plugin);
		assertSame("A rebase must retain the owned river capture", riverSnapshot, snapshots.get(0L));
		assertNotNull(field(riverSnapshot, "underwaterCapture"));
	}

	@Test
	public void retainedSectorsDoNotConsumeSceneClassificationBudget() throws Exception
	{
		WebGpuPlugin plugin = plugin();
		Set<Long> committed = field(plugin, "committedStaticSectors");
		committed.add(0L);
		Tile[][][] tiles = new Tile[1][8][8];
		Tile retained = tile(0, 0, paint(-1));
		tiles[0][0][0] = retained;
		Object job = createJob(plugin, scene(tiles));
		assertTrue(advanceScan(plugin, job, Long.MAX_VALUE));
		verify(retained, never()).getSceneLocation();
	}

	@Test
	public void capturesMissingSectorsBeforeReclassifyingRetainedNearField() throws Exception
	{
		WebGpuPlugin plugin = plugin();
		Set<Long> committed = field(plugin, "committedStaticSectors");
		Set<Long> requested = field(plugin, "requestedStaticSectors");
		committed.add(0L);
		requested.add(1L << 32);

		Object job = createJob(plugin, scene(new Tile[1][24][8]));
		List<?> work = field(job, "work");
		assertEquals(3, work.size());
		assertEquals(2, sectorX(work.get(0)));
		assertEquals(0, sectorX(work.get(1)));
		assertEquals(1, sectorX(work.get(2)));
	}

	@Test
	public void drySectorCaptureUsesItsOwnBudgetAfterClassification() throws Exception
	{
		WebGpuPlugin plugin = plugin();
		Tile[][][] tiles = new Tile[1][8][8];
		for (int x = 0; x < 8; x++)
			for (int z = 0; z < 8; z++)
			{
				Tile tile = mock(Tile.class);
				when(tile.getSceneLocation()).thenReturn(new Point(x, z));
				when(tile.getGameObjects()).thenReturn(new GameObject[0]);
				tiles[0][x][z] = tile;
			}
		Object job = createJob(plugin, scene(tiles));
		List<?> work = field(job, "work");
		Object sector = work.get(0);

		// Give the call a deterministic time allowance while retaining the real
		// production tile-copy cap. Scanning 64 tiles must leave 32 copies available.
		assertFalse(advanceScan(plugin, job, Long.MAX_VALUE));
		assertTrue((boolean) field(sector, "scanComplete"));
		assertEquals(32, (int) field(sector, "rootIndex"));
		assertTrue(advanceScan(plugin, job, Long.MAX_VALUE));
		assertEquals(64, (int) field(sector, "rootIndex"));
	}

	@Test
	public void expiredDeadlineStillStopsCaptureAfterClassification() throws Exception
	{
		WebGpuPlugin plugin = plugin();
		Tile[][][] tiles = new Tile[1][8][8];
		Tile tile = mock(Tile.class);
		when(tile.getSceneLocation()).thenReturn(new Point(0, 0));
		when(tile.getGameObjects()).thenReturn(new GameObject[0]);
		tiles[0][0][0] = tile;
		Object job = createJob(plugin, scene(tiles));
		List<?> work = field(job, "work");
		Object sector = work.get(0);
		setField(sector, "scanComplete", true);
		List<Tile> roots = field(sector, "roots");
		roots.add(tile);

		assertFalse(advanceScan(plugin, job, 0));
		assertEquals(0, (int) field(sector, "rootIndex"));
	}

	private static WebGpuPlugin plugin() throws Exception
	{
		WebGpuPlugin plugin = new WebGpuPlugin();
		setField(plugin, "client", mock(Client.class));
		setField(plugin, "appliedSmoothTerrainMaterials", false);
		return plugin;
	}

	private static Scene scene(Tile[][][] tiles)
	{
		Scene scene = mock(Scene.class);
		when(scene.getWorldViewId()).thenReturn(1);
		when(scene.getExtendedTiles()).thenReturn(tiles);
		return scene;
	}

	private static SceneTilePaint paint(int texture)
	{
		SceneTilePaint paint = mock(SceneTilePaint.class);
		when(paint.getTexture()).thenReturn(texture);
		when(paint.getSwColor()).thenReturn(90);
		when(paint.getSeColor()).thenReturn(90);
		when(paint.getNeColor()).thenReturn(90);
		when(paint.getNwColor()).thenReturn(90);
		return paint;
	}

	private static Tile tile(int x, int z, SceneTilePaint paint)
	{
		Tile tile = mock(Tile.class);
		when(tile.getSceneLocation()).thenReturn(new Point(x, z));
		when(tile.getGameObjects()).thenReturn(new GameObject[0]);
		when(tile.getSceneTilePaint()).thenReturn(paint);
		return tile;
	}

	private static Object createJob(WebGpuPlugin plugin, Scene scene) throws Exception
	{
		Method method = WebGpuPlugin.class.getDeclaredMethod("createStaticCaptureJob", Scene.class);
		method.setAccessible(true);
		return method.invoke(plugin, scene);
	}

	private static boolean advanceScan(WebGpuPlugin plugin, Object job, long deadline) throws Exception
	{
		Method method = WebGpuPlugin.class.getDeclaredMethod(
			"advanceStaticSceneScan", job.getClass(), long.class);
		method.setAccessible(true);
		return (boolean) method.invoke(plugin, job, deadline);
	}

	private static int sectorX(Object work) throws Exception
	{
		return field(field(work, "builder"), "sectorX");
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
