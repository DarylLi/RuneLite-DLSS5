package rs117.hd.webgpu;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.runelite.api.Constants;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import rs117.hd.scene.ProceduralGenerator;
import rs117.hd.utils.HDUtils;

/** CPU-side staging buffer for the first native WebGPU scene pass. */
public final class WebGpuSceneBuffer
{
	private static final int HIDDEN_HSL = 12345678;
	private static final int INITIAL_VERTEX_CAPACITY = 1 << 16;
	private static final int MAX_RTX_LIGHTS = 8;
	private static final int LIGHT_RECORD_FLOATS = 16;
	// The old preview limits were small enough that a busy world hit exactly
	// 90,000 dynamic vertices and silently dropped every actor/model after it.
	// Keep a generous corruption/OOM guard, but size it for the full visible
	// RuneLite scene instead of a prototype capture.
	// Keep both limits triangle-aligned. A non-multiple-of-three cap can accept
	// only part of a face and make the first dynamic vertex complete a corrupt
	// cross-boundary triangle, shifting every model after it.
	// A dense 117-HD scene at the 50-72 tile settings can exceed the original
	// 3.6M static budget before the first visible window has finished streaming.
	// Hitting that cap used to make the plugin clear the complete resident prefix
	// and start again, producing a deterministic load -> blank -> load loop.
	// Keep enough room for the complete near field plus a real actor/effect tail.
	private static final int MAX_STATIC_VERTICES = 7_199_997;
	private static final int MAX_DYNAMIC_VERTICES = 799_998;
	// A 72-tile square contains roughly 2.7x the area of the original 40-tile
	// preview.  Keep a real actor reserve even when the static prefix is full.
	// All three limits remain triangle-aligned so a capacity boundary can never
	// splice half a face into the dynamic suffix.
	private static final int MAX_TOTAL_VERTICES = 7_999_995;
	public static final int PACKED_VERTEX_BYTES = 36;
	private static final int SOURCE_TEXTURED_MODEL = 1;
	private static final int SOURCE_TERRAIN = 1 << 1;
	// Model-only. Terrain also uses bit 2, but the shader first checks that the
	// terrain bit is clear. The octahedral normal itself lives in bits 16..31.
	private static final int SOURCE_MODEL_SMOOTH_NORMAL = 1 << 2;
	private static final int SOURCE_TERRAIN_BLEND_CANDIDATE = 1 << 2;
	private static final int SOURCE_TERRAIN_PLANE_SHIFT = 3;
	private static final int SOURCE_TERRAIN_LOW_MASK = (1 << 5) - 1;
	// This bit is interpreted only when SOURCE_TERRAIN_BLEND_ACTIVE is clear.
	// Material C uses the same high bit range for blended land, while water is
	// deliberately excluded from terrain blending.
	private static final int SOURCE_TERRAIN_WATER = 1 << 30;
	// Terrain-only and mutually exclusive with blend-active material C. The
	// original 117 renderer submits a second opaque mesh below water surfaces;
	// this tag keeps that seabed out of water reflection classification.
	private static final int SOURCE_TERRAIN_UNDERWATER = 1 << 29;
	private static final int SOURCE_MODEL_TRANSPARENCY_SHIFT = 8;
	private static final int SOURCE_MODEL_NORMAL_X_SHIFT = 16;
	private static final int SOURCE_MODEL_NORMAL_Y_SHIFT = 24;
	private static final int TERRAIN_MATERIAL_BITS = 13;
	private static final int TERRAIN_MATERIAL_MASK = (1 << TERRAIN_MATERIAL_BITS) - 1;
	private static final int TERRAIN_MATERIAL_B_SHIFT = 5;
	private static final int TERRAIN_MATERIAL_C_SHIFT = 18;
	private static final int SOURCE_TERRAIN_BLEND_ACTIVE = 0x80000000;
	private static final int MAX_BLEND_MATERIAL_ID = TERRAIN_MATERIAL_MASK - 2;

	// Classifier-only instances and empty capture jobs never emit geometry.
	// Defer their 2.25 MiB direct allocation until a vertex is actually staged.
	private static final ByteBuffer EMPTY_VERTICES = allocateVertices(0);
	private ByteBuffer packedVertices = EMPTY_VERTICES;
	private int vertexCount;
	private int staticVertexCount;
	private long staticEpoch = 1;
	private long staticRevision;
	private final float[] lightRecords = new float[MAX_RTX_LIGHTS * LIGHT_RECORD_FLOATS];
	private final float[] lightDistances = new float[MAX_RTX_LIGHTS];
	private final float[] staticLightRecords = new float[MAX_RTX_LIGHTS * LIGHT_RECORD_FLOATS];
	private final float[] staticLightDistances = new float[MAX_RTX_LIGHTS];
	private int lightCount;
	private int staticLightCount;
	private int activeLightLimit = MAX_RTX_LIGHTS;
	private boolean buildingStaticGeometry = true;
	private boolean smoothTerrainMaterials = true;
	private WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver;
	private int[][][] underwaterDepthOffsets;
	private int underwaterSceneOffsetX;
	private int underwaterSceneOffsetZ;
	// SceneUploader's UV helper uses the 117 vertex layout (u, v, w, padding)
	// for each of the three face vertices, so this scratch buffer must contain
	// all twelve floats even though WebGPU currently consumes only u and v.
	private final float[] faceUvs = new float[12];
	// Actor models contain many deliberately flat-shaded faces whose client
	// vertex-normal entries are absent or unsuitable for interpolation. Reuse
	// these scratch arrays per submission so the model-smoothing control can
	// derive animation-safe normals from the actor's current (already animated)
	// vertices without allocating three large arrays for every actor, every frame.
	private float[] generatedNormalX = new float[0];
	private float[] generatedNormalY = new float[0];
	private float[] generatedNormalZ = new float[0];

	/**
	 * Read-only view of a finalized static prefix.  Static vertices are immutable
	 * after publication, so a compaction worker can retain this view while the
	 * render thread continues to append per-frame dynamic geometry after it.
	 */
	public static final class StaticGeometrySnapshot
	{
		private final ByteBuffer vertices;
		private final int vertexCount;

		private StaticGeometrySnapshot(ByteBuffer vertices, int vertexCount)
		{
			this.vertices = vertices;
			this.vertexCount = vertexCount;
		}

		public int vertexCount()
		{
			return vertexCount;
		}
	}

	/**
	 * Immutable, worker-safe copy of the RuneLite model data consumed by the
	 * WebGPU packer. RuneLite's Model and its backing arrays belong to the client
	 * thread; static-sector workers must never retain or dereference them.
	 */
	public static final class CapturedModel
	{
		private final float[] x;
		private final float[] y;
		private final float[] z;
		private final int[] faceA;
		private final int[] faceB;
		private final int[] faceC;
		private final int[] colorA;
		private final int[] colorB;
		private final int[] colorC;
		private final int[] normalX;
		private final int[] normalY;
		private final int[] normalZ;
		private final short[] faceTextures;
		private final byte[] faceTransparencies;
		private final byte modelTransparency;
		private final byte[] textureFaces;
		private final int[] textureA;
		private final int[] textureB;
		private final int[] textureC;
		private final int verticesCount;
		private final int faceCount;
		private final int overrideAmount;
		private final int overrideHue;
		private final int overrideSaturation;
		private final int overrideLuminance;

		private CapturedModel(Model model, boolean copy)
		{
			x = floats(model.getVerticesX(), copy);
			y = floats(model.getVerticesY(), copy);
			z = floats(model.getVerticesZ(), copy);
			faceA = ints(model.getFaceIndices1(), copy);
			faceB = ints(model.getFaceIndices2(), copy);
			faceC = ints(model.getFaceIndices3(), copy);
			colorA = ints(model.getFaceColors1(), copy);
			colorB = ints(model.getFaceColors2(), copy);
			colorC = ints(model.getFaceColors3(), copy);
			normalX = ints(model.getVertexNormalsX(), copy);
			normalY = ints(model.getVertexNormalsY(), copy);
			normalZ = ints(model.getVertexNormalsZ(), copy);
			faceTextures = shorts(model.getFaceTextures(), copy);
			faceTransparencies = bytes(model.getFaceTransparencies(), copy);
			modelTransparency = model.getTransparency();
			textureFaces = bytes(model.getTextureFaces(), copy);
			textureA = ints(model.getTexIndices1(), copy);
			textureB = ints(model.getTexIndices2(), copy);
			textureC = ints(model.getTexIndices3(), copy);
			verticesCount = model.getVerticesCount();
			faceCount = model.getFaceCount();
			overrideAmount = model.getOverrideAmount() & 0xff;
			overrideHue = model.getOverrideHue();
			overrideSaturation = model.getOverrideSaturation();
			overrideLuminance = model.getOverrideLuminance();
		}

		private static float[] floats(float[] values, boolean copy)
		{
			return copy && values != null ? Arrays.copyOf(values, values.length) : values;
		}

		private static int[] ints(int[] values, boolean copy)
		{
			return copy && values != null ? Arrays.copyOf(values, values.length) : values;
		}

		private static short[] shorts(short[] values, boolean copy)
		{
			return copy && values != null ? Arrays.copyOf(values, values.length) : values;
		}

		private static byte[] bytes(byte[] values, boolean copy)
		{
			return copy && values != null ? Arrays.copyOf(values, values.length) : values;
		}
	}

	/**
	 * Immutable, worker-safe paint tile. All RuneLite scene access needed to
	 * build it is completed by {@link #captureTilePaint} on the client thread.
	 */
	public static final class CapturedTilePaint
	{
		private final int renderPlane;
		private final int sourcePlane;
		private final int tileX;
		private final int tileZ;
		private final int worldX;
		private final int worldY;
		private final int worldPlane;
		private final int overlayId;
		private final int underlayId;
		private final float swHeight;
		private final float seHeight;
		private final float neHeight;
		private final float nwHeight;
		private final int textureId;
		private final int neColor;
		private final int nwColor;
		private final int seColor;
		private final int swColor;

		private CapturedTilePaint(
			CapturedTerrainAddress address,
			float swHeight, float seHeight, float neHeight, float nwHeight,
			int textureId, int neColor, int nwColor, int seColor, int swColor)
		{
			this.renderPlane = address.renderPlane;
			this.sourcePlane = address.sourcePlane;
			this.tileX = address.tileX;
			this.tileZ = address.tileZ;
			this.worldX = address.worldX;
			this.worldY = address.worldY;
			this.worldPlane = address.worldPlane;
			this.overlayId = address.overlayId;
			this.underlayId = address.underlayId;
			this.swHeight = swHeight;
			this.seHeight = seHeight;
			this.neHeight = neHeight;
			this.nwHeight = nwHeight;
			this.textureId = textureId;
			this.neColor = neColor;
			this.nwColor = nwColor;
			this.seColor = seColor;
			this.swColor = swColor;
		}
	}

	/**
	 * Immutable, worker-safe shaped tile. Every array is owned by this snapshot;
	 * overlay classification is also captured because it requires a Tile.
	 */
	public static final class CapturedTileModel
	{
		private final int renderPlane;
		private final int sourcePlane;
		private final int tileX;
		private final int tileZ;
		private final int worldX;
		private final int worldY;
		private final int worldPlane;
		private final int overlayId;
		private final int underlayId;
		private final int[] faceA;
		private final int[] faceB;
		private final int[] faceC;
		private final int[] x;
		private final int[] y;
		private final int[] z;
		private final int[] colorA;
		private final int[] colorB;
		private final int[] colorC;
		private final int[] faceTextures;
		private final boolean[] overlayFaces;
		private final int faceCount;
		private final int vertexCount;

		private CapturedTileModel(
			CapturedTerrainAddress address,
			int[] faceA, int[] faceB, int[] faceC,
			int[] x, int[] y, int[] z,
			int[] colorA, int[] colorB, int[] colorC,
			int[] faceTextures, boolean[] overlayFaces,
			int faceCount, int vertexCount)
		{
			this.renderPlane = address.renderPlane;
			this.sourcePlane = address.sourcePlane;
			this.tileX = address.tileX;
			this.tileZ = address.tileZ;
			this.worldX = address.worldX;
			this.worldY = address.worldY;
			this.worldPlane = address.worldPlane;
			this.overlayId = address.overlayId;
			this.underlayId = address.underlayId;
			this.faceA = faceA;
			this.faceB = faceB;
			this.faceC = faceC;
			this.x = x;
			this.y = y;
			this.z = z;
			this.colorA = colorA;
			this.colorB = colorB;
			this.colorC = colorC;
			this.faceTextures = faceTextures;
			this.overlayFaces = overlayFaces;
			this.faceCount = faceCount;
			this.vertexCount = vertexCount;
		}
	}

