package rs117.hd.webgpu;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import rs117.hd.utils.ColorUtils;
import rs117.hd.utils.ExpressionParser;
import rs117.hd.utils.ExpressionPredicate;
import rs117.hd.utils.VariableSupplier;

/** Loads 117's real material replacements without initializing its OpenGL renderer. */
public final class WebGpu117ResourceLoader
{
	private static final String RESOURCE_ROOT = "/rs117/hd/scene/";
	private static final String TEXTURE_ROOT = RESOURCE_ROOT + "textures/";

	public static final class Atlas
	{
		public final int[] rgbaPixels;
		/** Vanilla RuneScape diffuse textures; 117-only cells are neutral white. */
		public final int[] vanillaRgbaPixels;
		public final int[] normalPixels;
		/** Per texture: brightness, perceptual roughness, specular strength, flags. */
		public final float[] materialParameters;
		public final int width;
		public final int height;
		public final int tileSize;
		public final int textureCount;
		public final int loadedTextureCount;
		public final int hdTextureCount;
		public final int groundTextureCount;
		public final GroundTextureResolver groundTextureResolver;
		public final LightCatalog lightCatalog;

		private Atlas(
			int[] rgbaPixels,
			int[] vanillaRgbaPixels,
			int[] normalPixels,
			float[] materialParameters,
			int width,
			int height,
			int tileSize,
			int textureCount,
			int loadedTextureCount,
			int hdTextureCount,
			int groundTextureCount,
			GroundTextureResolver groundTextureResolver,
			LightCatalog lightCatalog)
		{
			this.rgbaPixels = rgbaPixels;
			this.vanillaRgbaPixels = vanillaRgbaPixels;
			this.normalPixels = normalPixels;
			this.materialParameters = materialParameters;
			this.width = width;
			this.height = height;
			this.tileSize = tileSize;
			this.textureCount = textureCount;
			this.loadedTextureCount = loadedTextureCount;
			this.hdTextureCount = hdTextureCount;
			this.groundTextureCount = groundTextureCount;
			this.groundTextureResolver = groundTextureResolver;
			this.lightCatalog = lightCatalog;
		}
	}

	public static final class HdLight
	{
		public final Integer worldX;
		public final Integer worldY;
		public final int plane;
		public final float height;
		public final float radius;
		public final float strength;
		public final float[] color;
		public final float[] offset;
		public final String type;
		public final float duration;
		public final float range;

		private HdLight(JsonObject json)
		{
			worldX = json.has("worldX") ? json.get("worldX").getAsInt() : null;
			worldY = json.has("worldY") ? json.get("worldY").getAsInt() : null;
			plane = readInt(json, "plane", 0);
			height = readFloat(json, "height", 0);
			radius = readFloat(json, "radius", 300);
			strength = readFloat(json, "strength", 5);
			type = json.has("type") ? json.get("type").getAsString() : "STATIC";
			duration = readFloat(json, "duration", 0);
			range = readFloat(json, "range", 0);
			offset = readVector(json, "offset", false);
			color = readVector(json, "color", true);
		}

		public float animatedStrength(long milliseconds)
		{
			return strength * animationMultiplier(milliseconds);
		}

		public float animatedRadius(long milliseconds)
		{
			float multiplier = animationMultiplier(milliseconds);
			return "FLICKER".equals(type) ? radius * 1.5f : radius * multiplier;
		}

		private float animationMultiplier(long milliseconds)
		{
			float amount = range / 100f;
			if (amount == 0)
				return 1;
			if ("PULSE".equals(type) && duration > 0)
			{
				float phase = (milliseconds % Math.max(1L, (long) duration)) / duration;
				float triangle = 1 - 2 * Math.abs(phase - .5f);
				return Math.max(0, 1 + (2 * triangle - 1) * amount);
			}
			if ("FLICKER".equals(type))
			{
				double time = milliseconds * .001;
				double noise = (Math.sin(time * 11) + Math.sin(time * 23) * .5
					+ Math.sin(time * 47) * .25) / 1.75;
				return Math.max(0, 1 + (float) noise * amount);
			}
			return 1;
		}
	}

	public static final class LightCatalog
	{
		public final List<HdLight> worldLights = new ArrayList<>();
		public final Map<Integer, List<HdLight>> objectLights = new HashMap<>();
		public final Map<Integer, List<HdLight>> npcLights = new HashMap<>();
		public final Map<Integer, List<HdLight>> projectileLights = new HashMap<>();
		public final Map<Integer, List<HdLight>> graphicsObjectLights = new HashMap<>();

		private static void add(
			Map<Integer, List<HdLight>> destination, Set<Integer> ids, HdLight light)
		{
			for (int id : ids)
				destination.computeIfAbsent(id, ignored -> new ArrayList<>()).add(light);
		}
	}

	/** Resolves the same deterministic material variants used by 117 for terrain tiles. */
	public static final class GroundTextureResolver
	{
		private static final int MAINLAND_MIN_REGION = 3872;
		private static final int MAINLAND_MAX_REGION = 15937;

		private final List<GroundRule> rules;
		private final Map<String, GroundRule> rulesByName;
		private final Map<String, AreaDefinition> areasByName;
		private final Map<String, int[]> textureIdsByGroundMaterial;
		private final Map<String, Integer> waterSurfaceColorsByName;
		private final boolean[] knownVanillaTextures;

		private GroundTextureResolver(
			List<GroundRule> rules,
			Map<String, AreaDefinition> areasByName,
			Map<String, int[]> textureIdsByGroundMaterial,
			Map<String, Integer> waterSurfaceColorsByName,
			boolean[] knownVanillaTextures)
		{
			this.rules = rules;
			this.rulesByName = new HashMap<>();
			for (GroundRule rule : rules)
				if (rule.name != null)
					rulesByName.put(rule.name, rule);
			this.areasByName = areasByName;
			this.textureIdsByGroundMaterial = textureIdsByGroundMaterial;
			this.waterSurfaceColorsByName = waterSurfaceColorsByName;
			this.knownVanillaTextures = knownVanillaTextures;
		}

		/** Fully resolved 117 tile rule, including non-material behavior. */
		public static final class Surface
		{
			public final int packedHsl;
			public final int textureId;
			public final boolean matched;
			public final boolean water;
			public final boolean blended;
			public final boolean forced;
			public final boolean overlayPriority;
			public final int heightOffset;
			public final int uvOrientation;
			/** Reciprocal scale, matching TileOverride.normalize(). */
			public final float uvScale;
			private final GroundTextureResolver resolver;
			private final GroundRule rule;
			private final String groundMaterial;
			private final Integer waterSurfaceColor;

