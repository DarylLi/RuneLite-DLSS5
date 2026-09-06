package rs117.hd.webgpu;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

/** RuneLite's draw-level and roof rules, shared by cached and callback terrain. */
public final class WebGpuSceneVisibility
{
	private final int minLevel;
	private final int currentLevel;
	private final int maxLevel;
	private final Set<Integer> hiddenRoofIds;

	public WebGpuSceneVisibility(
		int minLevel, int currentLevel, int maxLevel,
		Set<Integer> hiddenRoofIds, boolean removeAllRoofs)
	{
		this.minLevel = minLevel;
		this.currentLevel = currentLevel;
		this.maxLevel = removeAllRoofs ? Math.min(maxLevel, currentLevel) : maxLevel;
		this.hiddenRoofIds = hiddenRoofIds == null || hiddenRoofIds.isEmpty()
			? Collections.emptySet() : new HashSet<>(hiddenRoofIds);
	}

	/** Above-player floors can change visibility every frame and must not be cached. */
	public static boolean isCacheable(Scene scene, Tile tile, int currentLevel)
	{
		return isCacheable(scene, tile, scene.getMinLevel(), currentLevel);
	}

	public static boolean isCacheable(Scene scene, Tile tile, int minLevel, int currentLevel)
	{
		int level = drawLevel(scene, tile);
		return level >= minLevel && level <= currentLevel;
	}

	public boolean isVisible(Scene scene, Tile tile)
	{
		int level = drawLevel(scene, tile);
		if (level < minLevel || level > maxLevel)
			return false;
		if (level <= currentLevel)
			return true;
		int mapLevel = tile.getRenderLevel();
		if (mapLevel <= 0 || visibleBelow(scene, tile))
			return true;
		int offset = sceneOffset(scene);
		int x = tile.getSceneLocation().getX() + offset;
		int z = tile.getSceneLocation().getY() + offset;
		int[][][] roofs = scene.getRoofs();
		int roofId = roofs != null && mapLevel - 1 < roofs.length
			&& roofs[mapLevel - 1] != null && x >= 0 && x < roofs[mapLevel - 1].length
			&& roofs[mapLevel - 1][x] != null && z >= 0 && z < roofs[mapLevel - 1][x].length
			? roofs[mapLevel - 1][x][z] : 0;
		return roofId <= 0 || !hiddenRoofIds.contains(roofId);
	}

	public static int sceneOffset(Scene scene)
	{
		return scene.getWorldViewId() == WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;
	}

	private static int drawLevel(Scene scene, Tile tile)
	{
		// GPU SceneUploader places VIS_BELOW tiles on draw level zero even when
		// their source heights are on an upper plane. Bridge render levels already
		// refer to that source plane, while Tile.getPlane() is the draw plane.
		return visibleBelow(scene, tile) ? 0 : tile.getPlane();
	}

	private static boolean visibleBelow(Scene scene, Tile tile)
	{
		int level = tile.getRenderLevel();
		int offset = sceneOffset(scene);
		int x = tile.getSceneLocation().getX() + offset;
		int z = tile.getSceneLocation().getY() + offset;
		byte[][][] settings = scene.getExtendedTileSettings();
		return settings != null && level >= 0 && level < settings.length
			&& settings[level] != null && x >= 0 && x < settings[level].length
			&& settings[level][x] != null && z >= 0 && z < settings[level][x].length
			&& (settings[level][x][z] & Constants.TILE_FLAG_VIS_BELOW) != 0;
	}
}