	private static final class CapturedTerrainAddress
	{
		private final int renderPlane;
		private final int sourcePlane;
		private final int tileX;
		private final int tileZ;
		private final int worldX;
		private final int worldY;
		private final int worldPlane;
		private final int overlayId;
		private final int underlayId;

		private CapturedTerrainAddress(
			int renderPlane, int sourcePlane, int tileX, int tileZ,
			int worldX, int worldY, int worldPlane,
			int overlayId, int underlayId)
		{
			this.renderPlane = renderPlane;
			this.sourcePlane = sourcePlane;
			this.tileX = tileX;
			this.tileZ = tileZ;
			this.worldX = worldX;
			this.worldY = worldY;
			this.worldPlane = worldPlane;
			this.overlayId = overlayId;
			this.underlayId = underlayId;
		}
	}

	/**
	 * Client-thread water classification state. Once tile capture is complete it
	 * contains only owned primitive arrays and can be finalized by a worker.
	 */
	public static final class UnderwaterTerrainCapture
	{
		private final boolean[][][] waterTiles;
		private final boolean[][][] occupiedTiles;
		private final int sceneOffsetX;
		private final int sceneOffsetZ;
		private final float sceneCenterX;
		private final float sceneCenterZ;
		private final float maximumDistance;

		private UnderwaterTerrainCapture(
			boolean[][][] waterTiles, boolean[][][] occupiedTiles,
			int sceneOffsetX, int sceneOffsetZ, float sceneCenterX, float sceneCenterZ,
			float maximumDistance)
		{
			this.waterTiles = waterTiles;
			this.occupiedTiles = occupiedTiles;
			this.sceneOffsetX = sceneOffsetX;
			this.sceneOffsetZ = sceneOffsetZ;
			this.sceneCenterX = sceneCenterX;
			this.sceneCenterZ = sceneCenterZ;
			this.maximumDistance = maximumDistance;
		}
	}

	/** Immutable worker-produced depth field shared by sector packers. */
	public static final class PreparedUnderwaterTerrain
	{
		private final int[][][] depthOffsets;
		private final int sceneOffsetX;
		private final int sceneOffsetZ;

		private PreparedUnderwaterTerrain(int[][][] depthOffsets, int sceneOffsetX, int sceneOffsetZ)
		{
			this.depthOffsets = depthOffsets;
			this.sceneOffsetX = sceneOffsetX;
			this.sceneOffsetZ = sceneOffsetZ;
		}
	}

	/** Must be called on RuneLite's client thread. */
	public static CapturedModel captureModel(Model model)
	{
		return model == null ? null : new CapturedModel(model, true);
	}

	/** Must be called on RuneLite's client thread. */
	public static CapturedTilePaint captureTilePaint(
		Scene scene, SceneTilePaint paint, int renderPlane, int sourcePlane,
		int tileX, int tileZ)
	{
		if (scene == null || paint == null)
			return null;
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2
			: 0;
		int heightX = tileX + offset;
		int heightZ = tileZ + offset;
		int[][][] heights = scene.getTileHeights();
		if (heights == null || renderPlane < 0 || renderPlane >= heights.length
			|| heightX < 0 || heightZ < 0
			|| heightX + 1 >= heights[renderPlane].length
			|| heightZ + 1 >= heights[renderPlane][heightX].length
			|| heightZ + 1 >= heights[renderPlane][heightX + 1].length)
			return null;

		CapturedTerrainAddress address = captureTerrainAddress(
			scene, renderPlane, sourcePlane, tileX, tileZ);
		return new CapturedTilePaint(
			address,
			heights[renderPlane][heightX][heightZ],
			heights[renderPlane][heightX + 1][heightZ],
			heights[renderPlane][heightX + 1][heightZ + 1],
			heights[renderPlane][heightX][heightZ + 1],
			paint.getTexture(), paint.getNeColor(), paint.getNwColor(),
			paint.getSeColor(), paint.getSwColor());
	}

	/** Must be called on RuneLite's client thread. */
	public static CapturedTileModel captureTileModel(
		Scene scene, Tile tile, SceneTileModel model,
		int renderPlane, int sourcePlane, int tileX, int tileZ)
	{
		if (scene == null || tile == null || model == null)
			return null;
		int[] sourceFaceA = model.getFaceX();
		int[] sourceFaceB = model.getFaceY();
		int[] sourceFaceC = model.getFaceZ();
		int[] sourceX = model.getVertexX();
		int[] sourceY = model.getVertexY();
		int[] sourceZ = model.getVertexZ();
		int[] sourceColorA = model.getTriangleColorA();
		int[] sourceColorB = model.getTriangleColorB();
		int[] sourceColorC = model.getTriangleColorC();
		if (sourceFaceA == null || sourceFaceB == null || sourceFaceC == null
			|| sourceX == null || sourceY == null || sourceZ == null
			|| sourceColorA == null || sourceColorB == null || sourceColorC == null)
			return null;

		int faceCount = Math.min(sourceFaceA.length,
			Math.min(sourceFaceB.length, Math.min(sourceFaceC.length,
			Math.min(sourceColorA.length, Math.min(sourceColorB.length, sourceColorC.length)))));
		int vertexCount = Math.min(sourceX.length, Math.min(sourceY.length, sourceZ.length));
		int[] sourceFaceTextures = model.getTriangleTextureId();
		int[] faceTextures = sourceFaceTextures == null ? null
			: Arrays.copyOf(sourceFaceTextures, sourceFaceTextures.length);
		boolean[] overlayFaces = new boolean[faceCount];
		for (int face = 0; face < faceCount; face++)
			overlayFaces[face] = ProceduralGenerator.isOverlayFace(tile, face);

		return new CapturedTileModel(
			captureTerrainAddress(scene, renderPlane, sourcePlane, tileX, tileZ),
			Arrays.copyOf(sourceFaceA, sourceFaceA.length),
			Arrays.copyOf(sourceFaceB, sourceFaceB.length),
			Arrays.copyOf(sourceFaceC, sourceFaceC.length),
			Arrays.copyOf(sourceX, sourceX.length),
			Arrays.copyOf(sourceY, sourceY.length),
			Arrays.copyOf(sourceZ, sourceZ.length),
			Arrays.copyOf(sourceColorA, sourceColorA.length),
			Arrays.copyOf(sourceColorB, sourceColorB.length),
			Arrays.copyOf(sourceColorC, sourceColorC.length),
			faceTextures, overlayFaces, faceCount, vertexCount);
	}

	private static CapturedTerrainAddress captureTerrainAddress(
		Scene scene, int renderPlane, int sourcePlane, int tileX, int tileZ)
	{
		int[] world = HDUtils.sceneToWorld(scene, tileX, tileZ, sourcePlane);
		int overlayId = -1;
		int underlayId = -1;
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2
			: 0;
		int x = tileX + offset;
		int z = tileZ + offset;
		short[][][] overlays = scene.getOverlayIds();
		short[][][] underlays = scene.getUnderlayIds();
		if (overlays != null && underlays != null
			&& renderPlane >= 0 && renderPlane < overlays.length
			&& renderPlane < underlays.length && x >= 0 && z >= 0
			&& x < overlays[renderPlane].length && x < underlays[renderPlane].length
			&& z < overlays[renderPlane][x].length
			&& z < underlays[renderPlane][x].length)
		{
			overlayId = overlays[renderPlane][x][z] & 0xffff;
			underlayId = underlays[renderPlane][x][z] & 0xffff;
		}
		return new CapturedTerrainAddress(
			renderPlane, sourcePlane, tileX, tileZ,
			world[0], world[1], world[2], overlayId, underlayId);
	}

	public void clear()
	{
		staticEpoch++;
		staticRevision = 0;
		vertexCount = 0;
		staticVertexCount = 0;
		lightCount = 0;
		staticLightCount = 0;
		underwaterDepthOffsets = null;
		underwaterSceneOffsetX = 0;
		underwaterSceneOffsetZ = 0;
		buildingStaticGeometry = true;
	}

	/** Starts a new frame while retaining the immutable static scene prefix. */
	public void resetToStaticGeometry()
	{
		vertexCount = staticVertexCount;
		lightCount = staticLightCount;
		System.arraycopy(staticLightRecords, 0, lightRecords, 0, staticLightRecords.length);
		System.arraycopy(staticLightDistances, 0, lightDistances, 0, staticLightDistances.length);
		buildingStaticGeometry = false;
	}

	/**
	 * Clears only the per-frame RTX light selection. Static geometry, its
	 * revision, and all temporal-rendering history remain untouched. This lets
	 * the plugin reselect the nearest authored lights as the camera moves.
	 */
	public void beginLiveLightSelection()
	{
		lightCount = 0;
		Arrays.fill(lightRecords, 0f);
		Arrays.fill(lightDistances, Float.POSITIVE_INFINITY);
	}

	public int vertexCount()
	{
		return vertexCount;
	}

	/**
	 * Rebases a worker-built static sector into the persistent world epoch before
	 * it is finalized. The source snapshots stay in the current RuneLite Scene's
	 * local coordinates; applying one uniform translation here avoids rewriting
	 * every captured model and terrain array on the client thread.
	 */
	public void translateStagedGeometry(float x, float y, float z)
	{
		if (!buildingStaticGeometry)
			throw new IllegalStateException("Only unfinished static geometry can be rebased");
		if (x == 0 && y == 0 && z == 0)
			return;
		for (int vertex = 0; vertex < vertexCount; vertex++)
		{
			int offset = vertex * PACKED_VERTEX_BYTES;
			packedVertices.putFloat(offset, packedVertices.getFloat(offset) + x);
			packedVertices.putFloat(offset + 4, packedVertices.getFloat(offset + 4) + y);
			packedVertices.putFloat(offset + 8, packedVertices.getFloat(offset + 8) + z);
		}
		for (int light = 0; light < lightCount; light++)
		{
			int offset = light * LIGHT_RECORD_FLOATS;
			lightRecords[offset] += x;
			lightRecords[offset + 1] += y;
			lightRecords[offset + 2] += z;
		}
	}

	public void markStaticGeometryComplete()
	{
		markStaticGeometryCompleteRetaining(vertexCount);
	}

	/** Rebases newly emitted callback vertices without touching the resident prefix. */
	public void translateVertexRange(int firstVertex, int count, float x, float y, float z)
	{
		if (firstVertex < staticVertexCount || count < 0 || firstVertex > vertexCount
			|| count > vertexCount - firstVertex)
			throw new IllegalArgumentException("Translation must stay inside appended geometry");
		if (x == 0 && y == 0 && z == 0)
			return;
		for (int vertex = firstVertex; vertex < firstVertex + count; vertex++)
		{
			int offset = vertex * PACKED_VERTEX_BYTES;
			packedVertices.putFloat(offset, packedVertices.getFloat(offset) + x);
			packedVertices.putFloat(offset + 4, packedVertices.getFloat(offset + 4) + y);
			packedVertices.putFloat(offset + 8, packedVertices.getFloat(offset + 8) + z);
		}
	}

	/**
	 * Finalizes an independently-built static sector after using a temporary
	 * terrain-only halo for material smoothing. The halo participates in
	 * {@link #smoothStaticTerrain()} but is discarded before the immutable
	 * vertex prefix is exposed to raster or RTX consumers.
	 */
	public void markStaticGeometryCompleteRetaining(int retainedVertexCount)
	{
		if (retainedVertexCount < 0 || retainedVertexCount > vertexCount
			|| retainedVertexCount % 3 != 0)
			throw new IllegalArgumentException("Retained static geometry must be a triangle-aligned prefix");
		if (smoothTerrainMaterials)
			smoothStaticTerrain();
		vertexCount = retainedVertexCount;
		staticVertexCount = vertexCount;
		staticLightCount = lightCount;
		System.arraycopy(lightRecords, 0, staticLightRecords, 0, lightRecords.length);
		System.arraycopy(lightDistances, 0, staticLightDistances, 0, lightDistances.length);
		staticRevision++;
		buildingStaticGeometry = false;
	}

	/**
	 * Appends one independently-built static sector without touching the existing
	 * prefix.  The native renderer uses the epoch encoded by
	 * {@link #staticRevision()} to upload only this new tail.  The source is
	 * triangle-aligned by construction; a full destination keeps a complete-face
	 * prefix rather than silently cutting a triangle in half.
	 */
	public int appendStaticGeometry(WebGpuSceneBuffer source)
	{
		if (source == null || source.staticVertexCount <= 0)
			return 0;
		if (vertexCount != staticVertexCount)
			throw new IllegalStateException("Static sectors must be committed before dynamic geometry");

		int available = MAX_STATIC_VERTICES - staticVertexCount;
		if (source.staticVertexCount > available)
			return 0;
		int appended = source.staticVertexCount;

		ensureCapacity(vertexCount + appended);
		int sourceBytes = appended * PACKED_VERTEX_BYTES;
		ByteBuffer sourceView = source.packedVertices.duplicate().order(ByteOrder.nativeOrder());
		sourceView.position(0);
		sourceView.limit(sourceBytes);
		ByteBuffer destination = packedVertices.duplicate().order(ByteOrder.nativeOrder());
		destination.position(vertexCount * PACKED_VERTEX_BYTES);
		destination.put(sourceView);
		vertexCount += appended;
		staticVertexCount = vertexCount;

		for (int index = 0; index < source.staticLightCount; index++)
			mergeStaticLight(source, index);
		staticLightCount = lightCount;
		System.arraycopy(lightRecords, 0, staticLightRecords, 0, lightRecords.length);
		System.arraycopy(lightDistances, 0, staticLightDistances, 0, lightDistances.length);
		staticRevision++;
		buildingStaticGeometry = false;
		return appended;
	}