			private Surface(
				GroundTextureResolver resolver, GroundRule rule, String groundMaterial,
				int packedHsl, int textureId, boolean matched, boolean water, boolean blended,
				boolean forced, boolean overlayPriority, int heightOffset,
				int uvOrientation, float uvScale)
			{
				this.resolver = resolver;
				this.rule = rule;
				this.groundMaterial = groundMaterial;
				this.waterSurfaceColor = water && rule != null
					? resolver.waterSurfaceColorsByName.get(rule.waterType) : null;
				// Textured water's incoming HSL can contain only brightness. The
				// shared native water material needs the actual regional water tint.
				this.packedHsl = waterSurfaceColor == null ? packedHsl : waterSurfaceColor;
				this.textureId = textureId;
				this.matched = matched;
				this.water = water;
				this.blended = blended;
				this.forced = forced;
				this.overlayPriority = overlayPriority;
				this.heightOffset = heightOffset;
				this.uvOrientation = uvOrientation;
				this.uvScale = uvScale;
			}

			public int modifyColor(int hsl)
			{
				if (waterSurfaceColor != null)
					return waterSurfaceColor;
				return rule == null ? hsl : rule.modifyColor(hsl);
			}

			public int textureIdAt(int worldX, int worldY, int plane)
			{
				return water ? -2 : resolver.select(groundMaterial, worldX, worldY, plane);
			}
		}

		public boolean isKnownVanillaTexture(int textureId)
		{
			return textureId >= 0 && textureId < knownVanillaTextures.length
				&& knownVanillaTextures[textureId];
		}

		/** Selects the same deterministic seabed material used by 117 HD. */
		public int resolveUnderwaterTextureId(int worldX, int worldY, int plane)
		{
			return select("UNDERWATER_GENERIC", worldX, worldY, plane);
		}

		public int resolveTextureId(
			int overlayId,
			int underlayId,
			int worldX,
			int worldY,
			int plane,
			int packedHsl)
		{
			return resolveSurface(
				overlayId, underlayId, worldX, worldY, plane, packedHsl, true).textureId;
		}

		public long resolveMaterial(
			int overlayId,
			int underlayId,
			int worldX,
			int worldY,
			int plane,
			int packedHsl)
		{
			Surface surface = resolveSurface(
				overlayId, underlayId, worldX, worldY, plane, packedHsl, true);
			return (long) surface.packedHsl << 32 | surface.textureId & 0xffffffffL;
		}

		/** Resolves only the overlay or underlay selected by a shaped-tile face. */
		public long resolveFaceMaterial(
			int overlayId,
			int underlayId,
			int worldX,
			int worldY,
			int plane,
			int packedHsl,
			boolean overlayFace)
		{
			Surface surface = resolveFaceSurface(
				overlayId, underlayId, worldX, worldY, plane, packedHsl,
				overlayFace, true);
			return (long) surface.packedHsl << 32 | surface.textureId & 0xffffffffL;
		}

		public Surface resolveSurface(
			int overlayId, int underlayId, int worldX, int worldY, int plane,
			int packedHsl, boolean blendingEnabled)
		{
			return resolveSurface(
				overlayId, underlayId, worldX, worldY, plane, packedHsl,
				0, blendingEnabled);
		}

		public Surface resolveFaceSurface(
			int overlayId, int underlayId, int worldX, int worldY, int plane,
			int packedHsl, boolean overlayFace, boolean blendingEnabled)
		{
			return resolveSurface(
				overlayId, underlayId, worldX, worldY, plane, packedHsl,
				overlayFace ? 1 : 2, blendingEnabled);
		}

		private Surface resolveSurface(
			int overlayId,
			int underlayId,
			int worldX,
			int worldY,
			int plane,
			int packedHsl,
			int queriedLayer,
			boolean blendingEnabled)
		{
			GroundRule match = null;
			int matchIndex = Integer.MAX_VALUE;
			boolean queriedAsOverlay = false;
			if (queriedLayer != 2)
				for (GroundRule rule : rules)
					if (contains(rule.overlayIds, overlayId)
						&& applies(rule, worldX, worldY, plane))
					{
						match = rule;
						matchIndex = rule.index;
						queriedAsOverlay = true;
						break;
					}
			if (queriedLayer != 1)
				for (GroundRule rule : rules)
					if (match == null && contains(rule.underlayIds, underlayId)
						&& applies(rule, worldX, worldY, plane))
					{
						match = rule;
						matchIndex = rule.index;
						queriedAsOverlay = false;
						break;
					}
			for (GroundRule rule : rules)
			{
				if (rule.index > matchIndex)
					break;
				if (rule.overlayIds == null && rule.underlayIds == null
					&& rule.area != null && applies(rule, worldX, worldY, plane))
				{
					match = rule;
					break;
				}
			}
			GroundRule resolved = resolve(
				match, packedHsl, blendingEnabled, new HashSet<>());
			if (resolved == null)
			{
				// TileOverride.NONE falls back to DIRT for untextured terrain.
				return new Surface(
					this, null, "DIRT",
					packedHsl, select("DIRT", worldX, worldY, plane), false,
					false, blendingEnabled, false, false, 0, 0, 1f);
			}
			int modifiedHsl = resolved.modifyColor(packedHsl);
			boolean water = resolved.waterType != null && !"NONE".equals(resolved.waterType);
			return new Surface(
				this, resolved, resolved.groundMaterial,
				modifiedHsl,
				water ? -2 : select(resolved.groundMaterial, worldX, worldY, plane),
				true, water,
				blendingEnabled && resolved.blended,
				resolved.forced,
				queriedAsOverlay != resolved.blendedAsOpposite,
				resolved.heightOffset,
				resolved.uvOrientation,
				resolved.uvScale == 0 ? 1f : 1f / resolved.uvScale);
		}

