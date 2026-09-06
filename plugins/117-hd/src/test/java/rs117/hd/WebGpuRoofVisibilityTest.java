package rs117.hd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;
import org.junit.Test;
import rs117.hd.webgpu.WebGpuSceneBuffer;
import rs117.hd.webgpu.WebGpuSceneVisibility;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebGpuRoofVisibilityTest
{
	private static final int OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

	@Test
	public void roofIdsOnlyHideUpperDrawLevelsAndRespectEnginePlaneBounds()
	{
		Scene scene = scene();
		Tile upper = tile(1, 1);
		Tile ground = tile(0, 0);
		scene.getRoofs()[0][OFFSET + 1][OFFSET + 2] = 17;
		WebGpuSceneVisibility visible = visibility(0, 0, 3, Collections.emptySet(), false);
		WebGpuSceneVisibility hidden = visibility(0, 0, 3, Collections.singleton(17), false);
		assertTrue(visible.isVisible(scene, upper));
		assertFalse(hidden.isVisible(scene, upper));
		assertTrue(hidden.isVisible(scene, ground));
		assertFalse(visibility(0, 0, 0, Collections.emptySet(), false).isVisible(scene, upper));
		assertFalse(visibility(1, 1, 3, Collections.emptySet(), false).isVisible(scene, ground));
		assertTrue(visibility(0, 1, 3, Collections.singleton(17), false).isVisible(scene, upper));
		assertFalse(WebGpuSceneVisibility.isCacheable(scene, upper, 0));
		assertTrue(WebGpuSceneVisibility.isCacheable(scene, ground, 0));
	}

	@Test
	public void bridgeAndVisibleBelowUseDrawPlaneRatherThanHeightPlane()
	{
		Scene scene = scene();
		Tile bridge = tile(0, 1);
		scene.getRoofs()[0][OFFSET + 1][OFFSET + 2] = 17;
		WebGpuSceneVisibility hidden = visibility(0, 0, 3, Collections.singleton(17), true);
		assertTrue(hidden.isVisible(scene, bridge));
		assertTrue(WebGpuSceneVisibility.isCacheable(scene, bridge, 0));
		Tile visibleBelow = tile(2, 2);
		scene.getExtendedTileSettings()[2][OFFSET + 1][OFFSET + 2] = Constants.TILE_FLAG_VIS_BELOW;
		assertTrue(hidden.isVisible(scene, visibleBelow));
		assertTrue(WebGpuSceneVisibility.isCacheable(scene, visibleBelow, 0));
	}

	@Test
	public void callbackTerrainMatchesBridgeSurfaceAndTranslatesOnlyNewVertices() throws Exception
	{
		Scene scene = scene();
		Tile upper = tile(1, 2);
		Tile below = tile(0, 0);
		when(upper.getBridge()).thenReturn(below);
		scene.getExtendedTiles()[1][OFFSET + 1][OFFSET + 2] = upper;
		for (int x = OFFSET + 1; x <= OFFSET + 2; x++)
			for (int z = OFFSET + 2; z <= OFFSET + 3; z++)
				scene.getTileHeights()[2][x][z] = -100;
		WebGpuPlugin plugin = plugin();
		set(plugin, "sceneEpochOffsetX", 1024);
		set(plugin, "sceneEpochOffsetZ", 2048);
		WebGpuSceneBuffer buffer = field(plugin, "sceneBuffer");
		buffer.addTilePaint(scene, below.getSceneTilePaint(), 0, 1, 2);
		buffer.markStaticGeometryComplete();
		float staticX = buffer.packedVertices().getFloat(0);
		plugin.drawScenePaint(scene, upper.getSceneTilePaint(), 2, 1, 2);
		plugin.drawScenePaint(scene, upper.getSceneTilePaint(), 2, 1, 2);
		plugin.drawScenePaint(scene, below.getSceneTilePaint(), 0, 1, 2);
		assertEquals("One cached ground tile and one upper callback tile", 12, buffer.vertexCount());
		assertEquals(6, buffer.staticVertexCount());
		assertEquals(staticX, buffer.packedVertices().getFloat(0), 0f);
		int firstDynamic = 6 * WebGpuSceneBuffer.PACKED_VERTEX_BYTES;
		assertEquals(1280f, buffer.packedVertices().getFloat(firstDynamic), 0f);
		assertEquals(-100f, buffer.packedVertices().getFloat(firstDynamic + 4), 0f);
		assertEquals(2432f, buffer.packedVertices().getFloat(firstDynamic + 8), 0f);
	}

	@Test
	public void hiddenRoofChangesDoNotRebuildResidentGeometry() throws Exception
	{
		Scene scene = scene();
		Tile upper = tile(1, 1);
		scene.getExtendedTiles()[1][OFFSET + 1][OFFSET + 2] = upper;
		scene.getRoofs()[0][OFFSET + 1][OFFSET + 2] = 17;
		WebGpuPlugin plugin = plugin();
		set(plugin, "staticEpochValid", true);
		set(plugin, "staticEpochScene", scene);
		set(plugin, "staticEpochPlane", 0);
		set(plugin, "staticVisibilityMinLevel", 0);
		WebGpuSceneBuffer buffer = field(plugin, "sceneBuffer");
		long revision = buffer.staticRevision();
		int generation = field(plugin, "staticSectorGeneration");
		Set<Integer> mutableHidden = new HashSet<>();
		mutableHidden.add(17);
		plugin.preSceneDraw(scene, null, 0, 0, 0, 0, 0, 0, 0, 3, mutableHidden);
		mutableHidden.clear(); // The client can reuse its own set after the callback.
		plugin.drawScenePaint(scene, upper.getSceneTilePaint(), 1, 1, 2);
		assertEquals(0, buffer.vertexCount());
		plugin.preSceneDraw(scene, null, 0, 0, 0, 0, 0, 0, 0, 3, Collections.emptySet());
		plugin.drawScenePaint(scene, upper.getSceneTilePaint(), 1, 1, 2);
		assertEquals(6, buffer.vertexCount());
		assertEquals(revision, buffer.staticRevision());
		assertEquals(generation, (int) field(plugin, "staticSectorGeneration"));
	}

	@Test
	public void removeAllVarbitRejectsUpperCallbacksEvenWithoutRoofIds() throws Exception
	{
		Scene scene = scene();
		Tile upper = tile(1, 1);
		scene.getExtendedTiles()[1][OFFSET + 1][OFFSET + 2] = upper;
		WebGpuPlugin plugin = plugin();
		Client client = field(plugin, "client");
		when(client.getVarbitValue(VarbitID.OPTION_HIDE_ROOFTOPS)).thenReturn(1);
		plugin.drawScenePaint(scene, upper.getSceneTilePaint(), 1, 1, 2);
		assertEquals(0, ((WebGpuSceneBuffer) field(plugin, "sceneBuffer")).vertexCount());
	}

	@Test
	public void upperModelsAndTerrainNeverEnterStaticSectorOrShadowGeometry() throws Exception
	{
		Scene scene = scene();
		Tile upper = tile(1, 1);
		GameObject roof = mock(GameObject.class);
		when(roof.getHash()).thenReturn(55L);
		when(upper.getGameObjects()).thenReturn(new GameObject[] {roof});
		WebGpuPlugin plugin = plugin();
		Map<Long, Object> builders = new HashMap<>();
		Set<Long> capturedObjects = new HashSet<>();
		Method capture = WebGpuPlugin.class.getDeclaredMethod(
			"captureStaticTileChain", Scene.class, Tile.class, Map.class, Set.class, Set.class);
		capture.setAccessible(true);
		capture.invoke(plugin, scene, upper, builders, capturedObjects, new HashSet<Tile>());
		assertTrue(builders.isEmpty());
		assertTrue(capturedObjects.isEmpty());
	}

	@Test
	public void initialCallbackPlaneBoundsOverrideDifferentSceneMinimum() throws Exception
	{
		Scene scene = scene();
		WebGpuPlugin plugin = plugin();
		WebGpuSceneBuffer buffer = field(plugin, "sceneBuffer");
		long revision = buffer.staticRevision();
		plugin.preSceneDraw(scene, null, 0, 0, 0, 0, 0, 1, 1, 3, Collections.emptySet());
		assertTrue("The first callback must invalidate capture made with fallback planes",
			buffer.staticRevision() > revision);
		Method cacheable = WebGpuPlugin.class.getDeclaredMethod("isStaticTileCacheable", Scene.class, Tile.class);
		cacheable.setAccessible(true);
		assertFalse((boolean) cacheable.invoke(plugin, scene, tile(0, 0)));
		assertTrue((boolean) cacheable.invoke(plugin, scene, tile(1, 1)));
		assertFalse((boolean) cacheable.invoke(plugin, scene, tile(2, 2)));
	}

	@Test
	public void objectCallbackUsesOwningRoofIncludingLargeObjectOrigin() throws Exception
	{
		Scene scene = scene();
		Tile upper = tile(1, 1);
		GameObject roof = mock(GameObject.class);
		when(roof.getSceneMinLocation()).thenReturn(new Point(1, 2));
		when(roof.getX()).thenReturn(512); // Object center differs from owning tile.
		when(roof.getY()).thenReturn(512);
		when(upper.getGameObjects()).thenReturn(new GameObject[] {roof});
		scene.getExtendedTiles()[1][OFFSET + 1][OFFSET + 2] = upper;
		scene.getRoofs()[0][OFFSET + 1][OFFSET + 2] = 17;
		WebGpuPlugin plugin = plugin();
		set(plugin, "visibilityScene", scene);
		set(plugin, "sceneVisibility", visibility(0, 0, 3, Collections.singleton(17), false));
		Method visible = WebGpuPlugin.class.getDeclaredMethod(
			"isVisibleCallbackObject", Scene.class, net.runelite.api.TileObject.class);
		visible.setAccessible(true);
		assertFalse((boolean) visible.invoke(plugin, scene, roof));
		set(plugin, "sceneVisibility", visibility(0, 0, 3, Collections.emptySet(), false));
		assertTrue((boolean) visible.invoke(plugin, scene, roof));
	}

	private static WebGpuPlugin plugin() throws Exception
	{
		WebGpuPlugin plugin = new WebGpuPlugin();
		set(plugin, "client", mock(Client.class));
		((WebGpuSceneBuffer) field(plugin, "sceneBuffer")).markStaticGeometryComplete();
		return plugin;
	}

	private static WebGpuSceneVisibility visibility(int min, int current, int max, Set<Integer> hidden, boolean all)
	{
		return new WebGpuSceneVisibility(min, current, max, hidden, all);
	}

	private static Tile tile(int plane, int renderPlane)
	{
		Tile tile = mock(Tile.class);
		when(tile.getPlane()).thenReturn(plane);
		when(tile.getRenderLevel()).thenReturn(renderPlane);
		when(tile.getSceneLocation()).thenReturn(new Point(1, 2));
		when(tile.getGameObjects()).thenReturn(new GameObject[0]);
		SceneTilePaint paint = mock(SceneTilePaint.class);
		when(paint.getTexture()).thenReturn(-1);
		when(paint.getNeColor()).thenReturn(90);
		when(paint.getNwColor()).thenReturn(90);
		when(paint.getSwColor()).thenReturn(90);
		when(paint.getSeColor()).thenReturn(90);
		when(tile.getSceneTilePaint()).thenReturn(paint);
		return tile;
	}

	private static Scene scene()
	{
		Scene scene = mock(Scene.class);
		when(scene.getWorldViewId()).thenReturn(WorldView.TOPLEVEL);
		when(scene.getExtendedTiles()).thenReturn(new Tile[4][184][184]);
		when(scene.getExtendedTileSettings()).thenReturn(new byte[4][184][184]);
		when(scene.getTileHeights()).thenReturn(new int[4][185][185]);
		when(scene.getRoofs()).thenReturn(new int[4][184][184]);
		return scene;
	}

	@SuppressWarnings("unchecked")
	private static <T> T field(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(target);
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