	/**
	 * Captures the immutable static portion of the current backing store without
	 * copying its dynamic tail.  Callers must freeze static commits until their
	 * worker has finished consuming the snapshot.  The backing buffer itself is
	 * retained by the view, so atomically adopting a replacement does not
	 * invalidate an in-flight copy.
	 */
	public StaticGeometrySnapshot snapshotStaticGeometry()
	{
		ByteBuffer view = packedVertices.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		view.position(0);
		view.limit(staticVertexCount * PACKED_VERTEX_BYTES);
		return new StaticGeometrySnapshot(view.slice().order(ByteOrder.nativeOrder()),
			staticVertexCount);
	}

	/** Copies one triangle-aligned sector range from a frozen static snapshot. */
	public int appendStaticGeometryRange(
		StaticGeometrySnapshot source, int firstVertex, int rangeVertexCount)
	{
		if (source == null || rangeVertexCount <= 0)
			return 0;
		if (firstVertex < 0 || rangeVertexCount < 0
			|| firstVertex % 3 != 0 || rangeVertexCount % 3 != 0
			|| firstVertex > source.vertexCount
			|| rangeVertexCount > source.vertexCount - firstVertex)
			throw new IllegalArgumentException("Static range must be a triangle-aligned snapshot slice");
		if (vertexCount != staticVertexCount)
			throw new IllegalStateException("Static ranges must be copied before dynamic geometry");
		if (rangeVertexCount > MAX_STATIC_VERTICES - staticVertexCount)
			return 0;

		ensureCapacity(vertexCount + rangeVertexCount);
		int firstByte = firstVertex * PACKED_VERTEX_BYTES;
		int byteCount = rangeVertexCount * PACKED_VERTEX_BYTES;
		ByteBuffer sourceView = source.vertices.duplicate().order(ByteOrder.nativeOrder());
		sourceView.position(firstByte);
		sourceView.limit(firstByte + byteCount);
		ByteBuffer destination = packedVertices.duplicate().order(ByteOrder.nativeOrder());
		destination.position(vertexCount * PACKED_VERTEX_BYTES);
		destination.put(sourceView);
		vertexCount += rangeVertexCount;
		staticVertexCount = vertexCount;
		staticRevision++;
		buildingStaticGeometry = false;
		return rangeVertexCount;
	}

	/**
	 * Allocates the replacement on the worker, including its future dynamic tail,
	 * so the render thread does not perform a multi-hundred-megabyte growth copy
	 * on the first actor callback after compaction.
	 */
	public void reserveFullSceneCapacity()
	{
		if (vertexCount != 0 || staticVertexCount != 0)
			throw new IllegalStateException("Scene capacity must be reserved before geometry");
		ensureCapacity(MAX_TOTAL_VERTICES);
	}

	/**
	 * Atomically steals a worker-built static replacement.  Advancing the epoch
	 * forces native raster/RTX consumers to replace their old static scene rather
	 * than interpreting the compacted order as an append-only revision.
	 */
	public void adoptStaticGeometry(WebGpuSceneBuffer replacement)
	{
		if (replacement == null
			|| replacement.vertexCount != replacement.staticVertexCount)
			throw new IllegalArgumentException("Replacement must contain static geometry only");
		packedVertices = replacement.packedVertices;
		vertexCount = replacement.staticVertexCount;
		staticVertexCount = replacement.staticVertexCount;
		lightCount = 0;
		staticLightCount = 0;
		Arrays.fill(lightRecords, 0f);
		Arrays.fill(staticLightRecords, 0f);
		Arrays.fill(lightDistances, Float.POSITIVE_INFINITY);
		Arrays.fill(staticLightDistances, Float.POSITIVE_INFINITY);
		staticEpoch++;
		staticRevision = 1;
		buildingStaticGeometry = false;
	}

	/** Starts a hard epoch on fresh storage, leaving retained snapshots immutable. */
	public void clearWithFreshStorage()
	{
		packedVertices = EMPTY_VERTICES;
		clear();
	}

	public static int maxStaticVertices()
	{
		return MAX_STATIC_VERTICES;
	}

	private void mergeStaticLight(WebGpuSceneBuffer source, int sourceIndex)
	{
		float distance = source.staticLightDistances[sourceIndex];
		int slot;
		if (lightCount < activeLightLimit)
			slot = lightCount++;
		else
		{
			slot = 0;
			for (int index = 1; index < activeLightLimit; index++)
				if (lightDistances[index] > lightDistances[slot])
					slot = index;
			if (distance >= lightDistances[slot])
				return;
		}
		lightDistances[slot] = distance;
		System.arraycopy(
			source.staticLightRecords, sourceIndex * LIGHT_RECORD_FLOATS,
			lightRecords, slot * LIGHT_RECORD_FLOATS, LIGHT_RECORD_FLOATS);
	}

	public boolean hasStaticCapacity()
	{
		return vertexCount < MAX_STATIC_VERTICES;
	}

	public int staticVertexCount()
	{
		return staticVertexCount;
	}

	public long staticRevision()
	{
		return staticEpoch << 32 | staticRevision & 0xffffffffL;
	}

	public float[] lightRecords()
	{
		return lightRecords;
	}

	public int lightCount()
	{
		return lightCount;
	}

	/** Keeps the eight closest 117 lights, matching the native RTX light budget. */
	public void addPointLight(
		float x, float y, float z, float radius, float strength, float[] linearColor,
		float cameraX, float cameraY, float cameraZ)
	{
		if (radius <= 0 || strength <= 0 || linearColor == null || linearColor.length < 3)
			return;
		addPointLight(
			x, y, z, radius, strength,
			linearColor[0], linearColor[1], linearColor[2],
			cameraX, cameraY, cameraZ);
	}

	/** Allocation-free point-light submission for retained static candidates. */
	public void addPointLight(
		float x, float y, float z, float radius, float strength,
		float colorR, float colorG, float colorB,
		float cameraX, float cameraY, float cameraZ)
	{
		if (radius <= 0 || strength <= 0)
			return;
		float dx = x - cameraX;
		float dy = y - cameraY;
		float dz = z - cameraZ;
		float distance = dx * dx + dy * dy + dz * dz;
		int slot;
		if (lightCount < activeLightLimit)
		{
			slot = lightCount++;
		}
		else
		{
			slot = 0;
			for (int index = 1; index < activeLightLimit; index++)
				if (lightDistances[index] > lightDistances[slot])
					slot = index;
			if (distance >= lightDistances[slot])
				return;
		}

		lightDistances[slot] = distance;
		int offset = slot * LIGHT_RECORD_FLOATS;
		Arrays.fill(lightRecords, offset, offset + LIGHT_RECORD_FLOATS, 0f);
		lightRecords[offset] = x;
		lightRecords[offset + 1] = y;
		lightRecords[offset + 2] = z;
		lightRecords[offset + 3] = radius;
		lightRecords[offset + 7] = -1f;
		lightRecords[offset + 8] = Math.max(0, colorR);
		lightRecords[offset + 9] = Math.max(0, colorG);
		lightRecords[offset + 10] = Math.max(0, colorB);
		lightRecords[offset + 11] = strength;
	}

	/** Direct native-order storage; JNA passes this pointer without array marshalling. */
	public ByteBuffer packedVertices()
	{
		ByteBuffer view = packedVertices.duplicate().order(ByteOrder.nativeOrder());
		view.clear();
		return view;
	}