		private GroundRule resolve(
			GroundRule rule, int packedHsl, boolean blendingEnabled,
			Set<GroundRule> visited)
		{
			if (rule == null || !visited.add(rule))
				return rule;
			int h = packedHsl >> 10 & 63;
			int s = packedHsl >> 7 & 7;
			int l = packedHsl & 127;
			VariableSupplier variables = name -> {
				switch (name)
				{
					case "h": return h;
					case "s": return s;
					case "l": return l;
					case "blendingColors":
					case "blendingTextures": return blendingEnabled;
					case "blendingTexturesOnly": return false;
					default: return 0f;
				}
			};
			for (ReplacementRule replacement : rule.replacements)
				if (replacement.predicate.test(variables))
					return resolve(
						rulesByName.get(replacement.name), packedHsl,
						blendingEnabled, visited);
			return rule;
		}

		private boolean applies(GroundRule rule, int x, int y, int plane)
		{
			if ("ALL".equals(rule.area))
				return true;
			if ("OVERWORLD".equals(rule.area))
			{
				AreaDefinition overworld = areasByName.get("OVERWORLD");
				return overworld == null
					? isOverworld(x, y)
					: overworld.contains(x, y, plane, areasByName, new HashSet<>());
			}
			AreaDefinition area = areasByName.get(rule.area);
			return area != null && area.contains(x, y, plane, areasByName, new HashSet<>());
		}

		private int select(String groundMaterial, int x, int y, int plane)
		{
			int[] textureIds = textureIdsByGroundMaterial.get(groundMaterial);
			if (textureIds == null || textureIds.length == 0)
				return -1;
			long hash = x;
			hash = hash * 31 + y;
			hash = hash * 31 + plane;
			long seed = (hash ^ 0x5DEECE66DL) & ((1L << 48) - 1);
			seed = (seed * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
			int random = (int) (seed >>> (48 - 31));
			return textureIds[random % textureIds.length];
		}

		private static boolean isOverworld(int worldX, int worldY)
		{
			int regionId = (worldX >> 6 << 8) | worldY >> 6;
			return regionId >= MAINLAND_MIN_REGION && regionId <= MAINLAND_MAX_REGION;
		}

		private static boolean contains(int[] values, int value)
		{
			if (values == null)
				return false;
			for (int candidate : values)
				if (candidate == value)
					return true;
			return false;
		}
	}

	private static final class AreaDefinition
	{
		String name;
		final List<int[]> aabbs = new ArrayList<>();
		final List<String> areas = new ArrayList<>();

		boolean contains(
			int x, int y, int plane,
			Map<String, AreaDefinition> definitions,
			Set<String> visited)
		{
			if (!visited.add(name))
				return false;
			for (int[] bounds : aabbs)
			{
				int minX = Math.min(bounds[0], bounds.length >= 4 ? bounds[bounds.length == 6 ? 3 : 2] : bounds[0]);
				int minY = Math.min(bounds[1], bounds.length >= 4 ? bounds[bounds.length == 6 ? 4 : 3] : bounds[1]);
				int maxX = Math.max(bounds[0], bounds.length >= 4 ? bounds[bounds.length == 6 ? 3 : 2] : bounds[0]);
				int maxY = Math.max(bounds[1], bounds.length >= 4 ? bounds[bounds.length == 6 ? 4 : 3] : bounds[1]);
				int minPlane = Integer.MIN_VALUE;
				int maxPlane = Integer.MAX_VALUE;
				if (bounds.length == 3)
					minPlane = maxPlane = bounds[2];
				else if (bounds.length == 5)
					minPlane = maxPlane = bounds[4];
				else if (bounds.length == 6)
				{
					minPlane = Math.min(bounds[2], bounds[5]);
					maxPlane = Math.max(bounds[2], bounds[5]);
				}
				if (minX <= x && x <= maxX && minY <= y && y <= maxY
					&& minPlane <= plane && plane <= maxPlane)
					return true;
			}
			for (String childName : areas)
			{
				AreaDefinition child = definitions.get(childName);
				if (child != null && child.contains(
					x, y, plane, definitions, new HashSet<>(visited)))
					return true;
			}
			return false;
		}
	}

	private static final class MaterialDefinition
	{
		String name;
		String parent;
		int vanillaTextureIndex = -1;
		String replacementCondition;
		Boolean overrideBaseColor;
		Boolean unlit;
		Boolean hasTransparency;
		Float brightness;
		Float specularStrength;
		Float specularGloss;
		String normalMap;
		boolean normalMapSpecified;
		final List<String> materialsToReplace = new ArrayList<>();
	}

	private static final class MaterialProperties
	{
		float brightness = 1f;
		float roughness = .82f;
		float specularStrength;
		boolean overrideBaseColor;
		boolean unlit;
		boolean hasTransparency;
		boolean water;
		String normalMap;
	}

	private static final class GroundMaterialDefinition
	{
		String name;
		final List<String> materials = new ArrayList<>();
	}

	private static final class GroundTextureSource
	{
		final String materialName;
		final BufferedImage image;
		final int vanillaTextureId;

		private GroundTextureSource(
			String materialName, BufferedImage image, int vanillaTextureId)
		{
			this.materialName = materialName;
			this.image = image;
			this.vanillaTextureId = vanillaTextureId;
		}
	}

	private static final class GroundTexturePlan
	{
		final Map<String, Integer> textureIdsByMaterial = new HashMap<>();
		final LinkedHashMap<String, GroundTextureSource> extraSources = new LinkedHashMap<>();
	}

	private static final class GroundRule
	{
		int index;
		String name;
		String area;
		String groundMaterial;
		String waterType;
		boolean blended = true;
		boolean blendedAsOpposite;
		boolean forced;
		int uvOrientation;
		float uvScale = 1f;
		int heightOffset;
		int setHue = -1;
		int shiftHue;
		int minHue;
		int maxHue = 63;
		int setSaturation = -1;
		int shiftSaturation;
		int minSaturation;
		int maxSaturation = 7;
		int setLightness = -1;
		int shiftLightness;
		int minLightness;
		int maxLightness = 127;
		int[] overlayIds;
		int[] underlayIds;
		final List<ReplacementRule> replacements = new ArrayList<>();

		int modifyColor(int packedHsl)
		{
			int hue = setHue >= 0 ? setHue : (packedHsl >> 10 & 63) + shiftHue;
			int saturation = setSaturation >= 0
				? setSaturation : (packedHsl >> 7 & 7) + shiftSaturation;
			int lightness = setLightness >= 0
				? setLightness : (packedHsl & 127) + shiftLightness;
			hue = Math.max(minHue, Math.min(maxHue, hue));
			saturation = Math.max(minSaturation, Math.min(maxSaturation, saturation));
			lightness = Math.max(minLightness, Math.min(maxLightness, lightness));
			return hue << 10 | saturation << 7 | lightness;
		}
	}

