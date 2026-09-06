package rs117.hd.webgpu;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.Renderable;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;

import static rs117.hd.utils.ColorUtils.hsl;
import static rs117.hd.utils.MathUtils.RAND;

/**
 * WebGPU's lightweight equivalent of 117 HD's fishing-spot replacement.
 *
 * <p>The regular 117 HD renderer owns its replacement through {@code HdPlugin}
 * and {@code SceneContext}, so it cannot be shared with the standalone WebGPU
 * renderer. This class deliberately keeps the same replacement assets and NPC
 * list while depending only on RuneLite's public client objects.</p>
 */
public final class WebGpuFishingSpotReplacer
{
	private static final int FISHING_SPOT_MODEL_ID = 41238;
	private static final int FISHING_SPOT_ANIMATION_ID = 10793;
	private static final int FISHING_SPOT_COLOR = 23454;
	private static final int LAVA_SPOT_MODEL_ID = 2331;
	private static final int LAVA_SPOT_ANIMATION_ID = 525;
	private static final int LAVA_SPOT_COLOR = hsl("#837574");

	// Keep this in sync with 117 HD's FishingSpotReplacer. These NPCs use an
	// invisible vanilla interaction target plus a separate animated water model.
	private static final Set<Integer> FISHING_SPOT_IDS = Set.of(
		394, 635, 1506, 1507, 1508, 1509, 1510, 1511, 1512, 1513, 1514,
		1515, 1516, 1517, 1518, 1519, 1520, 1521, 1522, 1523, 1524, 1525,
		1526, 1527, 1528, 1529, 1530, 1531, 1532, 1533, 1534, 1535, 1536,
		1542, 1544, 2146, 2653, 2654, 2655, 3317, 3417, 3418, 3419, 3657,
		3913, 3914, 3915, 4079, 4080, 4081, 4082, 4316, 4476, 4477, 4710,
		4711, 4712, 4713, 4714, 5233, 5234, 5820, 5821, 6731, 6825, 7155,
		7199, 7200, 7323, 7459, 7460, 7461, 7462, 7463, 7464, 7465, 7466,
		7467, 7468, 7469, 7470, 7946, 7947, 8524, 8525, 8526, 8527, 9171,
		9172, 9173, 9174, 9478, 12267);
	private static final Set<Integer> LAVA_FISHING_SPOT_IDS = Set.of(4928);
	private static final Set<Integer> NPC_IDS = allFishingSpotIds();

	private final Client client;
	private final Map<Integer, RuneLiteObject> replacements = new HashMap<>();

	public WebGpuFishingSpotReplacer(Client client)
	{
		this.client = client;
	}

	public boolean isOriginalFishingSpot(Renderable renderable)
	{
		return renderable instanceof NPC && NPC_IDS.contains(((NPC) renderable).getId());
	}

	public void update()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			despawnAll();
			return;
		}

		Map<Integer, RuneLiteObject> stale = new HashMap<>(replacements);
		for (NPC npc : worldView.npcs())
		{
			if (!NPC_IDS.contains(npc.getId()) || npc.getLocalLocation() == null)
				continue;

			RuneLiteObject replacement = stale.remove(npc.getIndex());
			if (replacement == null)
				replacement = spawn(npc);
			if (replacement != null)
				replacement.setLocation(npc.getLocalLocation(), worldView.getPlane());
		}

		for (Map.Entry<Integer, RuneLiteObject> entry : stale.entrySet())
		{
			entry.getValue().setActive(false);
			replacements.remove(entry.getKey());
		}
	}

	public void despawnAll()
	{
		for (RuneLiteObject replacement : replacements.values())
			replacement.setActive(false);
		replacements.clear();
	}

	private RuneLiteObject spawn(NPC npc)
	{
		boolean lava = LAVA_FISHING_SPOT_IDS.contains(npc.getId());
		int modelId = lava ? LAVA_SPOT_MODEL_ID : FISHING_SPOT_MODEL_ID;
		int animationId = lava ? LAVA_SPOT_ANIMATION_ID : FISHING_SPOT_ANIMATION_ID;
		int recolor = lava ? LAVA_SPOT_COLOR : FISHING_SPOT_COLOR;

		ModelData modelData = client.loadModelData(modelId);
		if (modelData == null)
			return null;
		modelData = modelData.cloneColors();
		Arrays.fill(modelData.getFaceColors(), (short) recolor);

		AnimationController animation = new AnimationController(client, animationId);
		if (animation.getAnimation() != null)
			animation.setFrame(RAND.nextInt(animation.getAnimation().getDuration()));

		RuneLiteObject replacement = client.createRuneLiteObject();
		replacement.setAnimationController(animation);
		replacement.setOrientation(RAND.nextInt(4) * 512);
		replacement.setDrawFrontTilesFirst(false);
		replacement.setModel(modelData.light());
		replacement.setLocation(
			npc.getLocalLocation(), client.getTopLevelWorldView().getPlane());
		replacement.setActive(true);
		replacements.put(npc.getIndex(), replacement);
		return replacement;
	}

	private static Set<Integer> allFishingSpotIds()
	{
		Set<Integer> ids = new HashSet<>(FISHING_SPOT_IDS);
		ids.addAll(LAVA_FISHING_SPOT_IDS);
		return Set.copyOf(ids);
	}
}