	public long dynamicFingerprint()
	{
		long hash = 0xcbf29ce484222325L;
		int start = staticVertexCount * PACKED_VERTEX_BYTES;
		int end = vertexCount * PACKED_VERTEX_BYTES;
		for (int offset = start; offset < end; offset++)
		{
			hash ^= packedVertices.get(offset) & 0xffL;
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	public void setGroundTextureResolver(
		WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver)
	{
		this.groundTextureResolver = groundTextureResolver;
	}

	public void setSmoothTerrainMaterials(boolean smoothTerrainMaterials)
	{
		this.smoothTerrainMaterials = smoothTerrainMaterials;
	}

	/**
	 * Builds 117-style shoreline-aware seabed offsets before terrain emission.
	 * This is render-only staging data and never changes RuneLite's scene or
	 * click geometry.
	 */
	public void prepareUnderwaterTerrain(
		Scene scene, float sceneCenterX, float sceneCenterZ, int drawDistance)
	{
		UnderwaterTerrainCapture capture = beginUnderwaterTerrainCapture(
			scene, sceneCenterX, sceneCenterZ, drawDistance);
		Tile[][][] tiles = scene.getExtendedTiles();
		Set<Tile> visitedTiles = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Tile[][] plane : tiles)
			for (Tile[] column : plane)
				for (Tile tile : column)
					captureUnderwaterTileChain(scene, tile, capture, visitedTiles);
		sharePreparedUnderwaterTerrain(finalizeUnderwaterTerrain(capture));
	}

	/** Starts a bounded client-thread water/occupancy capture. */
	public static UnderwaterTerrainCapture beginUnderwaterTerrainCapture(
		Scene scene, float sceneCenterX, float sceneCenterZ, int drawDistance)
	{
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2
			: 0;
		Tile[][][] tiles = scene.getExtendedTiles();
		int planeCount = tiles.length;
		int sizeX = 0;
		int sizeZ = 0;
		for (Tile[][] plane : tiles)
		{
			sizeX = Math.max(sizeX, plane.length);
			for (Tile[] column : plane)
				sizeZ = Math.max(sizeZ, column.length);
		}
		boolean[][][] waterTiles = new boolean[planeCount][sizeX][sizeZ];
		boolean[][][] occupiedTiles = new boolean[planeCount][sizeX][sizeZ];
		int paddedDistance = drawDistance
			+ ProceduralGenerator.DEPTH_LEVEL_SLOPE.length + 1;
		float maximumDistance = paddedDistance * Perspective.LOCAL_TILE_SIZE;
		return new UnderwaterTerrainCapture(
			waterTiles, occupiedTiles, sceneOffset, sceneOffset,
			sceneCenterX, sceneCenterZ, maximumDistance);
	}

	/** Must be called on RuneLite's client thread. */
	public void captureUnderwaterTileChain(
		Scene scene, Tile tile, UnderwaterTerrainCapture capture,
		Set<Tile> visitedTiles)
	{
		if (tile == null)
			return;
		for (Tile current = tile; current != null && visitedTiles.add(current);
			current = current.getBridge())
		{
			int tileX = current.getSceneLocation().getX();
			int tileZ = current.getSceneLocation().getY();
			int depthX = tileX + capture.sceneOffsetX;
			int depthZ = tileZ + capture.sceneOffsetZ;
			float tileCenterX = tileX * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			float tileCenterZ = tileZ * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			if (Math.abs(tileCenterX - capture.sceneCenterX) > capture.maximumDistance
				|| Math.abs(tileCenterZ - capture.sceneCenterZ) > capture.maximumDistance)
				continue;
			int renderPlane = current.getRenderLevel();
			if (renderPlane < 0 || renderPlane >= capture.waterTiles.length
				|| depthX < 0 || depthX >= capture.waterTiles[renderPlane].length
				|| depthZ < 0 || depthZ >= capture.waterTiles[renderPlane][depthX].length)
				continue;
			capture.occupiedTiles[renderPlane][depthX][depthZ] = true;
			capture.waterTiles[renderPlane][depthX][depthZ] |= tileContainsWater(
				scene, current, renderPlane, current.getPlane(), tileX, tileZ);
		}
	}

	/**
	 * Returns whether the already-captured primitive water mask contains water in
	 * one world-space 8x8 sector. This never touches RuneLite scene objects, so the
	 * scene capturer can safely publish a proven-dry sector before the global
	 * shoreline depth field has finished. Water sectors must still wait for
	 * {@link #finalizeUnderwaterTerrain(UnderwaterTerrainCapture)}.
	 */
	public static boolean capturedSectorContainsWater(
		UnderwaterTerrainCapture capture, int sceneBaseX, int sceneBaseY,
		int sectorX, int sectorZ, int sectorSize)
	{
		if (capture == null || sectorSize <= 0)
			return false;
		int startX = sectorX * sectorSize - sceneBaseX + capture.sceneOffsetX;
		int startZ = sectorZ * sectorSize - sceneBaseY + capture.sceneOffsetZ;
		for (boolean[][] plane : capture.waterTiles)
		{
			if (plane == null)
				continue;
			for (int offsetX = 0; offsetX < sectorSize; offsetX++)
			{
				int x = startX + offsetX;
				if (x < 0 || x >= plane.length || plane[x] == null)
					continue;
				for (int offsetZ = 0; offsetZ < sectorSize; offsetZ++)
				{
					int z = startZ + offsetZ;
					if (z >= 0 && z < plane[x].length && plane[x][z])
						return true;
				}
			}
		}
		return false;
	}

	/** Maximum shoreline influence, plus the tiles adjoining sector edge vertices. */
	public static int underwaterCapturePaddingTiles()
	{
		return ProceduralGenerator.DEPTH_LEVEL_SLOPE.length + 1;
	}

	/**
	 * Detaches a complete local shoreline stencil from the mutable scene capture.
	 * The depth algorithm has finite reach, so this small owned copy produces the
	 * same interior depths without waiting for distant sectors to be classified.
	 */
	public static UnderwaterTerrainCapture copyUnderwaterSectorCapture(
		UnderwaterTerrainCapture source, int localX, int localZ, int sectorSize)
	{
		int padding = underwaterCapturePaddingTiles();
		int planeCount = source.waterTiles.length;
		int sizeX = planeCount == 0 ? 0 : source.waterTiles[0].length;
		int sizeZ = sizeX == 0 ? 0 : source.waterTiles[0][0].length;
		int startX = Math.max(0, Math.min(sizeX, localX + source.sceneOffsetX - padding));
		int startZ = Math.max(0, Math.min(sizeZ, localZ + source.sceneOffsetZ - padding));
		int endX = Math.max(startX,
			Math.min(sizeX, localX + source.sceneOffsetX + sectorSize + padding));
		int endZ = Math.max(startZ,
			Math.min(sizeZ, localZ + source.sceneOffsetZ + sectorSize + padding));
		boolean[][][] water = new boolean[planeCount][endX - startX][endZ - startZ];
		boolean[][][] occupied = new boolean[planeCount][endX - startX][endZ - startZ];
		for (int plane = 0; plane < planeCount; plane++)
			for (int x = startX; x < endX; x++)
			{
				System.arraycopy(source.waterTiles[plane][x], startZ,
					water[plane][x - startX], 0, endZ - startZ);
				System.arraycopy(source.occupiedTiles[plane][x], startZ,
					occupied[plane][x - startX], 0, endZ - startZ);
			}
		return new UnderwaterTerrainCapture(water, occupied,
			source.sceneOffsetX - startX, source.sceneOffsetZ - startZ,
			source.sceneCenterX, source.sceneCenterZ, source.maximumDistance);
	}

	/** Pure primitive-array finalization; safe to run on a sector worker. */
	public static PreparedUnderwaterTerrain finalizeUnderwaterTerrain(
		UnderwaterTerrainCapture capture)
	{
		boolean[][][] waterTiles = capture.waterTiles;
		boolean[][][] occupiedTiles = capture.occupiedTiles;
		int planeCount = waterTiles.length;
		int sizeX = planeCount == 0 ? 0 : waterTiles[0].length;
		int sizeZ = sizeX == 0 ? 0 : waterTiles[0][0].length;
		int[][][] depthOffsets = new int[planeCount][sizeX + 1][sizeZ + 1];
		for (int plane = 0; plane < planeCount; plane++)
		{
			int[][] levels = new int[sizeX + 1][sizeZ + 1];
			boolean[][] touchesWater = new boolean[sizeX + 1][sizeZ + 1];
			for (int vertexX = 0; vertexX <= sizeX; vertexX++)
				for (int vertexZ = 0; vertexZ <= sizeZ; vertexZ++)
				{
					boolean anyWater = false;
					boolean allWater = true;
					boolean anyTile = false;
					for (int dx = -1; dx <= 0; dx++)
						for (int dz = -1; dz <= 0; dz++)
						{
							int tileX = vertexX + dx;
							int tileZ = vertexZ + dz;
							if (tileX < 0 || tileX >= sizeX || tileZ < 0 || tileZ >= sizeZ
								|| !occupiedTiles[plane][tileX][tileZ])
							{
								allWater = false;
								continue;
							}
							anyTile = true;
							if (waterTiles[plane][tileX][tileZ])
								anyWater = true;
							else
								allWater = false;
						}
					touchesWater[vertexX][vertexZ] = anyWater;
					if (anyTile && anyWater && allWater)
						levels[vertexX][vertexZ] = 1;
				}

			// Match 117's stepped distance-from-shore depth field. Shared shore
			// vertices remain almost flush and deeper interior vertices descend.
			for (int level = 1; level < ProceduralGenerator.DEPTH_LEVEL_SLOPE.length; level++)
				for (int x = 1; x < sizeX; x++)
					for (int z = 1; z < sizeZ; z++)
						if (levels[x][z] == level
							&& levels[x - 1][z] >= level && levels[x + 1][z] >= level
							&& levels[x][z - 1] >= level && levels[x][z + 1] >= level)
							levels[x][z]++;

			for (int x = 0; x <= sizeX; x++)
				for (int z = 0; z <= sizeZ; z++)
				{
					if (!touchesWater[x][z])
						continue;
					int level = levels[x][z];
					// A tiny offset at a shared shoreline prevents equal-depth
					// fighting while retaining the original shore seam.
					depthOffsets[plane][x][z] = level == 0 ? 2
						: Math.max(2, Math.round(
							ProceduralGenerator.DEPTH_LEVEL_SLOPE[level - 1] * .55f));
				}
		}
		return new PreparedUnderwaterTerrain(depthOffsets, capture.sceneOffsetX, capture.sceneOffsetZ);
	}

	/**
	 * Shares the fully-prepared, read-only seabed depth field with a background
	 * sector builder. Call this after {@link #clear()}; preparation is complete
	 * before worker publication, and terrain emission never mutates the array.
	 */
	public void sharePreparedUnderwaterTerrainFrom(WebGpuSceneBuffer prepared)
	{
		underwaterDepthOffsets = prepared == null ? null : prepared.underwaterDepthOffsets;
		underwaterSceneOffsetX = prepared == null ? 0 : prepared.underwaterSceneOffsetX;
		underwaterSceneOffsetZ = prepared == null ? 0 : prepared.underwaterSceneOffsetZ;
	}

	/** Shares a worker-finalized immutable depth field with a sector packer. */
	public void sharePreparedUnderwaterTerrain(PreparedUnderwaterTerrain prepared)
	{
		underwaterDepthOffsets = prepared == null ? null : prepared.depthOffsets;
		underwaterSceneOffsetX = prepared == null ? 0 : prepared.sceneOffsetX;
		underwaterSceneOffsetZ = prepared == null ? 0 : prepared.sceneOffsetZ;
	}

	public void addTilePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		addTilePaint(scene, paint, plane, plane, tileX, tileZ);
	}

	/**
	 * Adds a paint tile while keeping RuneLite's two plane identities separate.
	 * A bridge tile is moved to a lower scene plane, but its render level remains
	 * the plane containing its heights and overlay/underlay ids. The scene plane
	 * is still required for instance/world material coordinates.
	 */
	public void addTilePaint(
		Scene scene, SceneTilePaint paint, int renderPlane, int sourcePlane,
		int tileX, int tileZ)
	{
		CapturedTilePaint captured = captureTilePaint(
			scene, paint, renderPlane, sourcePlane, tileX, tileZ);
		if (captured != null)
			addTilePaint(captured);
	}

	/** Packs a client-thread capture without accessing RuneLite scene objects. */
	public void addTilePaint(CapturedTilePaint paint)
	{
		float x0 = paint.tileX * Perspective.LOCAL_TILE_SIZE;
		float z0 = paint.tileZ * Perspective.LOCAL_TILE_SIZE;
		float x1 = x0 + Perspective.LOCAL_TILE_SIZE;
		float z1 = z0 + Perspective.LOCAL_TILE_SIZE;
		float sw = paint.swHeight;
		float se = paint.seHeight;
		float ne = paint.neHeight;
		float nw = paint.nwHeight;

		int textureId = paint.textureId;
		int neColor = paint.neColor;
		int nwColor = paint.nwColor;
		int seColor = paint.seColor;
		int swColor = paint.swColor;
		int vanillaNeColor = neColor;
		int vanillaNwColor = nwColor;
		int vanillaSeColor = seColor;
		int vanillaSwColor = swColor;
		int originalTextureId = textureId;
		WebGpu117ResourceLoader.GroundTextureResolver.Surface surface =
			resolveGroundSurface(
				paint.overlayId, paint.underlayId,
				paint.worldX, paint.worldY, paint.worldPlane, swColor, null);
		boolean canOverride = originalTextureId == -1
			|| groundTextureResolver != null
			&& groundTextureResolver.isKnownVanillaTexture(originalTextureId)
			// Water uses the procedural sentinel even without a loaded texture.
			// Its matched tint must not depend on provider texture availability.
			|| isVanillaWaterTexture(originalTextureId) && surface != null && surface.water;
		WebGpu117ResourceLoader.GroundTextureResolver.Surface appliedSurface =
			canOverride ? surface : null;
		if (paint.neColor == HIDDEN_HSL
			&& !(canOverride && surface != null && surface.forced))
			return;
		if (canOverride && surface != null
			&& (originalTextureId == -1 || surface.matched))
		{
			// Keep an authored vanilla texture as the fallback cell. Replacing it
			// with a ground-only atlas cell makes the textures-off path sample the
			// neutral fallback and turns otherwise textured tiles white.
			if (originalTextureId == -1)
				textureId = surface.textureId;
			swColor = surface.packedHsl;
			neColor = surface.modifyColor(neColor);
			nwColor = surface.modifyColor(nwColor);
			seColor = surface.modifyColor(seColor);
		}
		boolean water = isVanillaWaterTexture(originalTextureId)
			|| canOverride && surface != null && surface.water;
		if (water)
			textureId = -2; // All 117 water types use the native water surface path.
		boolean blendCandidate = originalTextureId == -1 && !water
			&& surface != null && surface.blended;
		int sourceFlags = terrainSourceFlags(paint.renderPlane, blendCandidate, water);
		int neTexture = textureId;
		int nwTexture = textureId;
		int seTexture = textureId;
		int swTexture = textureId;
		if (blendCandidate)
		{
			swTexture = surface.textureIdAt(paint.worldX, paint.worldY, paint.worldPlane);
			seTexture = surface.textureIdAt(paint.worldX + 1, paint.worldY, paint.worldPlane);
			nwTexture = surface.textureIdAt(paint.worldX, paint.worldY + 1, paint.worldPlane);
			neTexture = surface.textureIdAt(paint.worldX + 1, paint.worldY + 1, paint.worldPlane);
		}
		float heightOffset = appliedSurface == null ? 0 : appliedSurface.heightOffset;
		float[][] uv = paintUvs(paint.worldX, paint.worldY, appliedSurface);
		if (water)
			addUnderwaterTilePaint(
				paint,
				x0, z0, x1, z1, sw, se, ne, nw, heightOffset);
		addVertex(x1, ne - heightOffset, z1, neColor, vanillaNeColor, uv[0][0], uv[0][1], neTexture, sourceFlags);
		addVertex(x0, nw - heightOffset, z1, nwColor, vanillaNwColor, uv[1][0], uv[1][1], nwTexture, sourceFlags);
		addVertex(x1, se - heightOffset, z0, seColor, vanillaSeColor, uv[2][0], uv[2][1], seTexture, sourceFlags);
		addVertex(x0, sw - heightOffset, z0, swColor, vanillaSwColor, uv[3][0], uv[3][1], swTexture, sourceFlags);
		addVertex(x1, se - heightOffset, z0, seColor, vanillaSeColor, uv[2][0], uv[2][1], seTexture, sourceFlags);
		addVertex(x0, nw - heightOffset, z1, nwColor, vanillaNwColor, uv[1][0], uv[1][1], nwTexture, sourceFlags);
	}