	private static final class ReplacementRule
	{
		String name;
		ExpressionPredicate predicate;
	}

	private final List<MaterialDefinition> definitions;
	private final Map<String, MaterialDefinition> definitionsByName;
	private final Map<String, GroundMaterialDefinition> groundMaterialsByName;
	private final List<GroundRule> groundRules;
	private final Map<String, AreaDefinition> areasByName;
	private final Map<String, Integer> waterSurfaceColorsByName;

	private WebGpu117ResourceLoader() throws IOException
	{
		definitions = loadDefinitions();
		definitionsByName = new HashMap<>();
		for (MaterialDefinition definition : definitions)
			definitionsByName.put(definition.name, definition);
		groundMaterialsByName = loadGroundMaterials();
		areasByName = loadAreas();
		groundRules = loadGroundRules();
		waterSurfaceColorsByName = loadWaterSurfaceColors();
	}

	public static Atlas buildAtlas(TextureProvider provider, int tileSize, int columns) throws IOException
	{
		if (provider == null || provider.getTextures() == null || provider.getTextures().length == 0)
			return null;
		WebGpu117ResourceLoader loader = new WebGpu117ResourceLoader();
		Texture[] vanillaTextures = provider.getTextures();
		int vanillaTextureCount = vanillaTextures.length;
		String[] vanillaMaterials = loader.mapVanillaMaterials(vanillaTextureCount);
		GroundTexturePlan groundPlan = loader.createGroundTexturePlan(
			vanillaMaterials, vanillaTextureCount);
		int textureCount = vanillaTextureCount + groundPlan.extraSources.size();
		columns = Math.max(1, Math.min(columns, textureCount));
		int rows = (textureCount + columns - 1) / columns;
		int width = columns * tileSize;
		int height = rows * tileSize;
		int[] atlas = new int[width * height];
		int[] vanillaAtlas = new int[width * height];
		Arrays.fill(vanillaAtlas, packRgba(0xffffffff));
		int[] normalAtlas = new int[width * height];
		Arrays.fill(normalAtlas, packRgba(0xff8080ff));
		float[] materialParameters = new float[textureCount * 4];
		for (int textureId = 0; textureId < textureCount; textureId++)
		{
			materialParameters[textureId * 4] = 1f;
			materialParameters[textureId * 4 + 1] = .82f;
			materialParameters[textureId * 4 + 2] = 0f;
		}
		int loaded = 0;
		int loadedHd = 0;

		BufferedImage scaled = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[tileSize];
		double previousBrightness = provider.getBrightness();
		provider.setBrightness(1);
		try
		{
			for (int textureId = 0; textureId < vanillaTextureCount; textureId++)
			{
				if (vanillaTextures[textureId] == null)
					continue;
				int[] vanillaPixels = provider.load(textureId);
				if (vanillaPixels != null && vanillaPixels.length > 0)
					loader.writeVanillaTexture(
						vanillaAtlas, width, textureId, columns, tileSize, vanillaPixels);
				String materialName = loader.resolveReplacement(vanillaMaterials[textureId]);
				BufferedImage image = loader.loadMaterialTexture(materialName);
				boolean isHd = image != null;
				if (image == null)
				{
					if (vanillaPixels == null || vanillaPixels.length == 0)
						continue;
					loader.writeVanillaTexture(
						atlas, width, textureId, columns, tileSize, vanillaPixels);
				}
				else
				{
					loader.writeHdTexture(
						atlas, width, textureId, columns, tileSize, image, scaled, row);
				}
				loader.writeMaterialData(
					normalAtlas, materialParameters, width, textureId, columns, tileSize,
					materialName, scaled, row);
				loaded++;
				if (isHd)
					loadedHd++;
			}

			int extraIndex = 0;
			for (GroundTextureSource source : groundPlan.extraSources.values())
			{
				int textureId = vanillaTextureCount + extraIndex++;
				int[] vanillaPixels = source.vanillaTextureId >= 0
					&& source.vanillaTextureId < vanillaTextureCount
					? provider.load(source.vanillaTextureId) : null;
				if (vanillaPixels != null && vanillaPixels.length > 0)
					loader.writeVanillaTexture(
						vanillaAtlas, width, textureId, columns, tileSize, vanillaPixels);
				if (source.image != null)
					loader.writeHdTexture(
						atlas, width, textureId, columns, tileSize,
						source.image, scaled, row);
				else if (vanillaPixels != null && vanillaPixels.length > 0)
					loader.writeVanillaTexture(
						atlas, width, textureId, columns, tileSize, vanillaPixels);
				else
					loader.writeSolidTexture(
						atlas, width, textureId, columns, tileSize, 0xffffffff);
				loader.writeMaterialData(
					normalAtlas, materialParameters, width, textureId, columns, tileSize,
					source.materialName, scaled, row);
			}
		}
		finally
		{
			provider.setBrightness(previousBrightness);
		}

		boolean[] knownVanillaTextures = new boolean[vanillaTextureCount];
		for (int textureId = 0; textureId < vanillaTextureCount; textureId++)
			knownVanillaTextures[textureId] = vanillaMaterials[textureId] != null;
		GroundTextureResolver resolver = loader.createGroundTextureResolver(
			groundPlan.textureIdsByMaterial, knownVanillaTextures);
		LightCatalog lightCatalog = loadLightCatalog();

		return loaded == 0 ? null : new Atlas(
			atlas, vanillaAtlas, normalAtlas, materialParameters,
			width, height, tileSize, textureCount, loaded, loadedHd,
			groundPlan.extraSources.size(), resolver, lightCatalog);
	}

	private static LightCatalog loadLightCatalog() throws IOException
	{
		JsonObject gamevals;
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "gamevals.json"))
		{
			if (stream == null)
				throw new IOException("117 gamevals.json was not found");
			gamevals = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		Map<String, Integer> objects = readGamevals(gamevals, "objects");
		Map<String, Integer> npcs = readGamevals(gamevals, "npcs");
		Map<String, Integer> spotanims = readGamevals(gamevals, "spotanims");
		LightCatalog catalog = new LightCatalog();
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "lights.json"))
		{
			if (stream == null)
				throw new IOException("117 lights.json was not found");
			JsonArray lights = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			for (JsonElement element : lights)
			{
				JsonObject json = element.getAsJsonObject();
				HdLight light = new HdLight(json);
				if (light.worldX != null && light.worldY != null)
					catalog.worldLights.add(light);
				LightCatalog.add(catalog.objectLights,
					readIds(json, "objectIds", objects), light);
				LightCatalog.add(catalog.npcLights,
					readIds(json, "npcIds", npcs), light);
				LightCatalog.add(catalog.projectileLights,
					readIds(json, "projectileIds", spotanims), light);
				LightCatalog.add(catalog.graphicsObjectLights,
					readIds(json, "graphicsObjectIds", spotanims), light);
			}
		}
		return catalog;
	}

	private static Map<String, Integer> readGamevals(JsonObject gamevals, String key)
	{
		Map<String, Integer> result = new HashMap<>();
		JsonObject values = gamevals.getAsJsonObject(key);
		if (values != null)
			for (Map.Entry<String, JsonElement> entry : values.entrySet())
				result.put(entry.getKey(), entry.getValue().getAsInt());
		return result;
	}

	private static Set<Integer> readIds(
		JsonObject json, String key, Map<String, Integer> gamevals)
	{
		Set<Integer> result = new HashSet<>();
		JsonArray values = json.getAsJsonArray(key);
		if (values == null)
			return result;
		for (JsonElement value : values)
		{
			if (value.getAsJsonPrimitive().isNumber())
				result.add(value.getAsInt());
			else
			{
				Integer resolved = gamevals.get(value.getAsString());
				if (resolved != null)
					result.add(resolved);
			}
		}
		return result;
	}

	private GroundTexturePlan createGroundTexturePlan(
		String[] vanillaMaterials, int vanillaTextureCount) throws IOException
	{
		GroundTexturePlan plan = new GroundTexturePlan();
		Map<String, Integer> vanillaCellsByResolvedMaterial = new HashMap<>();
		for (int textureId = 0; textureId < vanillaMaterials.length; textureId++)
		{
			String resolved = resolveReplacement(vanillaMaterials[textureId]);
			if (resolved != null)
				vanillaCellsByResolvedMaterial.putIfAbsent(resolved, textureId);
		}

		Set<String> referencedGroundMaterials = new LinkedHashSet<>();
		for (GroundRule rule : groundRules)
			if (rule.groundMaterial != null)
				referencedGroundMaterials.add(rule.groundMaterial);
		// TileOverride.NONE and underwater terrain are synthesized by 117.
		referencedGroundMaterials.add("DIRT");
		referencedGroundMaterials.add("UNDERWATER_GENERIC");

		Map<String, Integer> extraIds = new HashMap<>();
		for (String groundMaterialName : referencedGroundMaterials)
		{
			GroundMaterialDefinition groundMaterial = groundMaterialsByName.get(groundMaterialName);
			if (groundMaterial == null)
				continue;
			for (String materialName : groundMaterial.materials)
			{
				if (plan.textureIdsByMaterial.containsKey(materialName))
					continue;
				String resolvedName = resolveReplacement(materialName);
				Integer textureId = vanillaCellsByResolvedMaterial.get(resolvedName);
				if (textureId == null)
				{
					textureId = extraIds.get(resolvedName);
					if (textureId == null)
					{
						BufferedImage image = loadMaterialTexture(resolvedName);
						int vanillaTextureId = resolveVanillaTextureIndex(resolvedName);
						MaterialProperties properties = resolveMaterialProperties(resolvedName);
						if (image == null && vanillaTextureId < 0
							&& !hasSurfaceData(properties))
						{
							textureId = -1;
						}
						else
						{
							textureId = vanillaTextureCount + plan.extraSources.size();
							plan.extraSources.put(resolvedName,
								new GroundTextureSource(resolvedName, image, vanillaTextureId));
						}
						extraIds.put(resolvedName, textureId);
					}
				}
				plan.textureIdsByMaterial.put(materialName, textureId);
			}
		}
		return plan;
	}

	private GroundTextureResolver createGroundTextureResolver(
		Map<String, Integer> groundTextureIds, boolean[] knownVanillaTextures)
	{
		Map<String, int[]> idsByGroundMaterial = new HashMap<>();
		for (GroundMaterialDefinition groundMaterial : groundMaterialsByName.values())
		{
			int count = 0;
			for (String materialName : groundMaterial.materials)
				if (groundTextureIds.containsKey(materialName)
					&& groundTextureIds.get(materialName) >= 0)
					count++;
			int[] ids = new int[count];
			int index = 0;
			for (String materialName : groundMaterial.materials)
			{
				Integer textureId = groundTextureIds.get(materialName);
				if (textureId != null && textureId >= 0)
					ids[index++] = textureId;
			}
			idsByGroundMaterial.put(groundMaterial.name, ids);
		}
		return new GroundTextureResolver(
			groundRules, areasByName, idsByGroundMaterial,
			waterSurfaceColorsByName, knownVanillaTextures);
	}

	private Map<String, Integer> loadWaterSurfaceColors() throws IOException
	{
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "water_types.json"))
		{
			if (stream == null)
				throw new IOException("117 water_types.json is missing from the distribution");
			JsonArray array = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			Map<String, Integer> result = new HashMap<>();
			for (JsonElement element : array)
			{
				if (!element.isJsonObject())
					continue;
				JsonObject json = element.getAsJsonObject();
				if (!json.has("name") || !json.has("surfaceColor")
					|| json.get("surfaceColor").isJsonNull())
					continue;
				// Reuse the light-color reader for both hex and 0-255 RGB arrays,
				// then encode the same HSL stream as other terrain. Native decodes
				// it to linear RGB exactly once before lighting.
				result.put(json.get("name").getAsString(),
					ColorUtils.linearRgbToPackedHsl(readVector(json, "surfaceColor", true)));
			}
			return result;
		}
	}

	private List<MaterialDefinition> loadDefinitions() throws IOException
	{
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(RESOURCE_ROOT + "materials.json"))
		{
			if (stream == null)
				throw new IOException("117 materials.json is missing from the distribution");
			JsonArray array = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			List<MaterialDefinition> result = new ArrayList<>(array.size());
			for (JsonElement element : array)
			{
				if (!element.isJsonObject())
					continue;
				JsonObject json = element.getAsJsonObject();
				if (!json.has("name"))
					continue;
				MaterialDefinition definition = new MaterialDefinition();
				definition.name = json.get("name").getAsString();
				if (json.has("parent"))
					definition.parent = json.get("parent").getAsString();
				if (json.has("vanillaTextureIndex"))
					definition.vanillaTextureIndex = json.get("vanillaTextureIndex").getAsInt();
				if (json.has("replacementCondition"))
					definition.replacementCondition = json.get("replacementCondition").getAsString();
				if (json.has("overrideBaseColor"))
					definition.overrideBaseColor = json.get("overrideBaseColor").getAsBoolean();
				if (json.has("unlit"))
					definition.unlit = json.get("unlit").getAsBoolean();
				if (json.has("hasTransparency"))
					definition.hasTransparency = json.get("hasTransparency").getAsBoolean();
				if (json.has("brightness"))
					definition.brightness = srgbToLinear(json.get("brightness").getAsFloat());
				if (json.has("specularStrength"))
					definition.specularStrength = json.get("specularStrength").getAsFloat();
				if (json.has("specularGloss"))
					definition.specularGloss = json.get("specularGloss").getAsFloat();
				if (json.has("normalMap"))
				{
					definition.normalMapSpecified = true;
					if (!json.get("normalMap").isJsonNull())
						definition.normalMap = json.get("normalMap").getAsString();
				}
				if (json.has("materialsToReplace"))
					for (JsonElement replacement : json.getAsJsonArray("materialsToReplace"))
						definition.materialsToReplace.add(replacement.getAsString());
				result.add(definition);
			}
			return result;
		}
	}

	private Map<String, GroundMaterialDefinition> loadGroundMaterials() throws IOException
	{
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "ground_materials.json"))
		{
			if (stream == null)
				throw new IOException("117 ground_materials.json is missing from the distribution");
			JsonArray array = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			Map<String, GroundMaterialDefinition> result = new LinkedHashMap<>();
			for (JsonElement element : array)
			{
				if (!element.isJsonObject())
					continue;
				JsonObject json = element.getAsJsonObject();
				if (!json.has("name") || !json.has("materials"))
					continue;
				GroundMaterialDefinition definition = new GroundMaterialDefinition();
				definition.name = json.get("name").getAsString();
				for (JsonElement material : json.getAsJsonArray("materials"))
					definition.materials.add(material.getAsString());
				result.put(definition.name, definition);
			}

			// These two are constructed programmatically by 117's GroundMaterialManager.
			GroundMaterialDefinition dirt = new GroundMaterialDefinition();
			dirt.name = "DIRT";
			dirt.materials.add("DIRT_1");
			dirt.materials.add("DIRT_2");
			result.put(dirt.name, dirt);
			GroundMaterialDefinition underwater = new GroundMaterialDefinition();
			underwater.name = "UNDERWATER_GENERIC";
			underwater.materials.addAll(dirt.materials);
			result.put(underwater.name, underwater);
			return result;
		}
	}

	private List<GroundRule> loadGroundRules() throws IOException
	{
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "tile_overrides.json"))
		{
			if (stream == null)
				throw new IOException("117 tile_overrides.json is missing from the distribution");
			JsonArray array = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			List<GroundRule> result = new ArrayList<>();
			VariableSupplier constants = name -> {
				switch (name)
				{
					case "textures":
					case "groundTextures": return true;
					// Blending is a live WebGPU setting and must remain in the
					// predicate instead of being folded while the atlas is built.
					case "blendingColors":
					case "blendingTextures":
					case "blendingTexturesOnly": return null;
					// ExpressionParser only folds numeric/boolean constants. Returning
					// strings leaves both names unresolved at runtime, where they each
					// fall back to zero and incorrectly make every winter rule true.
					case "season": return 0f; // SUMMER
					case "SeasonalTheme.WINTER": return 1f;
					default: return null;
				}
			};
			int ruleIndex = 0;
			for (JsonElement element : array)
			{
				if (!element.isJsonObject())
					continue;
				JsonObject json = element.getAsJsonObject();
				GroundRule rule = new GroundRule();
				rule.index = ruleIndex++;
				if (json.has("name"))
					rule.name = json.get("name").getAsString();
				if (json.has("area"))
					rule.area = json.get("area").getAsString();
				if (json.has("groundMaterial"))
					rule.groundMaterial = json.get("groundMaterial").getAsString();
				if (json.has("waterType"))
					rule.waterType = json.get("waterType").getAsString();
				rule.blended = !json.has("blended") || json.get("blended").getAsBoolean();
				rule.blendedAsOpposite = json.has("blendedAsOpposite")
					&& json.get("blendedAsOpposite").getAsBoolean();
				rule.forced = json.has("forced") && json.get("forced").getAsBoolean();
				rule.uvOrientation = readInt(json, "uvOrientation", 0);
				rule.uvScale = readFloat(json, "uvScale", 1f);
				rule.heightOffset = readInt(json, "heightOffset", 0);
				rule.setHue = readInt(json, "setHue", -1);
				rule.shiftHue = readInt(json, "shiftHue", 0);
				rule.minHue = readInt(json, "minHue", 0);
				rule.maxHue = readInt(json, "maxHue", 63);
				rule.setSaturation = readInt(json, "setSaturation", -1);
				rule.shiftSaturation = readInt(json, "shiftSaturation", 0);
				rule.minSaturation = readInt(json, "minSaturation", 0);
				rule.maxSaturation = readInt(json, "maxSaturation", 7);
				rule.setLightness = readInt(json, "setLightness", -1);
				rule.shiftLightness = readInt(json, "shiftLightness", 0);
				rule.minLightness = readInt(json, "minLightness", 0);
				rule.maxLightness = readInt(json, "maxLightness", 127);
				if (rule.forced)
				{
					// TileOverride.normalize() makes forced hidden faces white unless
					// the rule supplies a more specific color constraint.
					rule.minHue = rule.maxHue = 0;
					rule.minSaturation = rule.maxSaturation = 0;
					rule.minLightness = rule.maxLightness = 127;
				}
				rule.overlayIds = readIds(json, "overlayIds");
				rule.underlayIds = readIds(json, "underlayIds");
				if (json.has("replacements"))
				{
					for (Map.Entry<String, JsonElement> replacement
						: json.getAsJsonObject("replacements").entrySet())
					{
						String expression = ExpressionParser.mergeJsonExpressions("||", replacement.getValue());
						Object parsed = ExpressionParser.parseExpression(expression, constants);
						if (parsed instanceof Boolean && !(Boolean) parsed)
							continue;
						ReplacementRule replacementRule = new ReplacementRule();
						replacementRule.name = replacement.getKey();
						replacementRule.predicate = parsed instanceof Boolean
							? ExpressionPredicate.TRUE
							: ExpressionParser.asExpression(parsed).toPredicate();
						rule.replacements.add(replacementRule);
						if (parsed instanceof Boolean)
							break;
					}
				}
				result.add(rule);
			}
			return result;
		}
	}

	private Map<String, AreaDefinition> loadAreas() throws IOException
	{
		try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
			RESOURCE_ROOT + "areas.json"))
		{
			if (stream == null)
				throw new IOException("117 areas.json is missing from the distribution");
			JsonArray array = new JsonParser().parse(
				new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonArray();
			Map<String, AreaDefinition> result = new HashMap<>();
			for (JsonElement element : array)
			{
				if (!element.isJsonObject())
					continue;
				JsonObject json = element.getAsJsonObject();
				if (!json.has("name"))
					continue;
				AreaDefinition area = new AreaDefinition();
				area.name = json.get("name").getAsString();
				if (json.has("aabbs"))
					for (JsonElement bounds : json.getAsJsonArray("aabbs"))
						area.aabbs.add(readIntArray(bounds.getAsJsonArray()));
				// Area.containsPoint includes unhideAreas; tile overrides which use
				// those areas therefore need the same volumes as the original path.
				if (json.has("unhideAreas"))
					for (JsonElement bounds : json.getAsJsonArray("unhideAreas"))
						area.aabbs.add(readIntArray(bounds.getAsJsonArray()));
				if (json.has("regions"))
					for (JsonElement regionElement : json.getAsJsonArray("regions"))
					{
						int region = regionElement.getAsInt();
						int minX = (region >>> 8) << 6;
						int minY = (region & 255) << 6;
						area.aabbs.add(new int[] { minX, minY, minX + 63, minY + 63 });
					}
				if (json.has("regionBoxes"))
					for (JsonElement boxElement : json.getAsJsonArray("regionBoxes"))
					{
						int[] box = readIntArray(boxElement.getAsJsonArray());
						if (box.length == 0)
							continue;
						int from = box[0];
						int to = box.length > 1 ? box[1] : from;
						int x1 = from >>> 8;
						int y1 = from & 255;
						int x2 = to >>> 8;
						int y2 = to & 255;
						int minX = Math.min(x1, x2) << 6;
						int minY = Math.min(y1, y2) << 6;
						int maxX = ((Math.max(x1, x2) + 1) << 6) - 1;
						int maxY = ((Math.max(y1, y2) + 1) << 6) - 1;
						if (box.length == 3)
							area.aabbs.add(new int[] { minX, minY, maxX, maxY, box[2] });
						else if (box.length >= 4)
							area.aabbs.add(new int[] {
								minX, minY, box[2], maxX, maxY, box[3] });
						else
							area.aabbs.add(new int[] { minX, minY, maxX, maxY });
					}
				if (json.has("areas"))
					for (JsonElement child : json.getAsJsonArray("areas"))
						area.areas.add(child.getAsString());
				result.put(area.name, area);
			}
			return result;
		}
	}

	private static int[] readIntArray(JsonArray array)
	{
		int[] result = new int[array.size()];
		for (int index = 0; index < result.length; index++)
			result[index] = array.get(index).getAsInt();
		return result;
	}

	private static int[] readIds(JsonObject json, String key)
	{
		if (!json.has(key))
			return null;
		JsonArray array = json.getAsJsonArray(key);
		int[] result = new int[array.size()];
		for (int i = 0; i < result.length; i++)
			result[i] = array.get(i).getAsInt();
		return result;
	}

	private static int readInt(JsonObject json, String key, int fallback)
	{
		return json.has(key) ? json.get(key).getAsInt() : fallback;
	}

	private static float readFloat(JsonObject json, String key, float fallback)
	{
		return json.has(key) ? json.get(key).getAsFloat() : fallback;
	}

	/** Mirrors ColorUtils.LinearAdapter used by the original material loader. */
	private static float srgbToLinear(float value)
	{
		return value <= .04045f
			? value / 12.92f
			: (float) Math.pow((value + .055f) / 1.055f, 2.4);
	}

	private static float[] readVector(JsonObject json, String key, boolean color)
	{
		float[] result = new float[3];
		JsonElement element = json.get(key);
		if (element == null)
			return result;
		if (color && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
		{
			String hex = element.getAsString();
			if (hex.startsWith("#") && hex.length() == 7)
			{
				int rgb = Integer.parseInt(hex.substring(1), 16);
				for (int index = 0; index < 3; index++)
				{
					float value = (rgb >> (16 - index * 8) & 0xff) / 255f;
					result[index] = value <= .04045f
						? value / 12.92f
						: (float) Math.pow((value + .055f) / 1.055f, 2.4);
				}
			}
			return result;
		}
		if (!element.isJsonArray())
			return result;
		JsonArray values = element.getAsJsonArray();
		for (int index = 0; index < Math.min(3, values.size()); index++)
		{
			float value = values.get(index).getAsFloat();
			if (color)
			{
				value = Math.max(0, Math.min(1, value / 255f));
				value = value <= .04045f
					? value / 12.92f
					: (float) Math.pow((value + .055f) / 1.055f, 2.4);
			}
			result[index] = value;
		}
		return result;
	}

	private String[] mapVanillaMaterials(int textureCount)
	{
		String[] mapping = new String[textureCount];
		for (MaterialDefinition definition : definitions)
			if (definition.vanillaTextureIndex >= 0 && definition.vanillaTextureIndex < textureCount)
				mapping[definition.vanillaTextureIndex] = definition.name;
		return mapping;
	}

	private String resolveReplacement(String original)
	{
		if (original == null)
			return null;
		String resolved = original;
		Set<String> visited = new HashSet<>();
		for (int pass = 0; pass < definitions.size() && visited.add(resolved); pass++)
		{
			String before = resolved;
			for (MaterialDefinition definition : definitions)
				if ("modelTextures".equals(definition.replacementCondition)
					&& definition.materialsToReplace.contains(resolved))
					resolved = definition.name;
			if (resolved.equals(before))
				break;
		}
		return resolved;
	}

	private int resolveVanillaTextureIndex(String materialName)
	{
		String current = materialName;
		Set<String> visited = new HashSet<>();
		while (current != null && visited.add(current))
		{
			MaterialDefinition definition = definitionsByName.get(current);
			if (definition == null)
				break;
			if (definition.vanillaTextureIndex >= 0)
				return definition.vanillaTextureIndex;
			current = definition.parent;
		}
		return -1;
	}

	private BufferedImage loadMaterialTexture(String materialName) throws IOException
	{
		String current = materialName;
		while (current != null)
		{
			BufferedImage image = loadImage(current.toLowerCase());
			if (image != null)
				return image;
			MaterialDefinition definition = definitionsByName.get(current);
			current = definition == null ? null : definition.parent;
		}
		return null;
	}

	private BufferedImage loadImage(String name) throws IOException
	{
		for (String extension : new String[] { ".png", ".jpg" })
			try (InputStream stream = WebGpu117ResourceLoader.class.getResourceAsStream(
				TEXTURE_ROOT + name + extension))
			{
				if (stream != null)
					return ImageIO.read(stream);
			}
		return null;
	}

	private MaterialProperties resolveMaterialProperties(String materialName)
	{
		MaterialProperties properties = new MaterialProperties();
		properties.water = materialName != null && materialName.startsWith("WATER_");
		List<MaterialDefinition> inheritance = new ArrayList<>();
		String current = materialName;
		for (int depth = 0; current != null && depth < 64; depth++)
		{
			MaterialDefinition definition = definitionsByName.get(current);
			if (definition == null)
				break;
			inheritance.add(definition);
			current = definition.parent;
		}
		for (int index = inheritance.size() - 1; index >= 0; index--)
		{
			MaterialDefinition definition = inheritance.get(index);
			if (definition.brightness != null)
				properties.brightness = definition.brightness;
			if (definition.specularStrength != null)
				properties.specularStrength = definition.specularStrength;
			if (definition.specularGloss != null)
				properties.roughness = Math.max(
					.04f, Math.min(1f, (float) Math.sqrt(2f / (definition.specularGloss + 2f))));
			if (definition.overrideBaseColor != null)
				properties.overrideBaseColor = definition.overrideBaseColor;
			if (definition.unlit != null)
				properties.unlit = definition.unlit;
			if (definition.hasTransparency != null)
				properties.hasTransparency = definition.hasTransparency;
			if (definition.normalMapSpecified)
				properties.normalMap = definition.normalMap;
		}
		return properties;
	}

	private static boolean hasSurfaceData(MaterialProperties properties)
	{
		return properties.normalMap != null
			|| properties.overrideBaseColor
			|| properties.unlit
			|| properties.hasTransparency
			|| Math.abs(properties.brightness - 1f) > 1e-6f
			|| Math.abs(properties.roughness - .82f) > 1e-6f
			|| Math.abs(properties.specularStrength) > 1e-6f;
	}

	private void writeMaterialData(
		int[] normalAtlas,
		float[] materialParameters,
		int atlasWidth,
		int textureId,
		int columns,
		int tileSize,
		String materialName,
		BufferedImage scaled,
		int[] row) throws IOException
	{
		MaterialProperties properties = resolveMaterialProperties(materialName);
		int parameterOffset = textureId * 4;
		materialParameters[parameterOffset] = properties.brightness;
		materialParameters[parameterOffset + 1] = properties.roughness;
		materialParameters[parameterOffset + 2] = properties.specularStrength;
		materialParameters[parameterOffset + 3] =
			(properties.overrideBaseColor ? 1 : 0) |
			(properties.unlit ? 2 : 0) |
			(properties.water ? 4 : 0) |
			(properties.hasTransparency ? 8 : 0);
		if (properties.normalMap == null)
			return;
		BufferedImage normalMap = loadMaterialTexture(properties.normalMap);
		if (normalMap != null)
			writeHdTexture(
				normalAtlas, atlasWidth, textureId, columns, tileSize, normalMap, scaled, row);
	}

	private void writeVanillaTexture(
		int[] atlas, int atlasWidth, int textureId, int columns, int tileSize, int[] source)
	{
		int sourceSize = (int) Math.sqrt(source.length);
		if (sourceSize * sourceSize != source.length)
			return;
		int originX = textureId % columns * tileSize;
		int originY = textureId / columns * tileSize;
		for (int y = 0; y < tileSize; y++)
		{
			int sourceY = y * sourceSize / tileSize;
			int destination = (originY + y) * atlasWidth + originX;
			for (int x = 0; x < tileSize; x++)
			{
				int rgb = source[sourceY * sourceSize + x * sourceSize / tileSize];
				atlas[destination + x] = packRgba(rgb == 0 ? 0 : 0xff000000 | rgb);
			}
		}
	}

	private void writeSolidTexture(
		int[] atlas, int atlasWidth, int textureId, int columns, int tileSize, int argb)
	{
		int originX = textureId % columns * tileSize;
		int originY = textureId / columns * tileSize;
		int packed = packRgba(argb);
		for (int y = 0; y < tileSize; y++)
			Arrays.fill(
				atlas, (originY + y) * atlasWidth + originX,
				(originY + y) * atlasWidth + originX + tileSize, packed);
	}

	private void writeHdTexture(
		int[] atlas,
		int atlasWidth,
		int textureId,
		int columns,
		int tileSize,
		BufferedImage source,
		BufferedImage scaled,
		int[] row)
	{
		Graphics2D graphics = scaled.createGraphics();
		try
		{
			graphics.setComposite(AlphaComposite.Clear);
			graphics.fillRect(0, 0, tileSize, tileSize);
			graphics.setComposite(AlphaComposite.Src);
			graphics.setRenderingHint(
				RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			// 117 flips non-vanilla textures horizontally to preserve vanilla UV orientation.
			graphics.drawImage(source, tileSize, 0, -tileSize, tileSize, null);
		}
		finally
		{
			graphics.dispose();
		}

		int originX = textureId % columns * tileSize;
		int originY = textureId / columns * tileSize;
		for (int y = 0; y < tileSize; y++)
		{
			scaled.getRGB(0, y, tileSize, 1, row, 0, tileSize);
			int destination = (originY + y) * atlasWidth + originX;
			for (int x = 0; x < tileSize; x++)
				atlas[destination + x] = packRgba(row[x]);
		}
	}

	private static int packRgba(int argb)
	{
		return argb & 0xff000000
			| (argb & 0xff) << 16
			| argb & 0xff00
			| (argb >> 16) & 0xff;
	}
}