	private void addUnderwaterTilePaint(
		CapturedTilePaint paint,
		float x0, float z0, float x1, float z1,
		float sw, float se, float ne, float nw, float surfaceHeightOffset)
	{
		int textureId = groundTextureResolver == null ? -1
			: groundTextureResolver.resolveUnderwaterTextureId(
				paint.worldX, paint.worldY, paint.worldPlane);
		float[][] uv = paintUvs(paint.worldX, paint.worldY, null);
		int flags = terrainUnderwaterSourceFlags(paint.renderPlane);
		int color = HDUtils.UNDERWATER_HSL;
		float swDepth = underwaterDepthAt(paint.renderPlane, paint.tileX, paint.tileZ);
		float seDepth = underwaterDepthAt(paint.renderPlane, paint.tileX + 1, paint.tileZ);
		float nwDepth = underwaterDepthAt(paint.renderPlane, paint.tileX, paint.tileZ + 1);
		float neDepth = underwaterDepthAt(paint.renderPlane, paint.tileX + 1, paint.tileZ + 1);
		addVertex(x1, ne - surfaceHeightOffset + neDepth, z1,
			color, color, uv[0][0], uv[0][1], textureId, flags);
		addVertex(x0, nw - surfaceHeightOffset + nwDepth, z1,
			color, color, uv[1][0], uv[1][1], textureId, flags);
		addVertex(x1, se - surfaceHeightOffset + seDepth, z0,
			color, color, uv[2][0], uv[2][1], textureId, flags);
		addVertex(x0, sw - surfaceHeightOffset + swDepth, z0,
			color, color, uv[3][0], uv[3][1], textureId, flags);
		addVertex(x1, se - surfaceHeightOffset + seDepth, z0,
			color, color, uv[2][0], uv[2][1], textureId, flags);
		addVertex(x0, nw - surfaceHeightOffset + nwDepth, z1,
			color, color, uv[1][0], uv[1][1], textureId, flags);
	}

	public void addTileModel(
		Scene scene, Tile tile, SceneTileModel model, int plane, int tileX, int tileZ)
	{
		addTileModel(scene, tile, model, plane, plane, tileX, tileZ);
	}

	public void addTileModel(
		Scene scene, Tile tile, SceneTileModel model,
		int renderPlane, int sourcePlane, int tileX, int tileZ)
	{
		CapturedTileModel captured = captureTileModel(
			scene, tile, model, renderPlane, sourcePlane, tileX, tileZ);
		if (captured != null)
			addTileModel(captured);
	}

	/** Packs a client-thread capture without accessing RuneLite scene objects. */
	public void addTileModel(CapturedTileModel model)
	{
		int[] faceA = model.faceA;
		int[] faceB = model.faceB;
		int[] faceC = model.faceC;
		int[] x = model.x;
		int[] y = model.y;
		int[] z = model.z;
		int[] colorA = model.colorA;
		int[] colorB = model.colorB;
		int[] colorC = model.colorC;
		int[] faceTextures = model.faceTextures;

		for (int face = 0; face < model.faceCount; face++)
		{
			if (!hasVertexCapacity())
				break;
			int a = faceA[face];
			int b = faceB[face];
			int c = faceC[face];
			if (a < 0 || b < 0 || c < 0
				|| a >= model.vertexCount || b >= model.vertexCount || c >= model.vertexCount)
				continue;
			int firstColor = colorA[face];
			int secondColor = colorB[face];
			int thirdColor = colorC[face];
			int vanillaFirstColor = firstColor;
			int vanillaSecondColor = secondColor;
			int vanillaThirdColor = thirdColor;
			int textureId = faceTextures == null || face >= faceTextures.length
				? -1 : faceTextures[face];
			int originalTextureId = textureId;
			boolean overlayFace = model.overlayFaces[face];
			WebGpu117ResourceLoader.GroundTextureResolver.Surface surface =
				resolveGroundSurface(
					model.overlayId, model.underlayId,
					model.worldX, model.worldY, model.worldPlane,
					firstColor, overlayFace);
			boolean canOverride = originalTextureId == -1
				|| groundTextureResolver != null
				&& groundTextureResolver.isKnownVanillaTexture(originalTextureId)
				|| isVanillaWaterTexture(originalTextureId) && surface != null && surface.water;
			WebGpu117ResourceLoader.GroundTextureResolver.Surface appliedSurface =
				canOverride ? surface : null;
			if (colorA[face] == HIDDEN_HSL
				&& !(canOverride && surface != null && surface.forced))
				continue;
			if (canOverride && surface != null
				&& (originalTextureId == -1 || surface.matched))
			{
				if (originalTextureId == -1)
					textureId = surface.textureId;
				firstColor = surface.packedHsl;
				secondColor = surface.modifyColor(secondColor);
				thirdColor = surface.modifyColor(thirdColor);
			}
			boolean water = isVanillaWaterTexture(originalTextureId)
				|| canOverride && surface != null && surface.water;
			if (water)
				textureId = -2;
			boolean blendCandidate = originalTextureId == -1 && !water
				&& surface != null && surface.blended;
			int sourceFlags = terrainSourceFlags(model.renderPlane, blendCandidate, water);
			int textureA = textureId;
			int textureB = textureId;
			int textureC = textureId;
			if (blendCandidate)
			{
				textureA = surface.textureIdAt(
					model.worldX + (int) (x[a] / 128f - model.tileX),
					model.worldY + (int) (z[a] / 128f - model.tileZ), model.worldPlane);
				textureB = surface.textureIdAt(
					model.worldX + (int) (x[b] / 128f - model.tileX),
					model.worldY + (int) (z[b] / 128f - model.tileZ), model.worldPlane);
				textureC = surface.textureIdAt(
					model.worldX + (int) (x[c] / 128f - model.tileX),
					model.worldY + (int) (z[c] / 128f - model.tileZ), model.worldPlane);
			}
			float heightOffset = appliedSurface == null ? 0 : appliedSurface.heightOffset;
			float[] uvA = modelUv(
				model.worldX, model.worldY, model.tileX, model.tileZ,
				x[a], z[a], appliedSurface);
			float[] uvB = modelUv(
				model.worldX, model.worldY, model.tileX, model.tileZ,
				x[b], z[b], appliedSurface);
			float[] uvC = modelUv(
				model.worldX, model.worldY, model.tileX, model.tileZ,
				x[c], z[c], appliedSurface);
			if (water)
				addUnderwaterTileModelFace(
					model, a, b, c, heightOffset);
			addVertex(x[a], y[a] - heightOffset, z[a], firstColor, vanillaFirstColor,
				uvA[0], uvA[1], textureA, sourceFlags);
			addVertex(x[b], y[b] - heightOffset, z[b], secondColor, vanillaSecondColor,
				uvB[0], uvB[1], textureB, sourceFlags);
			addVertex(x[c], y[c] - heightOffset, z[c], thirdColor, vanillaThirdColor,
				uvC[0], uvC[1], textureC, sourceFlags);
		}
	}

	private void addUnderwaterTileModelFace(
		CapturedTileModel model, int a, int b, int c, float surfaceHeightOffset)
	{
		int textureId = groundTextureResolver == null ? -1
			: groundTextureResolver.resolveUnderwaterTextureId(
				model.worldX, model.worldY, model.worldPlane);
		int flags = terrainUnderwaterSourceFlags(model.renderPlane);
		int color = HDUtils.UNDERWATER_HSL;
		float[] uvA = modelUv(model.worldX, model.worldY, model.tileX, model.tileZ,
			model.x[a], model.z[a], null);
		float[] uvB = modelUv(model.worldX, model.worldY, model.tileX, model.tileZ,
			model.x[b], model.z[b], null);
		float[] uvC = modelUv(model.worldX, model.worldY, model.tileX, model.tileZ,
			model.x[c], model.z[c], null);
		addVertex(model.x[a], model.y[a] - surfaceHeightOffset
				+ underwaterDepthForVertex(model.renderPlane, model.x[a], model.z[a]), model.z[a],
			color, color, uvA[0], uvA[1], textureId, flags);
		addVertex(model.x[b], model.y[b] - surfaceHeightOffset
				+ underwaterDepthForVertex(model.renderPlane, model.x[b], model.z[b]), model.z[b],
			color, color, uvB[0], uvB[1], textureId, flags);
		addVertex(model.x[c], model.y[c] - surfaceHeightOffset
				+ underwaterDepthForVertex(model.renderPlane, model.x[c], model.z[c]), model.z[c],
			color, color, uvC[0], uvC[1], textureId, flags);
	}

	private static boolean isVanillaWaterTexture(int textureId)
	{
		return textureId == 1 || textureId == 24 || textureId == 25
			|| textureId == 208 || textureId >= 130 && textureId <= 189;
	}

	private boolean tileContainsWater(
		Scene scene, Tile tile, int renderPlane, int sourcePlane,
		int tileX, int tileZ)
	{
		CapturedTerrainAddress address = captureTerrainAddress(
			scene, renderPlane, sourcePlane, tileX, tileZ);
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null)
		{
			WebGpu117ResourceLoader.GroundTextureResolver.Surface surface =
				resolveGroundSurface(
					address.overlayId, address.underlayId,
					address.worldX, address.worldY, address.worldPlane,
					paint.getSwColor(), null);
			return isVanillaWaterTexture(paint.getTexture())
				|| surface != null && surface.water;
		}
		SceneTileModel model = tile.getSceneTileModel();
		if (model == null)
			return false;
		int[] colors = model.getTriangleColorA();
		int[] textures = model.getTriangleTextureId();
		for (int face = 0; face < model.getFaceX().length; face++)
		{
			int textureId = textures == null ? -1 : textures[face];
			WebGpu117ResourceLoader.GroundTextureResolver.Surface surface =
				resolveGroundSurface(
					address.overlayId, address.underlayId,
					address.worldX, address.worldY, address.worldPlane, colors[face],
					ProceduralGenerator.isOverlayFace(tile, face));
			if (isVanillaWaterTexture(textureId) || surface != null && surface.water)
				return true;
		}
		return false;
	}

	private WebGpu117ResourceLoader.GroundTextureResolver.Surface resolveGroundSurface(
		int overlayId, int underlayId, int worldX, int worldY, int worldPlane,
		int packedHsl, Boolean overlayFace)
	{
		if (groundTextureResolver == null || overlayId < 0 || underlayId < 0)
			return null;
		return overlayFace == null
			? groundTextureResolver.resolveSurface(
				overlayId, underlayId, worldX, worldY, worldPlane, packedHsl,
				smoothTerrainMaterials)
			: groundTextureResolver.resolveFaceSurface(
				overlayId, underlayId, worldX, worldY, worldPlane, packedHsl,
				overlayFace, smoothTerrainMaterials);
	}

	private float[][] paintUvs(
		int worldX, int worldY,
		WebGpu117ResourceLoader.GroundTextureResolver.Surface surface)
	{
		return new float[][] {
			terrainUv(worldX, worldY, surface),
			terrainUv(worldX - 1, worldY, surface),
			terrainUv(worldX, worldY - 1, surface),
			terrainUv(worldX - 1, worldY - 1, surface)
		};
	}

	private float[] modelUv(
		int worldX, int worldY, int tileX, int tileZ, int vertexX, int vertexZ,
		WebGpu117ResourceLoader.GroundTextureResolver.Surface surface)
	{
		float localX = vertexX / 128f - tileX;
		float localZ = vertexZ / 128f - tileZ;
		return terrainUv(worldX + localX - 1, worldY + localZ - 1, surface);
	}

	private static float[] terrainUv(
		float worldX, float worldY,
		WebGpu117ResourceLoader.GroundTextureResolver.Surface surface)
	{
		float scale = surface == null ? 1f : surface.uvScale;
		int orientation = surface == null ? 0 : surface.uvOrientation;
		float radians = (float) (-orientation * Math.PI / 1024.0);
		float cosine = (float) Math.cos(radians) * -scale;
		float sine = (float) Math.sin(radians) * -scale;
		return new float[] {
			worldX * cosine - worldY * sine,
			worldX * sine + worldY * cosine
		};
	}

	private static int terrainSourceFlags(int plane, boolean blendCandidate, boolean water)
	{
		return SOURCE_TERRAIN
			| ((plane & 3) << SOURCE_TERRAIN_PLANE_SHIFT)
			| (blendCandidate ? SOURCE_TERRAIN_BLEND_CANDIDATE : 0)
			| (water ? SOURCE_TERRAIN_WATER : 0);
	}

	private static int terrainUnderwaterSourceFlags(int plane)
	{
		return SOURCE_TERRAIN | SOURCE_TERRAIN_UNDERWATER
			| ((plane & 3) << SOURCE_TERRAIN_PLANE_SHIFT);
	}

	private float underwaterDepthAt(int plane, int x, int z)
	{
		x += underwaterSceneOffsetX;
		z += underwaterSceneOffsetZ;
		if (underwaterDepthOffsets == null || plane < 0
			|| plane >= underwaterDepthOffsets.length || x < 0 || z < 0
			|| x >= underwaterDepthOffsets[plane].length
			|| z >= underwaterDepthOffsets[plane][x].length)
			return Math.round(ProceduralGenerator.DEPTH_LEVEL_SLOPE[0] * .55f);
		return Math.max(2, underwaterDepthOffsets[plane][x][z]);
	}

	private float underwaterDepthForVertex(int plane, int localX, int localZ)
	{
		float gridX = localX / (float) Perspective.LOCAL_TILE_SIZE;
		float gridZ = localZ / (float) Perspective.LOCAL_TILE_SIZE;
		int x0 = (int) Math.floor(gridX);
		int z0 = (int) Math.floor(gridZ);
		float tx = gridX - x0;
		float tz = gridZ - z0;
		float south = underwaterDepthAt(plane, x0, z0) * (1f - tx)
			+ underwaterDepthAt(plane, x0 + 1, z0) * tx;
		float north = underwaterDepthAt(plane, x0, z0 + 1) * (1f - tx)
			+ underwaterDepthAt(plane, x0 + 1, z0 + 1) * tx;
		return south * (1f - tz) + north * tz;
	}

	/**
	 * Makes duplicated terrain vertices agree at tile boundaries, then stores the
	 * three selected ground materials for each triangle in sourceFlags. Objects
	 * never carry SOURCE_TERRAIN and are therefore deliberately excluded.
	 */
	private void smoothStaticTerrain()
	{
		if (vertexCount < 3)
			return;

		Map<TerrainVertexKey, TerrainBlend> blends = new HashMap<>(vertexCount / 3);
		for (int vertex = 0; vertex < vertexCount; vertex++)
		{
			int offset = vertex * PACKED_VERTEX_BYTES;
			int sourceFlags = packedVertices.getInt(offset + 28);
			if (!isTerrainBlendCandidate(sourceFlags))
				continue;
			int textureId = packedVertices.getInt(offset + 24);
			if (textureId == -2)
				continue; // Never smear the water material onto shoreline land.
			TerrainVertexKey key = terrainVertexKey(offset, sourceFlags);
			TerrainBlend blend = blends.computeIfAbsent(key, ignored -> new TerrainBlend());
			blend.add(
				packedVertices.getInt(offset + 12),
				packedVertices.getInt(offset + 32),
				textureId);
		}

		// Smooth both the 117-modified color and the retained vanilla color so
		// the checkbox behaves consistently with HD textures enabled or disabled.
		for (int vertex = 0; vertex < vertexCount; vertex++)
		{
			int offset = vertex * PACKED_VERTEX_BYTES;
			int sourceFlags = packedVertices.getInt(offset + 28);
			if (!isTerrainBlendCandidate(sourceFlags)
				|| packedVertices.getInt(offset + 24) == -2)
				continue;
			TerrainBlend blend = blends.get(terrainVertexKey(offset, sourceFlags));
			if (blend == null)
				continue;
			packedVertices.putInt(offset + 12, blend.hdColor());
			packedVertices.putInt(offset + 32, blend.vanillaColor());
		}

		// Each terrain triangle is emitted as three consecutive vertices. Keep
		// textureId flat and encode the other two ids in unused sourceFlags bits;
		// native WGSL reconstructs barycentric material weights from vertex_index.
		for (int first = 0; first + 2 < vertexCount; first += 3)
		{
			int firstOffset = first * PACKED_VERTEX_BYTES;
			int secondOffset = firstOffset + PACKED_VERTEX_BYTES;
			int thirdOffset = secondOffset + PACKED_VERTEX_BYTES;
			int firstFlags = packedVertices.getInt(firstOffset + 28);
			int secondFlags = packedVertices.getInt(secondOffset + 28);
			int thirdFlags = packedVertices.getInt(thirdOffset + 28);
			if (!isTerrainBlendCandidate(firstFlags)
				|| !isTerrainBlendCandidate(secondFlags)
				|| !isTerrainBlendCandidate(thirdFlags))
				continue;
			if (packedVertices.getInt(firstOffset + 24) == -2
				|| packedVertices.getInt(secondOffset + 24) == -2
				|| packedVertices.getInt(thirdOffset + 24) == -2)
				continue;

			TerrainBlend firstBlend = blends.get(terrainVertexKey(firstOffset, firstFlags));
			TerrainBlend secondBlend = blends.get(terrainVertexKey(secondOffset, secondFlags));
			TerrainBlend thirdBlend = blends.get(terrainVertexKey(thirdOffset, thirdFlags));
			if (firstBlend == null || secondBlend == null || thirdBlend == null)
				continue;
			int materialA = firstBlend.material();
			int materialB = secondBlend.material();
			int materialC = thirdBlend.material();
			if (materialA == materialB && materialA == materialC)
				continue;
			if (!canEncodeBlendMaterial(materialA)
				|| !canEncodeBlendMaterial(materialB)
				|| !canEncodeBlendMaterial(materialC))
				continue;

			int packedFlags = (firstFlags & SOURCE_TERRAIN_LOW_MASK)
				| SOURCE_TERRAIN_BLEND_ACTIVE
				| encodeBlendMaterial(materialB) << TERRAIN_MATERIAL_B_SHIFT
				| encodeBlendMaterial(materialC) << TERRAIN_MATERIAL_C_SHIFT;
			packedVertices.putInt(firstOffset + 24, materialA);
			packedVertices.putInt(secondOffset + 24, materialA);
			packedVertices.putInt(thirdOffset + 24, materialA);
			packedVertices.putInt(firstOffset + 28, packedFlags);
			packedVertices.putInt(secondOffset + 28, packedFlags);
			packedVertices.putInt(thirdOffset + 28, packedFlags);
		}
	}

	private static boolean isTerrainBlendCandidate(int sourceFlags)
	{
		// Bit 2 is intentionally shared by terrain blend candidates and authored
		// model normals.  Requiring SOURCE_TERRAIN as well keeps this CPU pass on
		// SceneTilePaint/SceneTileModel vertices only; otherwise enabling terrain
		// blending averages the colors of normal-mapped walls, trees and objects
		// that merely happen to carry the model-normal bit.
		return (sourceFlags & (SOURCE_TERRAIN | SOURCE_TERRAIN_BLEND_CANDIDATE))
			== (SOURCE_TERRAIN | SOURCE_TERRAIN_BLEND_CANDIDATE);
	}

	private TerrainVertexKey terrainVertexKey(int offset, int sourceFlags)
	{
		return new TerrainVertexKey(
			Float.floatToRawIntBits(packedVertices.getFloat(offset)),
			Float.floatToRawIntBits(packedVertices.getFloat(offset + 4)),
			Float.floatToRawIntBits(packedVertices.getFloat(offset + 8)),
			(sourceFlags >> SOURCE_TERRAIN_PLANE_SHIFT) & 3);
	}

	private static boolean canEncodeBlendMaterial(int materialId)
	{
		return materialId >= -2 && materialId <= MAX_BLEND_MATERIAL_ID;
	}

	private static int encodeBlendMaterial(int materialId)
	{
		return materialId + 2;
	}

	public int addModel(Model model, int orientation, int worldX, int worldY, int worldZ)
	{
		return addModel(new CapturedModel(model, false), orientation, worldX, worldY, worldZ, false);
	}

	/**
	 * Adds a model without changing its positions, topology or click geometry.
	 * Actor submissions opt into generated normals for client-flat faces because
	 * their animated models otherwise have no interpolable normal stream for the
	 * model-smoothing slider to affect. Static rocks/objects retain the authored
	 * normal path exactly.
	 */
	public int addModel(
		Model model, int orientation, int worldX, int worldY, int worldZ,
		boolean generateActorNormals)
	{
		return addModel(
			new CapturedModel(model, false), orientation, worldX, worldY, worldZ,
			generateActorNormals);
	}

	/** Packs an owned static-model snapshot on a sector worker. */
	public int addModel(
		CapturedModel model, int orientation, int worldX, int worldY, int worldZ)
	{
		return addModel(model, orientation, worldX, worldY, worldZ, false);
	}

	private int addModel(
		CapturedModel model, int orientation, int worldX, int worldY, int worldZ,
		boolean generateActorNormals)
	{
		int firstOutputVertex = vertexCount;
		float[] x = model.x;
		float[] y = model.y;
		float[] z = model.z;
		int[] faceA = model.faceA;
		int[] faceB = model.faceB;
		int[] faceC = model.faceC;
		int[] colorA = model.colorA;
		int[] colorB = model.colorB;
		int[] colorC = model.colorC;
		int[] normalX = model.normalX;
		int[] normalY = model.normalY;
		int[] normalZ = model.normalZ;
		short[] faceTextures = model.faceTextures;
		byte[] faceTransparencies = model.faceTransparencies;
		byte modelTransparency = model.modelTransparency;
		byte[] textureFaces = model.textureFaces;
		int[] textureA = model.textureA;
		int[] textureB = model.textureB;
		int[] textureC = model.textureC;
		float sine = orientation == 0 ? 0 : Perspective.SINE[orientation] / 65536f;
		float cosine = orientation == 0 ? 1 : Perspective.COSINE[orientation] / 65536f;
		if (generateActorNormals)
			generateModelNormals(
				x, y, z, faceA, faceB, faceC, colorC,
				modelTransparency, faceTransparencies, model.verticesCount);

		for (int face = 0; face < model.faceCount; face++)
		{
			if (!hasVertexCapacity())
				break;
			int firstColor = colorA[face];
			int thirdColor = colorC[face];
			boolean clientFlatShadedFace = thirdColor == -1;
			if (thirdColor == -2)
			{
				continue;
			}
			int secondColor = colorB[face];
			if (thirdColor == -1)
			{
				secondColor = thirdColor = firstColor;
			}
			int textureId = faceTextures == null ? -1 : faceTextures[face];
			int sourceFlags = textureId == -1 ? 0 : SOURCE_TEXTURED_MODEL;
			// RuneLite stores transparency as 0=opaque and 255=invisible.  The
			// original 117 renderer carries this per face; dropping it turned
			// fishing-spot/effect billboards into black quads or removed their
			// visible detail entirely.
			int transparency = combinedTransparency(modelTransparency, faceTransparencies, face);
			// The client reserves effective alpha values 253-255 for helper and
			// clickbox-only faces. SceneUploader maps 253/254 to 255 and omits the
			// triangle entirely; retaining those faces here made invisible helpers
			// participate in both raster depth and the opaque RTX acceleration
			// structure. Keep every legitimate partially-transparent face below the
			// reserved range, including fishing splashes and spell/effect billboards.
			if (transparency >= 253)
				continue;
			if (transparency != 0)
				sourceFlags |= transparency << SOURCE_MODEL_TRANSPARENCY_SHIFT;
			int firstSourceFlags = sourceFlags;
			int secondSourceFlags = sourceFlags;
			int thirdSourceFlags = sourceFlags;
			int firstVertex = faceA[face];
			int secondVertex = faceB[face];
			int thirdVertex = faceC[face];
			// RuneLite's NORMALS draw flag supplies animation-safe authored normals
			// for smooth faces. Person models also contain a large number of client-
			// flat faces (face color C == -1); those intentionally do not provide a
			// useful interpolated stream, which previously made the strength slider
			// appear to do nothing on players while it worked on smooth rocks. For an
			// Actor only, use adjacency normals derived above from the current animated
			// vertices for those faces (and as a fallback for missing entries). This is
			// shading metadata only: submitted positions and clickboxes are untouched.
			boolean authoredFaceNormals =
				hasModelNormal(normalX, normalY, normalZ, firstVertex)
				&& hasModelNormal(normalX, normalY, normalZ, secondVertex)
				&& hasModelNormal(normalX, normalY, normalZ, thirdVertex);
			boolean useGeneratedNormals = generateActorNormals
				&& (clientFlatShadedFace || !authoredFaceNormals);
			if (hasSubmissionNormal(normalX, normalY, normalZ, firstVertex, useGeneratedNormals)
				&& hasSubmissionNormal(normalX, normalY, normalZ, secondVertex, useGeneratedNormals)
				&& hasSubmissionNormal(normalX, normalY, normalZ, thirdVertex, useGeneratedNormals))
			{
				firstSourceFlags = packModelNormal(
					sourceFlags,
					modelNormalX(normalX, firstVertex, useGeneratedNormals),
					modelNormalY(normalY, firstVertex, useGeneratedNormals),
					modelNormalZ(normalZ, firstVertex, useGeneratedNormals), sine, cosine);
				secondSourceFlags = packModelNormal(
					sourceFlags,
					modelNormalX(normalX, secondVertex, useGeneratedNormals),
					modelNormalY(normalY, secondVertex, useGeneratedNormals),
					modelNormalZ(normalZ, secondVertex, useGeneratedNormals), sine, cosine);
				thirdSourceFlags = packModelNormal(
					sourceFlags,
					modelNormalX(normalX, thirdVertex, useGeneratedNormals),
					modelNormalY(normalY, thirdVertex, useGeneratedNormals),
					modelNormalZ(normalZ, thirdVertex, useGeneratedNormals), sine, cosine);
			}
			if (textureId == -1)
			{
				int amount = model.overrideAmount;
				if (amount > 0)
				{
					firstColor = applyModelOverride(firstColor, amount, model);
					secondColor = applyModelOverride(secondColor, amount, model);
					thirdColor = applyModelOverride(thirdColor, amount, model);
				}
			}
			int textureFace = textureFaces == null ? -1 : textureFaces[face] & 0xff;
			boolean hasVanillaUvs = textureId != -1 && textureFace != 0xff
				&& textureA != null && textureB != null && textureC != null
				&& textureFace < textureA.length && textureFace < textureB.length && textureFace < textureC.length;
			if (hasVanillaUvs)
			{
				computeFaceUvsInline(
					faceUvs, model, textureFace, faceA[face], faceB[face], faceC[face]);
				hasVanillaUvs = validFaceUvs(faceUvs);
			}
			// Some cache models carry a valid face texture but omit (or contain a
			// degenerate) texture-triangle mapping.  Projecting every such face on
			// X/Z made vertical walls and steep roof sections sample one narrow
			// transparent strip of the atlas, which looked like whole pieces of the
			// building had vanished.  Use a dominant-axis face projection as the
			// geometry-UV fallback so all three orientations remain well-defined.
			boolean hasTextureUvs = textureId != -1;
			if (hasTextureUvs && !hasVanillaUvs)
				fillPlanarFaceUvs(faceUvs, x, y, z, firstVertex, secondVertex, thirdVertex);

			addModelVertex(x, y, z, firstVertex, orientation, sine, cosine,
				worldX, worldY, worldZ, firstColor,
				hasTextureUvs ? faceUvs[0] : x[firstVertex] / 128f,
				hasTextureUvs ? faceUvs[1] : z[firstVertex] / 128f, textureId, firstSourceFlags);
			addModelVertex(x, y, z, secondVertex, orientation, sine, cosine,
				worldX, worldY, worldZ, secondColor,
				hasTextureUvs ? faceUvs[4] : x[secondVertex] / 128f,
				hasTextureUvs ? faceUvs[5] : z[secondVertex] / 128f, textureId, secondSourceFlags);
			addModelVertex(x, y, z, thirdVertex, orientation, sine, cosine,
				worldX, worldY, worldZ, thirdColor,
				hasTextureUvs ? faceUvs[8] : x[thirdVertex] / 128f,
				hasTextureUvs ? faceUvs[9] : z[thirdVertex] / 128f, textureId, thirdSourceFlags);
		}
		return vertexCount - firstOutputVertex;
	}

	private static boolean validFaceUvs(float[] uv)
	{
		if (!(Float.isFinite(uv[0]) && Float.isFinite(uv[1])
			&& Float.isFinite(uv[4]) && Float.isFinite(uv[5])
			&& Float.isFinite(uv[8]) && Float.isFinite(uv[9])))
			return false;
		float firstU = uv[4] - uv[0];
		float firstV = uv[5] - uv[1];
		float secondU = uv[8] - uv[0];
		float secondV = uv[9] - uv[1];
		return Math.abs(firstU * secondV - firstV * secondU) > 1e-8f;
	}

	/** Pure-array equivalent of RuneLite's model UV projection. */
	private static void computeFaceUvsInline(
		float[] out, CapturedModel model, int textureFace,
		int triangleA, int triangleB, int triangleC)
	{
		float[] vx = model.x;
		float[] vy = model.y;
		float[] vz = model.z;
		int firstTextureVertex = model.textureA[textureFace];
		float v1x = vx[firstTextureVertex];
		float v1y = vy[firstTextureVertex];
		float v1z = vz[firstTextureVertex];

		int secondTextureVertex = model.textureB[textureFace];
		float v2x = vx[secondTextureVertex] - v1x;
		float v2y = vy[secondTextureVertex] - v1y;
		float v2z = vz[secondTextureVertex] - v1z;
		int thirdTextureVertex = model.textureC[textureFace];
		float v3x = vx[thirdTextureVertex] - v1x;
		float v3y = vy[thirdTextureVertex] - v1y;
		float v3z = vz[thirdTextureVertex] - v1z;

		float px = v2y * v3z - v2z * v3y;
		float py = v2z * v3x - v2x * v3z;
		float pz = v2x * v3y - v2y * v3x;
		float tx = v3y * pz - v3z * py;
		float ty = v3z * px - v3x * pz;
		float tz = v3x * py - v3y * px;
		float inverse = 1f / (tx * v2x + ty * v2y + tz * v2z);
		writeProjectedUv(out, 0, vx, vy, vz, triangleA, v1x, v1y, v1z,
			tx, ty, tz, inverse);
		writeProjectedUv(out, 4, vx, vy, vz, triangleB, v1x, v1y, v1z,
			tx, ty, tz, inverse);
		writeProjectedUv(out, 8, vx, vy, vz, triangleC, v1x, v1y, v1z,
			tx, ty, tz, inverse);

		tx = v2y * pz - v2z * py;
		ty = v2z * px - v2x * pz;
		tz = v2x * py - v2y * px;
		inverse = 1f / (tx * v3x + ty * v3y + tz * v3z);
		writeProjectedV(out, 1, vx, vy, vz, triangleA, v1x, v1y, v1z,
			tx, ty, tz, inverse);
		writeProjectedV(out, 5, vx, vy, vz, triangleB, v1x, v1y, v1z,
			tx, ty, tz, inverse);
		writeProjectedV(out, 9, vx, vy, vz, triangleC, v1x, v1y, v1z,
			tx, ty, tz, inverse);
		out[2] = out[6] = out[10] = 0f;
	}

	private static void writeProjectedUv(
		float[] out, int output, float[] x, float[] y, float[] z, int vertex,
		float originX, float originY, float originZ,
		float axisX, float axisY, float axisZ, float inverse)
	{
		out[output] = (axisX * (x[vertex] - originX)
			+ axisY * (y[vertex] - originY)
			+ axisZ * (z[vertex] - originZ)) * inverse;
	}

	private static void writeProjectedV(
		float[] out, int output, float[] x, float[] y, float[] z, int vertex,
		float originX, float originY, float originZ,
		float axisX, float axisY, float axisZ, float inverse)
	{
		writeProjectedUv(out, output, x, y, z, vertex,
			originX, originY, originZ, axisX, axisY, axisZ, inverse);
	}

	private static void fillPlanarFaceUvs(
		float[] out, float[] x, float[] y, float[] z, int a, int b, int c)
	{
		float edge1X = x[b] - x[a];
		float edge1Y = y[b] - y[a];
		float edge1Z = z[b] - z[a];
		float edge2X = x[c] - x[a];
		float edge2Y = y[c] - y[a];
		float edge2Z = z[c] - z[a];
		float normalX = Math.abs(edge1Y * edge2Z - edge1Z * edge2Y);
		float normalY = Math.abs(edge1Z * edge2X - edge1X * edge2Z);
		float normalZ = Math.abs(edge1X * edge2Y - edge1Y * edge2X);

		int projection = normalX >= normalY && normalX >= normalZ ? 0
			: normalZ >= normalY ? 1 : 2;
		writePlanarUv(out, 0, x, y, z, a, projection);
		writePlanarUv(out, 4, x, y, z, b, projection);
		writePlanarUv(out, 8, x, y, z, c, projection);
	}

	private static void writePlanarUv(
		float[] out, int output, float[] x, float[] y, float[] z,
		int vertex, int projection)
	{
		out[output] = (projection == 0 ? z[vertex] : x[vertex]) / 128f;
		out[output + 1] = (projection == 2 ? z[vertex] : y[vertex]) / 128f;
		out[output + 2] = 0;
		out[output + 3] = 0;
	}

	/**
	 * Requests a native temporal-history reset without changing the scene ABI.
	 *
	 * The native bridge already treats a static revision change as a hard
	 * history boundary. Dynamic models currently have no object-ID stream, so
	 * Java uses this only when the same-size dynamic suffix changes submission
	 * identity/order and ordinal motion would otherwise pair unrelated actors.
	 */
	public void requestTemporalReset()
	{
		staticRevision++;
	}

	private static boolean hasModelNormal(int[] normalX, int[] normalY, int[] normalZ, int vertex)
	{
		return normalX != null && normalY != null && normalZ != null
			&& vertex >= 0 && vertex < normalX.length && vertex < normalY.length && vertex < normalZ.length
			&& (normalX[vertex] != 0 || normalY[vertex] != 0 || normalZ[vertex] != 0);
	}

	private boolean hasSubmissionNormal(
		int[] normalX, int[] normalY, int[] normalZ, int vertex,
		boolean preferGenerated)
	{
		return preferGenerated && hasGeneratedNormal(vertex)
			|| hasModelNormal(normalX, normalY, normalZ, vertex);
	}

	private float modelNormalX(int[] authored, int vertex, boolean preferGenerated)
	{
		return preferGenerated && hasGeneratedNormal(vertex)
			? generatedNormalX[vertex] : authored[vertex];
	}

	private float modelNormalY(int[] authored, int vertex, boolean preferGenerated)
	{
		return preferGenerated && hasGeneratedNormal(vertex)
			? generatedNormalY[vertex] : authored[vertex];
	}

	private float modelNormalZ(int[] authored, int vertex, boolean preferGenerated)
	{
		return preferGenerated && hasGeneratedNormal(vertex)
			? generatedNormalZ[vertex] : authored[vertex];
	}

	private boolean hasGeneratedNormal(int vertex)
	{
		return vertex >= 0 && vertex < generatedNormalX.length
			&& (Math.abs(generatedNormalX[vertex])
				+ Math.abs(generatedNormalY[vertex])
				+ Math.abs(generatedNormalZ[vertex])) > 1e-6f;
	}

	/**
	 * Builds equal-face-weighted vertex normals from the actor's current vertex
	 * positions. The model supplied to the draw callback has already had its
	 * animation applied, so these normals follow limbs and equipment without
	 * requiring skinning data in the native ABI.
	 */
	private void generateModelNormals(
		float[] x, float[] y, float[] z,
		int[] faceA, int[] faceB, int[] faceC, int[] colorC,
		byte modelTransparency, byte[] faceTransparencies, int verticesCount)
	{
		int vertexLimit = Math.min(verticesCount,
			Math.min(x.length, Math.min(y.length, z.length)));
		ensureGeneratedNormalCapacity(vertexLimit);
		Arrays.fill(generatedNormalX, 0, vertexLimit, 0f);
		Arrays.fill(generatedNormalY, 0, vertexLimit, 0f);
		Arrays.fill(generatedNormalZ, 0, vertexLimit, 0f);

		int faceLimit = Math.min(colorC.length,
			Math.min(faceA.length, Math.min(faceB.length, faceC.length)));
		for (int face = 0; face < faceLimit; face++)
		{
			if (colorC[face] == -2
				|| combinedTransparency(modelTransparency, faceTransparencies, face) >= 253)
				continue;
			int a = faceA[face];
			int b = faceB[face];
			int c = faceC[face];
			if (a < 0 || b < 0 || c < 0
				|| a >= vertexLimit || b >= vertexLimit || c >= vertexLimit)
				continue;

			float edge1X = x[b] - x[a];
			float edge1Y = y[b] - y[a];
			float edge1Z = z[b] - z[a];
			float edge2X = x[c] - x[a];
			float edge2Y = y[c] - y[a];
			float edge2Z = z[c] - z[a];
			float nx = edge1Y * edge2Z - edge1Z * edge2Y;
			float ny = edge1Z * edge2X - edge1X * edge2Z;
			float nz = edge1X * edge2Y - edge1Y * edge2X;
			float lengthSquared = nx * nx + ny * ny + nz * nz;
			if (!(lengthSquared > 1e-10f) || !Float.isFinite(lengthSquared))
				continue;
			float inverseLength = 1.0f / (float) Math.sqrt(lengthSquared);
			nx *= inverseLength;
			ny *= inverseLength;
			nz *= inverseLength;
			accumulateGeneratedNormal(a, nx, ny, nz);
			accumulateGeneratedNormal(b, nx, ny, nz);
			accumulateGeneratedNormal(c, nx, ny, nz);
		}
	}

	private void ensureGeneratedNormalCapacity(int verticesCount)
	{
		if (generatedNormalX.length >= verticesCount)
			return;
		generatedNormalX = new float[verticesCount];
		generatedNormalY = new float[verticesCount];
		generatedNormalZ = new float[verticesCount];
	}

	private void accumulateGeneratedNormal(int vertex, float x, float y, float z)
	{
		generatedNormalX[vertex] += x;
		generatedNormalY[vertex] += y;
		generatedNormalZ[vertex] += z;
	}

	private static int combinedTransparency(byte modelTransparency, byte[] transparencies, int face)
	{
		if (modelTransparency == -1)
			return 255;
		int transparency = transparencies == null ? 0 : transparencies[face] & 0xff;
		// 253/254 are special clickbox/helper faces in the client, not visible
		// geometry. Treat them as fully transparent exactly as 117 does.
		if (transparency >= 253)
			return 255;
		if (modelTransparency != 0)
		{
			int modelAlpha = modelTransparency & 0xff;
			transparency += (253 - transparency) * modelAlpha >> 8;
		}
		return transparency;
	}

	/** Packs a world-oriented unit normal into two octahedral UNORM8 channels. */
	private static int packModelNormal(
		int sourceFlags, float inputX, float inputY, float inputZ, float sine, float cosine)
	{
		float rotatedX = inputZ * sine + inputX * cosine;
		float rotatedZ = inputZ * cosine - inputX * sine;
		// RuneLite uses -Y as up. The WebGPU scene pass uses +Y as up, so
		// reverse the full authored normal rather than special-casing slopes.
		float nx = -rotatedX;
		float ny = -inputY;
		float nz = -rotatedZ;
		float inverseL1 = 1.0f / (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
		float octX = nx * inverseL1;
		float octY = ny * inverseL1;
		if (nz < 0.0f)
		{
			float originalX = octX;
			octX = (1.0f - Math.abs(octY)) * signNotZero(originalX);
			octY = (1.0f - Math.abs(originalX)) * signNotZero(octY);
		}
		int packedX = Math.round(Math.max(0.0f, Math.min(1.0f, octX * 0.5f + 0.5f)) * 255.0f);
		int packedY = Math.round(Math.max(0.0f, Math.min(1.0f, octY * 0.5f + 0.5f)) * 255.0f);
		return sourceFlags | SOURCE_MODEL_SMOOTH_NORMAL
			| packedX << SOURCE_MODEL_NORMAL_X_SHIFT
			| packedY << SOURCE_MODEL_NORMAL_Y_SHIFT;
	}

	private static float signNotZero(float value)
	{
		return value < 0.0f ? -1.0f : 1.0f;
	}

	private static int applyModelOverride(int color, int amount, CapturedModel model)
	{
		int hue = model.overrideHue;
		int saturation = model.overrideSaturation;
		int luminance = model.overrideLuminance;
		if (hue != -1)
			color += (amount * (hue - (color >> 10 & 0x3f)) >> 7) << 10;
		if (saturation != -1)
			color += (amount * (saturation - (color >> 7 & 7)) >> 7) << 7;
		if (luminance != -1)
			color += amount * (luminance - (color & 0x7f)) >> 7;
		return color;
	}

	private void addModelVertex(
		float[] x, float[] y, float[] z, int vertex, int orientation, float sine, float cosine,
		int worldX, int worldY, int worldZ, int color, float u, float v, int textureId,
		int sourceFlags)
	{
		float localX = x[vertex];
		float localZ = z[vertex];
		if (orientation != 0)
		{
			float originalX = localX;
			localX = localZ * sine + originalX * cosine;
			localZ = localZ * cosine - originalX * sine;
		}
		addVertex(localX + worldX, y[vertex] + worldY, localZ + worldZ,
			color, u, v, textureId, sourceFlags);
	}

	private void addVertex(float x, float y, float z, int color, float u, float v, int textureId)
	{
		addVertex(x, y, z, color, color, u, v, textureId, 0);
	}

	private void addVertex(
		float x, float y, float z, int color, float u, float v, int textureId, int sourceFlags)
	{
		addVertex(x, y, z, color, color, u, v, textureId, sourceFlags);
	}

	private void addVertex(
		float x, float y, float z, int color, int vanillaColor,
		float u, float v, int textureId, int sourceFlags)
	{
		if (!hasVertexCapacity())
			return;
		ensureCapacity(vertexCount + 1);
		int offset = vertexCount * PACKED_VERTEX_BYTES;
		packedVertices.putFloat(offset, x);
		packedVertices.putFloat(offset + 4, y);
		packedVertices.putFloat(offset + 8, z);
		packedVertices.putInt(offset + 12, color);
		packedVertices.putFloat(offset + 16, u);
		packedVertices.putFloat(offset + 20, v);
		packedVertices.putInt(offset + 24, textureId);
		packedVertices.putInt(offset + 28, sourceFlags);
		packedVertices.putInt(offset + 32, vanillaColor);
		vertexCount++;
	}

	private boolean hasVertexCapacity()
	{
		return buildingStaticGeometry
			? vertexCount < MAX_STATIC_VERTICES
			: vertexCount < Math.min(
				staticVertexCount + MAX_DYNAMIC_VERTICES, MAX_TOTAL_VERTICES);
	}

	private void ensureCapacity(int requiredVertices)
	{
		int currentCapacity = packedVertices.capacity() / PACKED_VERTEX_BYTES;
		if (requiredVertices <= currentCapacity)
		{
			return;
		}
		int capacity = Math.min(MAX_TOTAL_VERTICES,
			Math.max(INITIAL_VERTEX_CAPACITY, Math.max(requiredVertices, currentCapacity * 2)));
		ByteBuffer replacement = allocateVertices(capacity);
		ByteBuffer source = packedVertices.duplicate();
		source.clear();
		source.limit(vertexCount * PACKED_VERTEX_BYTES);
		replacement.put(source);
		replacement.clear();
		packedVertices = replacement;
	}

	private static final class TerrainVertexKey
	{
		private final int x;
		private final int y;
		private final int z;
		private final int plane;

		private TerrainVertexKey(int x, int y, int z, int plane)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.plane = plane;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
				return true;
			if (!(other instanceof TerrainVertexKey))
				return false;
			TerrainVertexKey key = (TerrainVertexKey) other;
			return x == key.x && y == key.y && z == key.z && plane == key.plane;
		}

		@Override
		public int hashCode()
		{
			int hash = x;
			hash = 31 * hash + y;
			hash = 31 * hash + z;
			return 31 * hash + plane;
		}
	}

	private static final class TerrainBlend
	{
		private double hdRed;
		private double hdGreen;
		private double hdBlue;
		private double vanillaRed;
		private double vanillaGreen;
		private double vanillaBlue;
		private int hdCount;
		private int vanillaCount;
		private int firstHdColor;
		private int firstVanillaColor;
		private boolean sameHdColor = true;
		private boolean sameVanillaColor = true;
		private int[] materialIds = new int[4];
		private int[] materialCounts = new int[4];
		private int materialCount;

		private void add(int hdColor, int vanillaColor, int materialId)
		{
			addColor(hdColor, false);
			addColor(vanillaColor, true);
			for (int index = 0; index < materialCount; index++)
			{
				if (materialIds[index] == materialId)
				{
					materialCounts[index]++;
					return;
				}
			}
			if (materialCount == materialIds.length)
			{
				materialIds = Arrays.copyOf(materialIds, materialIds.length * 2);
				materialCounts = Arrays.copyOf(materialCounts, materialCounts.length * 2);
			}
			materialIds[materialCount] = materialId;
			materialCounts[materialCount] = 1;
			materialCount++;
		}

		private void addColor(int packedHsl, boolean vanilla)
		{
			if (packedHsl < 0 || packedHsl == HIDDEN_HSL)
				return;
			int count = vanilla ? vanillaCount : hdCount;
			if (count == 0)
			{
				if (vanilla)
					firstVanillaColor = packedHsl;
				else
					firstHdColor = packedHsl;
			}
			else if (vanilla ? packedHsl != firstVanillaColor : packedHsl != firstHdColor)
			{
				if (vanilla)
					sameVanillaColor = false;
				else
					sameHdColor = false;
			}

			double hue = ((packedHsl >> 10) & 63) / 64.0 + 1.0 / 128.0;
			double saturation = ((packedHsl >> 7) & 7) / 8.0 + 1.0 / 16.0;
			double lightness = (packedHsl & 127) / 128.0;
			double q = lightness < .5
				? lightness * (1 + saturation)
				: lightness + saturation - lightness * saturation;
			double p = 2 * lightness - q;
			double red = srgbToLinear(hueChannel(p, q, hue + 1.0 / 3.0));
			double green = srgbToLinear(hueChannel(p, q, hue));
			double blue = srgbToLinear(hueChannel(p, q, hue - 1.0 / 3.0));
			if (vanilla)
			{
				vanillaRed += red;
				vanillaGreen += green;
				vanillaBlue += blue;
				vanillaCount++;
			}
			else
			{
				hdRed += red;
				hdGreen += green;
				hdBlue += blue;
				hdCount++;
			}
		}

		private int hdColor()
		{
			if (hdCount == 0 || sameHdColor)
				return firstHdColor;
			return linearRgbToPackedHsl(hdRed / hdCount, hdGreen / hdCount, hdBlue / hdCount);
		}

		private int vanillaColor()
		{
			if (vanillaCount == 0 || sameVanillaColor)
				return firstVanillaColor;
			return linearRgbToPackedHsl(
				vanillaRed / vanillaCount,
				vanillaGreen / vanillaCount,
				vanillaBlue / vanillaCount);
		}

		private int material()
		{
			if (materialCount == 0)
				return -1;
			int best = 0;
			for (int index = 1; index < materialCount; index++)
			{
				if (materialCounts[index] > materialCounts[best]
					|| materialCounts[index] == materialCounts[best]
					&& compareMaterial(materialIds[index], materialIds[best]) < 0)
					best = index;
			}
			return materialIds[best];
		}

		private static int compareMaterial(int first, int second)
		{
			if (first >= 0 && second < 0)
				return -1;
			if (first < 0 && second >= 0)
				return 1;
			return Integer.compare(first, second);
		}
	}

	private static double hueChannel(double p, double q, double value)
	{
		double hue = value;
		if (hue < 0)
			hue += 1;
		if (hue > 1)
			hue -= 1;
		if (hue < 1.0 / 6.0)
			return p + (q - p) * 6 * hue;
		if (hue < .5)
			return q;
		if (hue < 2.0 / 3.0)
			return p + (q - p) * (2.0 / 3.0 - hue) * 6;
		return p;
	}

	private static double srgbToLinear(double value)
	{
		return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
	}

	private static double linearToSrgb(double value)
	{
		double clamped = Math.max(0, Math.min(1, value));
		return clamped <= .0031308
			? clamped * 12.92
			: 1.055 * Math.pow(clamped, 1.0 / 2.4) - .055;
	}

	private static int linearRgbToPackedHsl(double linearRed, double linearGreen, double linearBlue)
	{
		double red = linearToSrgb(linearRed);
		double green = linearToSrgb(linearGreen);
		double blue = linearToSrgb(linearBlue);
		double maximum = Math.max(red, Math.max(green, blue));
		double minimum = Math.min(red, Math.min(green, blue));
		double lightness = (maximum + minimum) * .5;
		double hue = 0;
		double saturation = 0;
		double delta = maximum - minimum;
		if (delta > 1e-9)
		{
			saturation = delta / Math.max(1e-9, 1 - Math.abs(2 * lightness - 1));
			if (maximum == red)
				hue = ((green - blue) / delta) % 6;
			else if (maximum == green)
				hue = (blue - red) / delta + 2;
			else
				hue = (red - green) / delta + 4;
			hue /= 6;
			if (hue < 0)
				hue += 1;
		}
		int packedHue = Math.floorMod((int) Math.round((hue - 1.0 / 128.0) * 64), 64);
		int packedSaturation = clampColor((int) Math.round((saturation - 1.0 / 16.0) * 8), 7);
		int packedLightness = clampColor((int) Math.round(lightness * 128), 127);
		return packedHue << 10 | packedSaturation << 7 | packedLightness;
	}

	private static int clampColor(int value, int maximum)
	{
		return Math.max(0, Math.min(maximum, value));
	}

	private static ByteBuffer allocateVertices(int capacity)
	{
		return ByteBuffer.allocateDirect(capacity * PACKED_VERTEX_BYTES)
			.order(ByteOrder.nativeOrder());
	}

	// Test/debug accessors deliberately decode only the live range.
	float[] uvs()
	{
		float[] result = new float[vertexCount * 2];
		for (int vertex = 0; vertex < vertexCount; vertex++)
		{
			int offset = vertex * PACKED_VERTEX_BYTES;
			result[vertex * 2] = packedVertices.getFloat(offset + 16);
			result[vertex * 2 + 1] = packedVertices.getFloat(offset + 20);
		}
		return result;
	}

	int[] textureIds()
	{
		int[] result = new int[vertexCount];
		for (int vertex = 0; vertex < vertexCount; vertex++)
			result[vertex] = packedVertices.getInt(vertex * PACKED_VERTEX_BYTES + 24);
		return result;
	}

	int[] sourceFlags()
	{
		int[] result = new int[vertexCount];
		for (int vertex = 0; vertex < vertexCount; vertex++)
			result[vertex] = packedVertices.getInt(vertex * PACKED_VERTEX_BYTES + 28);
		return result;
	}

	int[] vanillaColors()
	{
		int[] result = new int[vertexCount];
		for (int vertex = 0; vertex < vertexCount; vertex++)
			result[vertex] = packedVertices.getInt(vertex * PACKED_VERTEX_BYTES + 32);
		return result;
	}
}
