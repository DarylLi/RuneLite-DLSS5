package rs117.hd;

import java.awt.Canvas;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.BufferProvider;
import net.runelite.api.Actor;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.TileObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.Tile;
import net.runelite.api.TextureProvider;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.entityhider.EntityHiderPlugin;
import rs117.hd.webgpu.WebGpuCapabilities;
import rs117.hd.webgpu.WebGpu117ResourceLoader;
import rs117.hd.webgpu.DlssMode;
import rs117.hd.webgpu.WebGpuDlssStatus;
import rs117.hd.webgpu.WebGpuFishingSpotReplacer;
import rs117.hd.webgpu.WebGpuRuntime;
import rs117.hd.webgpu.WebGpuSceneBuffer;
import rs117.hd.webgpu.WebGpuSceneVisibility;
import rs117.hd.webgpu.WebGpuSettings;
import rs117.hd.webgpu.WebGpuSettingsWindow;
import rs117.hd.utils.Mat4;

/**
 * Opt-in WebGPU integration shell. It is intentionally separate from
 * {@link HdPlugin}, keeping 117 HD's production OpenGL lifecycle untouched.
 */
@Slf4j
@PluginDescriptor(
	name = "117 HD WebGPU (Experimental)",
	description = "Experimental standalone WebGPU renderer for 117 HD",
	tags = { "webgpu", "vulkan", "dlss", "gpu", "hd" },
	conflicts = "GPU"
)
@PluginDependency(EntityHiderPlugin.class)
public class WebGpuPlugin extends Plugin implements DrawCallbacks {
	private static final int WEBGPU_TEXTURE_SIZE = 256;
	private static final int TEXTURE_ATLAS_COLUMNS = 16;
	private static final int STATIC_SECTOR_SIZE = Constants.CHUNK_SIZE;
	private static final int STATIC_SECTOR_PREFETCH_TILES = Constants.CHUNK_SIZE;
	private static final int MAX_EXPANDED_MAP_LOADING_CHUNKS = 5;
	private static final int UNEXPANDED_SCENE_RADIUS_TILES = Constants.SCENE_SIZE / 2;
	private static final long STATIC_SECTOR_COMMIT_INTERVAL_MILLIS = 220L;
	private static final int MAX_IN_FLIGHT_STATIC_SECTORS = 24;
	private static final int MAX_STATIC_SECTOR_COMMIT_VERTICES = 99_999;
	private static final int STATIC_COMPACTION_HIGH_WATER_PERCENT = 90;
	private static final int STATIC_COMPACTION_LOW_WATER_PERCENT = 70;
	private static final long STATIC_COMPACTION_RETRY_MILLIS = 5_000L;
	private static final int STATIC_SECTOR_UNLOAD_HYSTERESIS_TILES =
		STATIC_SECTOR_SIZE * 2;
	private static final int STATIC_ROLLING_EVICTION_MIN_SECTORS = 16;
	private static final int STATIC_ROLLING_EVICTION_MIN_VERTEX_PERCENT = 12;
	private static final int STATIC_SATURATED_EVICTION_MIN_SECTORS = 8;
	private static final int STATIC_SATURATED_EVICTION_MIN_VERTEX_PERCENT = 3;
	private static final int STATIC_ROLLING_EVICTION_MIN_TRAVEL_SECTORS = 4;
	private static final long STATIC_ROLLING_EVICTION_IDLE_MILLIS = 1_000L;
	private static final int MAX_RETAINED_REBASE_TILES = Constants.SCENE_SIZE;
	private static final long STATIC_CAPTURE_BUDGET_NANOS = 2_000_000L;
	private static final int MAX_STATIC_SCAN_TILE_CHAINS_PER_FRAME = 256;
	private static final int MAX_STATIC_CAPTURE_TILE_CHAINS_PER_FRAME = 32;
	private static final int MAX_STATIC_CAPTURE_SCENE_SLOTS_PER_FRAME = 8_192;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	private WebGpuRuntime runtime;
	private WebGpuFishingSpotReplacer fishingSpotReplacer;
	private WebGpuSettings settings;
	private WebGpuSettingsWindow settingsWindow;
	private Canvas canvas;
	private int webGpuDrawDistance = 50;
	private boolean appliedSmoothTerrainMaterials = true;
	private volatile boolean dlssAvailable;
	private volatile boolean neuralRenderingAvailable;
	private volatile boolean rayReconstructionAvailable;
	// These latches deliberately live for the Java process rather than being
	// persisted. A failed signed preview feature therefore cannot create a native
	// retry storm, while a full RuneLite restart gets one clean retry opportunity.
	private volatile boolean neuralRenderingSessionDisabled;
	private volatile boolean rayReconstructionSessionDisabled;
	private volatile String neuralRenderingDisabledReason = "";
	private volatile String rayReconstructionDisabledReason = "";
	private volatile DlssMode appliedDlssMode = DlssMode.OFF;
	private boolean appliedNeuralRendering;
	private boolean appliedRayReconstruction;
	private volatile WebGpuDlssStatus lastDlssRuntimeStatus;
	private volatile String dlssFallbackReason = "";
	private long lastDlssStatusRefresh;
	private int surfaceWidth;
	private int surfaceHeight;
	private boolean active;
	private final WebGpuSceneBuffer sceneBuffer = new WebGpuSceneBuffer();
	private float[] viewProjection = Mat4.identity();
	private float[] cameraViewToClip = Mat4.identity();
	private final float[] frameCameraPosition = new float[3];
	private final float[] rawFrameCameraPosition = new float[3];
	private boolean sceneFrameReady;
	private float frameCameraX;
	private float frameCameraZ;
	private float frameSceneCenterX;
	private float frameSceneCenterZ;
	private float rawFrameSceneCenterX;
	private float rawFrameSceneCenterZ;
	private long lastFrameDiagnostic;
	private long lastSlowFrameDiagnostic;
	private long lastMotionDiagnostic;
	private long frameSequence;
	private final StringBuilder callbackTrace = new StringBuilder();
	private int actorCallbackCount;
	private boolean localPlayerSubmitted;
	private int submittedPlayerX;
	private int submittedPlayerZ;
	private long submittedPlayerModelHash;
	private boolean texturesUploaded;
	private volatile WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver;
	private final Set<Long> stagedStaticObjects = new HashSet<>();
	private final Set<Long> stagedFrameObjects = new HashSet<>();
	private final Set<Object> stagedFrameTerrain =
		Collections.newSetFromMap(new IdentityHashMap<>());
	private Scene visibilityScene;
	private WebGpuSceneVisibility sceneVisibility;
	private int staticVisibilityMinLevel = -1;
	private int staticVisibilityCurrentLevel = -1;
	private final Set<Long> stagedStaticLightSources = new HashSet<>();
	private final Set<Long> stagedFrameLightSources = new HashSet<>();
	private final List<DynamicSubmission> currentDynamicSubmissions = new ArrayList<>();
	private final List<DynamicSubmission> previousDynamicSubmissions = new ArrayList<>();
	private int previousDynamicVertexCount;
	private boolean hasPreviousDynamicSubmissions;
	private Scene cachedStaticScene;
	private int cachedStaticPlane = -1;
	private boolean staticSceneValid;
	private boolean buildingStaticScene;
	private WebGpu117ResourceLoader.LightCatalog lightCatalog;
	private ExecutorService staticSectorExecutor;
	private volatile int staticSectorGeneration;
	private final Map<Long, StaticSectorSnapshot> staticSectorSnapshots = new HashMap<>();
	private final Set<Long> requestedStaticSectors = new HashSet<>();
	private final Map<Long, Future<?>> requestedStaticSectorTasks = new HashMap<>();
	private final Set<Long> committedStaticSectors = new HashSet<>();
	private final Set<Long> activeStaticSectors = new HashSet<>();
	private final Map<Long, CommittedStaticSectorRange> committedStaticSectorRanges =
		new HashMap<>();
	private final Map<Long, Set<Long>> committedStaticSectorObjects = new HashMap<>();
	private final Map<Long, List<StaticLightSnapshot>> committedStaticSectorLights =
		new HashMap<>();
	private final ConcurrentLinkedQueue<CompletedStaticSector> completedStaticSectors =
		new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<CompletedStaticCompaction> completedStaticCompactions =
		new ConcurrentLinkedQueue<>();
	private StaticCaptureJob staticCaptureJob;
	private long lastStaticSectorCommitMillis;
	private boolean staticCapacityWarningLogged;
	private boolean staticCacheSaturated;
	private boolean staticCompactionInFlight;
	private int staticCompactionGeneration = -1;
	private long lastStaticCompactionAttemptMillis;
	private boolean staticEpochValid;
	private Scene staticEpochScene;
	private int staticEpochWorld;
	private int staticEpochPlane = -1;
	private boolean staticEpochInstanced;
	private int staticEpochBaseX;
	private int staticEpochBaseY;
	private int lastStaticSceneBaseX;
	private int lastStaticSceneBaseY;
	private int sceneEpochOffsetX;
	private int sceneEpochOffsetZ;
	private boolean staticSectorManifestDirty = true;
	private int manifestCenterSectorX = Integer.MIN_VALUE;
	private int manifestCenterSectorZ = Integer.MIN_VALUE;
	private int lastStaticCompactionCenterSectorX = Integer.MIN_VALUE;
	private int lastStaticCompactionCenterSectorZ = Integer.MIN_VALUE;
	private volatile float staticResidencyCenterX;
	private volatile float staticResidencyCenterZ;
	private float lastStaticResidencyObservedX = Float.NaN;
	private float lastStaticResidencyObservedZ = Float.NaN;
	private long lastStaticResidencyMovementMillis;

	@Override
	protected void startUp() {
		staticSectorExecutor = Executors.newFixedThreadPool(
			Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
			new StaticSectorThreadFactory());
		settings = WebGpuSettings.load(configManager);
		fishingSpotReplacer = new WebGpuFishingSpotReplacer(client);
		appliedSmoothTerrainMaterials = settings.smoothTerrainMaterials;
		sceneBuffer.setSmoothTerrainMaterials(appliedSmoothTerrainMaterials);
		clientThread.invoke(() -> {
			canvas = client.getCanvas();
			if (canvas == null || !canvas.isDisplayable())
				return false;

			int[] dimensions = physicalCanvasDimensions();
			runtime = new WebGpuRuntime();
			runtime.attach(canvas, dimensions[0], dimensions[1]);
			runtime.setVsync(settings.vsync);
			runtime.setVisualSettings(settings);
			texturesUploaded = false;
			invalidateStaticScene();
			lightCatalog = null;
			surfaceWidth = dimensions[0];
			surfaceHeight = dimensions[1];

			WebGpuCapabilities capabilities = runtime.capabilities();
			dlssAvailable = capabilities.dlssSuperResolution;
			neuralRenderingAvailable = capabilities.neuralRendering;
			rayReconstructionAvailable = capabilities.rayReconstruction;
			if (!capabilities.rtx || !capabilities.rayQuery)
			{
				settings.rtxEnabled = false;
				settings.dynamicRtxShadows = false;
				settings.rtxReflections = false;
			}
			// The native switch now enables only the isolated static-world shadow
			// pass. It no longer starts reflections, DLSS, RR, FG or Streamline
			// presentation as a side effect.
			runtime.setRtxEnabled(settings.rtxEnabled);
			runtime.setRtxDynamicShadows(
				settings.rtxEnabled && settings.dynamicRtxShadows);
			runtime.setRtxReflections(settings.rtxReflections);
			settings.frameGeneration = false;
			if (!dlssAvailable)
				settings.dlssMode = DlssMode.OFF;
			if (!neuralRenderingAvailable)
				settings.neuralRendering = false;
			if (settings.neuralRendering)
				settings.dlssMode = DlssMode.OFF;
			if (!rayReconstructionAvailable || settings.dlssMode == DlssMode.OFF
				|| !settings.rtxReflections || settings.neuralRendering)
				settings.rayReconstruction = false;
			applyDlssFeatures(
				settings.dlssMode, settings.neuralRendering, settings.rayReconstruction);
			settings.save(configManager);
			log.info(
				"Starting experimental 117 HD WebGPU backend: {} (vendor 0x{}, device 0x{}): {}",
				capabilities.adapterName,
				Integer.toHexString(capabilities.vendorId),
				Integer.toHexString(capabilities.deviceId),
				capabilities.status
			);
			active = true;
			webGpuDrawDistance = settings.drawDistance;
			client.setUnlockedFps(true);
			client.setUnlockedFpsTarget(settings.fpsLimit);
			canvas.setIgnoreRepaint(true);
			client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.ZBUF | DrawCallbacks.NORMALS);
			client.setExpandedMapLoading(expandedMapLoadingChunks(webGpuDrawDistance));
			client.setDrawCallbacks(this);
			client.resizeCanvas();
			if (client.getGameState() == GameState.LOGGED_IN)
				client.setGameState(GameState.LOADING);
			SwingUtilities.invokeLater(() -> {
				settingsWindow = new WebGpuSettingsWindow(
					canvas, settings, capabilities, this::settingsChanged);
				refreshOptionalDlssAvailability();
				settingsWindow.setDlssRuntimeStatus(
					lastDlssRuntimeStatus, dlssFallbackReason);
			});
			return true;
		});
	}

	@Override
	protected void shutDown() {
		if (settingsWindow != null)
		{
			settingsWindow.close();
			settingsWindow = null;
		}
		clientThread.invoke(() -> {
			active = false;
			if (fishingSpotReplacer != null)
				fishingSpotReplacer.despawnAll();
			fishingSpotReplacer = null;
			client.setGpuFlags(0);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);
			if (client.getDrawCallbacks() == this)
				client.setDrawCallbacks(null);
			if (runtime != null)
				runtime.close();
			runtime = null;
			dlssAvailable = false;
			neuralRenderingAvailable = false;
			rayReconstructionAvailable = false;
			appliedDlssMode = DlssMode.OFF;
			appliedNeuralRendering = false;
			appliedRayReconstruction = false;
			lastDlssRuntimeStatus = null;
			dlssFallbackReason = "";
			texturesUploaded = false;
			invalidateStaticScene();
			lightCatalog = null;
			canvas = null;
			client.resizeCanvas();
		});
		ExecutorService executor = staticSectorExecutor;
		staticSectorExecutor = null;
		if (executor != null)
			executor.shutdownNow();
	}

	private void settingsChanged(WebGpuSettings changed)
	{
		changed.frameGeneration = false;
		if (!dlssAvailable)
			changed.dlssMode = DlssMode.OFF;
		boolean refreshSettings = false;
		if (!neuralRenderingAvailable || neuralRenderingSessionDisabled)
		{
			if (changed.neuralRendering && neuralRenderingSessionDisabled
				&& appliedDlssMode != DlssMode.OFF)
				changed.dlssMode = appliedDlssMode;
			refreshSettings |= changed.neuralRendering;
			changed.neuralRendering = false;
		}
		if (changed.neuralRendering)
			changed.dlssMode = DlssMode.OFF;
		if (!rayReconstructionAvailable || rayReconstructionSessionDisabled
			|| changed.dlssMode == DlssMode.OFF
			|| !changed.rtxReflections || changed.neuralRendering)
		{
			refreshSettings |= changed.rayReconstruction;
			changed.rayReconstruction = false;
		}
		changed.save(configManager);
		if (refreshSettings && settingsWindow != null)
			settingsWindow.refreshSettings();
		clientThread.invokeLater(() -> {
			if (!active || runtime == null)
				return;
			try
			{
				int previousDrawDistance = webGpuDrawDistance;
				int previousExpandedChunks = expandedMapLoadingChunks(previousDrawDistance);
				boolean terrainSmoothingChanged =
					appliedSmoothTerrainMaterials != changed.smoothTerrainMaterials;
				webGpuDrawDistance = changed.drawDistance;
				int expandedChunks = expandedMapLoadingChunks(webGpuDrawDistance);
				if (expandedChunks != previousExpandedChunks)
				{
					client.setExpandedMapLoading(expandedChunks);
					if (client.getGameState() == GameState.LOGGED_IN)
						client.setGameState(GameState.LOADING);
				}
				appliedSmoothTerrainMaterials = changed.smoothTerrainMaterials;
				sceneBuffer.setSmoothTerrainMaterials(appliedSmoothTerrainMaterials);
				runtime.setVsync(changed.vsync);
				runtime.setVisualSettings(changed);
				runtime.setRtxEnabled(changed.rtxEnabled);
				runtime.setRtxDynamicShadows(
					changed.rtxEnabled && changed.dynamicRtxShadows);
				runtime.setRtxReflections(changed.rtxReflections);
				if (changed.dlssMode != appliedDlssMode ||
					changed.neuralRendering != appliedNeuralRendering ||
					changed.rayReconstruction != appliedRayReconstruction)
					applyDlssFeatures(
						changed.dlssMode, changed.neuralRendering,
						changed.rayReconstruction);
				client.setUnlockedFps(true);
				client.setUnlockedFpsTarget(changed.fpsLimit);
				if (previousDrawDistance != webGpuDrawDistance || terrainSmoothingChanged)
					invalidateStaticScene();
			}
			catch (RuntimeException ex)
			{
				fallBackAfterRendererFailure(ex);
			}
		});
	}

	private void applyDlssFeatures(
		DlssMode requestedMode,
		boolean requestNeuralRendering,
		boolean requestRayReconstruction)
	{
		boolean neuralRenderingSuppressed =
			requestNeuralRendering && neuralRenderingSessionDisabled;
		boolean neuralRendering = neuralRenderingAvailable
			&& !neuralRenderingSessionDisabled && requestNeuralRendering;
		DlssMode superResolutionFallback = requestedMode != DlssMode.OFF
			? requestedMode
			: appliedDlssMode != DlssMode.OFF
				? appliedDlssMode
				: DlssMode.MAX_QUALITY;
		// The signed preview defines NR's tag-70 input/output in a
		// display-referred color domain, while this SR integration consumes linear
		// HDR. Until NVIDIA publishes a supported chaining contract, never feed one
		// feature's output directly into the other.
		DlssMode mode = neuralRendering
			? DlssMode.OFF
			: dlssAvailable
				? neuralRenderingSuppressed && requestedMode == DlssMode.OFF
					? superResolutionFallback
					: requestedMode
				: DlssMode.OFF;
		boolean rayReconstruction = rayReconstructionAvailable
			&& !rayReconstructionSessionDisabled
			&& requestRayReconstruction && mode != DlssMode.OFF
			&& settings.rtxReflections && !neuralRendering;
		dlssFallbackReason = "";
		try
		{
			runtime.requestDlss(
				mode, surfaceWidth, surfaceHeight, false,
				rayReconstruction, neuralRendering);
			settings.dlssMode = mode;
			settings.neuralRendering = neuralRendering;
			settings.rayReconstruction = rayReconstruction;
			appliedDlssMode = mode;
			appliedNeuralRendering = neuralRendering;
			appliedRayReconstruction = rayReconstruction;
			refreshDlssRuntimeStatus();
		}
		catch (RuntimeException ex)
		{
			// RR is optional enhancement. Preserve a valid SR request if the
			// denoiser rejects a guide or runtime configuration.
			if (rayReconstruction)
			{
				rayReconstructionSessionDisabled = true;
				rayReconstructionDisabledReason = ex.getMessage();
				dlssFallbackReason =
					"Ray Reconstruction was rejected; Super Resolution fallback retained: "
						+ ex.getMessage();
				log.warn(
					"DLSS Ray Reconstruction failed and is disabled for this RuneLite session; " +
						"retaining Super Resolution mode {}: {}",
					mode, ex.getMessage());
				refreshOptionalDlssAvailability();
				try
				{
					runtime.requestDlss(
						mode, surfaceWidth, surfaceHeight, false, false, false);
					settings.dlssMode = mode;
					settings.neuralRendering = false;
					settings.rayReconstruction = false;
					appliedDlssMode = mode;
					appliedNeuralRendering = false;
					appliedRayReconstruction = false;
					settings.save(configManager);
					if (settingsWindow != null)
						settingsWindow.refreshSettings();
					refreshDlssRuntimeStatus();
					return;
				}
				catch (RuntimeException srFailure)
				{
					ex.addSuppressed(srFailure);
				}
			}
			// Neural Rendering is a signed preview feature. If its configuration is
			// rejected, retain a separately requested Super Resolution mode rather
			// than dropping the whole renderer back to native resolution.
			if (neuralRendering)
			{
				neuralRenderingSessionDisabled = true;
				neuralRenderingDisabledReason = ex.getMessage();
				dlssFallbackReason =
					"Neural Rendering was rejected; Super Resolution fallback retained: "
						+ ex.getMessage();
				log.warn(
					"DLSS 5 Neural Rendering failed and is disabled for this RuneLite session; " +
						"falling back to DLSS Super Resolution mode {}: {}",
					superResolutionFallback, ex.getMessage());
				refreshOptionalDlssAvailability();
				try
				{
					runtime.requestDlss(
						superResolutionFallback, surfaceWidth, surfaceHeight,
						false, false, false);
					settings.dlssMode = superResolutionFallback;
					settings.neuralRendering = false;
					settings.rayReconstruction = false;
					appliedDlssMode = superResolutionFallback;
					appliedNeuralRendering = false;
					appliedRayReconstruction = false;
					settings.save(configManager);
					if (settingsWindow != null)
						settingsWindow.refreshSettings();
					refreshDlssRuntimeStatus();
					return;
				}
				catch (RuntimeException srFailure)
				{
					ex.addSuppressed(srFailure);
				}
			}
			log.warn("DLSS feature request failed; returning to native WebGPU rendering", ex);
			dlssFallbackReason = "DLSS request rejected; using native WebGPU: "
				+ ex.getMessage();
			settings.dlssMode = DlssMode.OFF;
			settings.neuralRendering = false;
			settings.frameGeneration = false;
			settings.rayReconstruction = false;
			appliedDlssMode = DlssMode.OFF;
			appliedNeuralRendering = false;
			appliedRayReconstruction = false;
			try
			{
				runtime.requestDlss(
					DlssMode.OFF, surfaceWidth, surfaceHeight, false, false, false);
			}
			catch (RuntimeException cleanupFailure)
			{
				log.warn("Unable to confirm the native DLSS fallback state", cleanupFailure);
			}
			settings.save(configManager);
			if (settingsWindow != null)
				settingsWindow.refreshSettings();
			refreshDlssRuntimeStatus();
		}
	}

	private void refreshDlssRuntimeStatus()
	{
		WebGpuRuntime currentRuntime = runtime;
		if (currentRuntime == null)
			return;
		try
		{
			WebGpuDlssStatus status = currentRuntime.dlssStatus();
			lastDlssRuntimeStatus = status;
			WebGpuSettingsWindow window = settingsWindow;
			if (window != null)
				window.setDlssRuntimeStatus(status, dlssFallbackReason);
		}
		catch (RuntimeException ex)
		{
			// Diagnostics must never take down an otherwise healthy renderer.
			log.debug("Unable to refresh the effective DLSS status", ex);
		}
	}

	private void refreshOptionalDlssAvailability()
	{
		WebGpuSettingsWindow window = settingsWindow;
		if (window != null)
			window.setOptionalDlssAvailability(
				rayReconstructionAvailable && !rayReconstructionSessionDisabled,
				neuralRenderingAvailable && !neuralRenderingSessionDisabled,
				rayReconstructionDisabledReason,
				neuralRenderingDisabledReason);
	}

	@Override
	public void draw(int overlayColor) {
		if (!active || runtime == null)
			return;
		try {
			traceCallback("present");
			long presentationStarted = System.nanoTime();
			long now = System.currentTimeMillis();
			if (client.getGameState() == GameState.LOGGED_IN && now - lastFrameDiagnostic >= 5000) {
				lastFrameDiagnostic = now;
				WebGpuCapabilities liveCapabilities = runtime.capabilities();
				log.info(
					"WebGPU scene frame: ready={}, vertices={} (static={}, dynamic={}), " +
						"shadows={}, dynamicShadows={}, reflections={}, sunHour={}, ambient={}, GPU={}",
					sceneFrameReady, sceneBuffer.vertexCount(), sceneBuffer.staticVertexCount(),
					sceneBuffer.vertexCount() - sceneBuffer.staticVertexCount(),
					settings.rtxEnabled, settings.rtxEnabled && settings.dynamicRtxShadows,
					settings.rtxReflections, settings.timeOfDay, settings.ambientIntensity,
					liveCapabilities.status);
			}
			int[] dimensions = physicalCanvasDimensions();
			if (dimensions[0] != surfaceWidth || dimensions[1] != surfaceHeight) {
				surfaceWidth = dimensions[0];
				surfaceHeight = dimensions[1];
				runtime.resize(surfaceWidth, surfaceHeight);
				// The native resize path refreshes the optimal render dimensions.
				// Re-requesting here makes a resize failure observable to Java and
				// gives us a clean raster fallback instead of a half-configured mode.
				if (appliedDlssMode != DlssMode.OFF || appliedNeuralRendering)
					applyDlssFeatures(
						appliedDlssMode, appliedNeuralRendering,
						appliedRayReconstruction);
			}

			BufferProvider bufferProvider = client.getBufferProvider();
			if (sceneFrameReady && sceneBuffer.vertexCount() > 0) {
				prepareDynamicHistoryBoundary();
				publishStaticSectorManifestIfNeeded();
				runtime.presentScene(
					sceneBuffer,
					viewProjection,
					cameraViewToClip,
					frameCameraPosition,
					client.getViewportXOffset(),
					client.getViewportYOffset(),
					client.getViewportWidth(),
					client.getViewportHeight(),
					client.getSkyboxColor(),
					bufferProvider.getPixels(),
					bufferProvider.getWidth(),
					bufferProvider.getHeight()
				);
			} else {
				runtime.presentBgra(
					bufferProvider.getPixels(),
					bufferProvider.getWidth(),
					bufferProvider.getHeight()
				);
			}
			if (now - lastDlssStatusRefresh >= 1000)
			{
				lastDlssStatusRefresh = now;
				refreshDlssRuntimeStatus();
			}
			long presentationMillis = (System.nanoTime() - presentationStarted) / 1_000_000L;
			if (presentationMillis >= 250 && now - lastSlowFrameDiagnostic >= 5000)
			{
				lastSlowFrameDiagnostic = now;
				log.warn(
					"Slow WebGPU presentation: {} ms, vertices={} (static={}, dynamic={})",
					presentationMillis, sceneBuffer.vertexCount(), sceneBuffer.staticVertexCount(),
					sceneBuffer.vertexCount() - sceneBuffer.staticVertexCount());
			}
			if (client.getGameState() == GameState.LOGGED_IN && now - lastMotionDiagnostic >= 1000)
			{
				lastMotionDiagnostic = now;
				logMotionDiagnostics();
			}
		} catch (RuntimeException ex) {
			fallBackAfterRendererFailure(ex);
		}
	}

	private void fallBackAfterRendererFailure(RuntimeException failure)
	{
		if (!active)
			return;
		active = false;
		log.error("WebGPU frame presentation failed; returning RuneLite to its standard renderer", failure);

		// A device reset can otherwise become a launcher crash loop because these
		// settings are restored before a fresh device exists.  Preserve all visual
		// preferences, but persistently disarm the native GPU features responsible
		// for the failed frame.
		settings.rtxEnabled = false;
		settings.dynamicRtxShadows = false;
		settings.rtxReflections = false;
		settings.dlssMode = DlssMode.OFF;
		settings.neuralRendering = false;
		settings.frameGeneration = false;
		settings.rayReconstruction = false;
		settings.save(configManager);

		clientThread.invokeLater(() ->
		{
			if (client.getDrawCallbacks() == this)
				client.setDrawCallbacks(null);
			client.setGpuFlags(0);
			client.setUnlockedFps(false);
			if (canvas != null)
				canvas.setIgnoreRepaint(false);

			WebGpuRuntime failedRuntime = runtime;
			runtime = null;
			if (failedRuntime != null)
			{
				try
				{
					failedRuntime.close();
				}
				catch (RuntimeException closeFailure)
				{
					log.warn("WebGPU cleanup after the failed frame was incomplete", closeFailure);
				}
			}
			dlssAvailable = false;
			neuralRenderingAvailable = false;
			rayReconstructionAvailable = false;
			appliedDlssMode = DlssMode.OFF;
			appliedNeuralRendering = false;
			appliedRayReconstruction = false;
			texturesUploaded = false;
			invalidateStaticScene();
			lightCatalog = null;
			client.resizeCanvas();

			SwingUtilities.invokeLater(() ->
			{
				if (settingsWindow != null)
				{
					settingsWindow.close();
					settingsWindow = null;
				}
			});
		});
	}

	@Override
	public void drawScene(
		double cameraX,
		double cameraY,
		double cameraZ,
		double cameraPitch,
		double cameraYaw,
		int plane
	) {
		frameSequence++;
		callbackTrace.setLength(0);
		traceCallback("drawScene");
		actorCallbackCount = 0;
		localPlayerSubmitted = false;
		submittedPlayerX = 0;
		submittedPlayerZ = 0;
		submittedPlayerModelHash = 0;
		Scene scene = client.getTopLevelWorldView().getScene();
		if (scene != null)
			updateStaticEpoch(scene);
		prepareSceneFrame(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		if (fishingSpotReplacer != null)
			fishingSpotReplacer.update();
		if (scene != null)
			beginSceneFrame(scene);
	}

	@Override
	public void preSceneDraw(
		Scene scene,
		Projection entityProjection,
		float cameraX,
		float cameraY,
		float cameraZ,
		float cameraPitch,
		float cameraYaw,
		int minLevel,
		int level,
		int maxLevel,
		Set<Integer> hideRoofIds
	) {
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			traceCallback("preSceneDraw");
			// Roof IDs change with camera/player/hover position. Upper floors use
			// visible callbacks, so these changes never invalidate resident chunks.
			int previousMinLevel = minimumStaticDrawLevel(scene);
			int previousCurrentLevel = currentStaticDrawLevel(scene);
			visibilityScene = scene;
			sceneVisibility = new WebGpuSceneVisibility(
				minLevel, level, maxLevel, hideRoofIds,
				client.getVarbitValue(VarbitID.OPTION_HIDE_ROOFTOPS) != 0);
			// drawScene/capture may precede the first preSceneDraw. Compare its
			// scene/client fallback too, so an initially different callback range
			// removes any incorrectly captured prefix before this frame is drawn.
			if (previousMinLevel != minLevel || previousCurrentLevel != level)
				invalidateStaticScene();
			staticVisibilityMinLevel = minLevel;
			staticVisibilityCurrentLevel = level;
			updateStaticEpoch(scene);
			prepareSceneFrame(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
	}

	private void beginSceneFrame(Scene scene)
	{
		scene.setDrawDistance(webGpuDrawDistance);
		updateSceneCenter();
		refreshStaticSectorManifestCenter();
		uploadTextureAtlasIfReady();
		stagedFrameObjects.clear();
		stagedFrameTerrain.clear();
		stagedFrameLightSources.clear();
		currentDynamicSubmissions.clear();
		// A compacted prefix is adopted before resetToStaticGeometry and before any
		// RuneLite model callback can append the dynamic suffix.  Until this point
		// the old complete prefix remains the one presented by native WebGPU.
		adoptCompletedStaticCompaction();
		ensureStaticSectorScene(scene);
		advanceStaticSectorCapture(scene);
		sceneBuffer.resetToStaticGeometry();
		maybeStartStaticCompaction();
		queueVisibleStaticSectors();
		commitCompletedStaticSectors();
		sceneBuffer.beginLiveLightSelection();
		stageCommittedStaticLights();
	}

	private void ensureStaticSectorScene(Scene scene)
	{
		if (staticSceneValid && cachedStaticScene == scene
			&& cachedStaticPlane == client.getPlane())
			return;
		if (groundTextureResolver == null)
			return;

		staticCaptureJob = createStaticCaptureJob(scene);
		cachedStaticScene = scene;
		cachedStaticPlane = client.getPlane();
		staticSceneValid = true;
		log.info("Started budgeted WebGPU static scene scan");
	}

	/**
	 * Selects a stable local-coordinate epoch for ordinary top-level map rebases.
	 * RuneLite rebuilds the Scene every chunk while all callback coordinates move
	 * back by that chunk. Keeping the epoch anchored in world space lets the
	 * already-committed vertex prefix and RTX acceleration structures survive.
	 */
	private void updateStaticEpoch(Scene scene)
	{
		int world = client.getWorld();
		int plane = client.getPlane();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		boolean instanced = scene.isInstance();
		boolean sceneChanged = staticEpochScene != scene;
		if (!staticEpochValid)
		{
			startStaticEpoch(scene, world, plane, instanced, baseX, baseY);
			return;
		}

		int deltaX = baseX - lastStaticSceneBaseX;
		int deltaY = baseY - lastStaticSceneBaseY;
		int epochDeltaX = baseX - staticEpochBaseX;
		int epochDeltaY = baseY - staticEpochBaseY;
		boolean baseChanged = deltaX != 0 || deltaY != 0;
		boolean compatibleStreamingRebase = client.getGameState() == GameState.LOGGED_IN
			&& !instanced && !staticEpochInstanced
			&& world == staticEpochWorld && plane == staticEpochPlane
			&& (!baseChanged
				|| Math.floorMod(deltaX, Constants.CHUNK_SIZE) == 0
				&& Math.floorMod(deltaY, Constants.CHUNK_SIZE) == 0
				&& Math.abs(deltaX) <= MAX_RETAINED_REBASE_TILES
				&& Math.abs(deltaY) <= MAX_RETAINED_REBASE_TILES);

		if (world != staticEpochWorld || plane != staticEpochPlane
			|| instanced != staticEpochInstanced
			|| ((sceneChanged || baseChanged) && !compatibleStreamingRebase))
		{
			log.info(
				"Starting a new WebGPU static epoch: world {}->{}, plane {}->{}, "
					+ "instance {}->{}, sceneChanged={}, baseDelta={},{}",
				staticEpochWorld, world, staticEpochPlane, plane,
				staticEpochInstanced, instanced, sceneChanged, deltaX, deltaY);
			startStaticEpoch(scene, world, plane, instanced, baseX, baseY);
			return;
		}

		sceneEpochOffsetX = epochDeltaX * Perspective.LOCAL_TILE_SIZE;
		sceneEpochOffsetZ = epochDeltaY * Perspective.LOCAL_TILE_SIZE;
		if (sceneChanged || baseChanged)
		{
			// Immutable snapshots already carry their scene-to-epoch translation and
			// remain valid across an ordinary 8-tile rebase. Only the client-thread
			// capture job contains live Tile references from the outgoing Scene.
			softInvalidateStaticCapture();
			staticEpochScene = scene;
			lastStaticSceneBaseX = baseX;
			lastStaticSceneBaseY = baseY;
			if (baseChanged)
				log.debug(
					"Retaining WebGPU static epoch across scene rebase {},{} -> {},{}",
					baseX - deltaX, baseY - deltaY, baseX, baseY);
		}
	}

	private void startStaticEpoch(
		Scene scene, int world, int plane, boolean instanced, int baseX, int baseY)
	{
		cancelStaticCompaction();
		staticSectorGeneration++;
		cancelStaticCaptureJob();
		staticSceneValid = false;
		cachedStaticScene = null;
		cachedStaticPlane = -1;
		staticSectorSnapshots.clear();
		cancelRequestedStaticSectorTasks();
		committedStaticSectors.clear();
		activeStaticSectors.clear();
		committedStaticSectorRanges.clear();
		committedStaticSectorObjects.clear();
		committedStaticSectorLights.clear();
		completedStaticSectors.clear();
		stagedStaticObjects.clear();
		stagedStaticLightSources.clear();
		currentDynamicSubmissions.clear();
		previousDynamicSubmissions.clear();
		previousDynamicVertexCount = 0;
		hasPreviousDynamicSubmissions = false;
		staticCapacityWarningLogged = false;
		staticCacheSaturated = false;
		staticSectorManifestDirty = true;
		manifestCenterSectorX = Integer.MIN_VALUE;
		manifestCenterSectorZ = Integer.MIN_VALUE;
		lastStaticCompactionCenterSectorX = Integer.MIN_VALUE;
		lastStaticCompactionCenterSectorZ = Integer.MIN_VALUE;
		lastStaticResidencyObservedX = Float.NaN;
		lastStaticResidencyObservedZ = Float.NaN;
		lastStaticResidencyMovementMillis = System.currentTimeMillis();
		lastStaticSectorCommitMillis = 0;
		lastStaticCompactionAttemptMillis = 0;
		// A cancelled worker can still hold a read-only view of the old prefix.
		// Always detach storage on a hard epoch rather than overwrite that view.
		sceneBuffer.clearWithFreshStorage();
		sceneBuffer.markStaticGeometryComplete();

		staticEpochValid = true;
		staticEpochScene = scene;
		staticEpochWorld = world;
		staticEpochPlane = plane;
		staticEpochInstanced = instanced;
		staticEpochBaseX = baseX;
		staticEpochBaseY = baseY;
		lastStaticSceneBaseX = baseX;
		lastStaticSceneBaseY = baseY;
		sceneEpochOffsetX = 0;
		sceneEpochOffsetZ = 0;
	}

	/**
	 * Drops only live RuneLite Scene references during an overlapping chunk
	 * rebase. Worker snapshots and completed sector tails are immutable and are
	 * already expressed relative to the persistent world epoch, so discarding
	 * them here caused walking to continually restart otherwise valid work.
	 */
	private void softInvalidateStaticCapture()
	{
		cancelStaticCaptureJob();
		staticSceneValid = false;
		cachedStaticScene = null;
		cachedStaticPlane = -1;
		rebuildCommittedStaticObjectHashes();
	}

	/** Cancels stale workers and snapshots while retaining committed GPU data. */
	private void invalidateStaticSectorSnapshots()
	{
		invalidateStaticSectorSnapshots(false);
	}

	private void invalidateStaticSectorSnapshots(boolean force)
	{
		if (!force && !staticSceneValid && cachedStaticScene == null
			&& staticSectorSnapshots.isEmpty() && requestedStaticSectors.isEmpty()
			&& completedStaticSectors.isEmpty() && staticCaptureJob == null
			&& !staticCompactionInFlight)
			return;
		cancelStaticCompaction();
		staticSectorGeneration++;
		cancelStaticCaptureJob();
		staticSceneValid = false;
		cachedStaticScene = null;
		cachedStaticPlane = -1;
		staticSectorSnapshots.clear();
		cancelRequestedStaticSectorTasks();
		completedStaticSectors.clear();
		rebuildCommittedStaticObjectHashes();
		stagedStaticLightSources.clear();
	}

	private void invalidateStaticScene()
	{
		cancelStaticCompaction();
		staticSectorGeneration++;
		cancelStaticCaptureJob();
		staticSceneValid = false;
		cachedStaticScene = null;
		cachedStaticPlane = -1;
		staticSectorSnapshots.clear();
		cancelRequestedStaticSectorTasks();
		committedStaticSectors.clear();
		activeStaticSectors.clear();
		committedStaticSectorRanges.clear();
		committedStaticSectorObjects.clear();
		committedStaticSectorLights.clear();
		completedStaticSectors.clear();
		stagedStaticObjects.clear();
		stagedStaticLightSources.clear();
		currentDynamicSubmissions.clear();
		previousDynamicSubmissions.clear();
		previousDynamicVertexCount = 0;
		hasPreviousDynamicSubmissions = false;
		staticCapacityWarningLogged = false;
		staticCacheSaturated = false;
		staticSectorManifestDirty = true;
		manifestCenterSectorX = Integer.MIN_VALUE;
		manifestCenterSectorZ = Integer.MIN_VALUE;
		lastStaticCompactionCenterSectorX = Integer.MIN_VALUE;
		lastStaticCompactionCenterSectorZ = Integer.MIN_VALUE;
		lastStaticResidencyObservedX = Float.NaN;
		lastStaticResidencyObservedZ = Float.NaN;
		lastStaticResidencyMovementMillis = System.currentTimeMillis();
		lastStaticSectorCommitMillis = 0;
		lastStaticCompactionAttemptMillis = 0;
		staticEpochValid = false;
		staticEpochScene = null;
		staticEpochPlane = -1;
		sceneEpochOffsetX = 0;
		sceneEpochOffsetZ = 0;
		sceneBuffer.clearWithFreshStorage();
		sceneBuffer.markStaticGeometryComplete();
	}

	/**
	 * Invalidates an asynchronous copy. Hard epoch resets subsequently detach the
	 * live buffer rather than overwrite bytes still referenced by its prefix view.
	 */
	private void cancelStaticCompaction()
	{
		staticCompactionInFlight = false;
		staticCompactionGeneration = -1;
		completedStaticCompactions.clear();
	}

	private void cancelStaticCaptureJob()
	{
		StaticCaptureJob job = staticCaptureJob;
		staticCaptureJob = null;
		if (job != null)
			job.cancel();
	}

	private void cancelRequestedStaticSectorTasks()
	{
		for (Future<?> task : requestedStaticSectorTasks.values())
			task.cancel(false);
		requestedStaticSectorTasks.clear();
		requestedStaticSectors.clear();
	}

	/**
	 * Creates a client-thread-only capture queue. Tile references never leave
	 * this job; workers receive a sector only after all of its API data has been
	 * copied into immutable primitive snapshots.
	 */
	private StaticCaptureJob createStaticCaptureJob(Scene scene)
	{
		// Only geometry already present in the persistent static prefix may
		// suppress RuneLite's live object callbacks while this capture is pending.
		rebuildCommittedStaticObjectHashes();
		int baseX = scene.getBaseX();
		int baseY = scene.getBaseY();
		WebGpuSceneBuffer underwaterClassifier = new WebGpuSceneBuffer();
		underwaterClassifier.setGroundTextureResolver(groundTextureResolver);
		underwaterClassifier.setSmoothTerrainMaterials(appliedSmoothTerrainMaterials);
		WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture =
			WebGpuSceneBuffer.beginUnderwaterTerrainCapture(
				scene, rawFrameSceneCenterX, rawFrameSceneCenterZ,
				webGpuDrawDistance + STATIC_SECTOR_PREFETCH_TILES + STATIC_SECTOR_SIZE);
		float captureRadius = (webGpuDrawDistance + STATIC_SECTOR_PREFETCH_TILES
			+ STATIC_SECTOR_SIZE) * Perspective.LOCAL_TILE_SIZE;
		StaticCaptureJob job = new StaticCaptureJob(
			staticSectorGeneration, scene, scene.getWorldViewId(), client.getPlane(),
			baseX, baseY, scene.getExtendedTiles(), underwaterClassifier,
			underwaterCapture, new HashSet<>(stagedStaticObjects), groundTextureResolver,
			appliedSmoothTerrainMaterials,
			rawFrameSceneCenterX, rawFrameSceneCenterZ, captureRadius,
			rawFrameCameraPosition[0], rawFrameCameraPosition[1],
			rawFrameCameraPosition[2], sceneEpochOffsetX, sceneEpochOffsetZ,
			staticEpochBaseX, staticEpochBaseY);
		initializeRadialStaticSectorScan(job);
		return job;
	}

	/**
	 * Enumerates the bounded capture area as 8x8 sectors before retaining any
	 * Tile references. Nearest sectors are scanned first, which makes the first
	 * visible geometry independent of the much larger extended-scene dimensions.
	 */
	private void initializeRadialStaticSectorScan(StaticCaptureJob job)
	{
		int sceneOffset = job.worldViewId == WorldView.TOPLEVEL
			? (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2 : 0;
		int sizeX = 0;
		int sizeZ = 0;
		for (Tile[][] plane : job.tiles)
		{
			if (plane == null)
				continue;
			sizeX = Math.max(sizeX, plane.length);
			for (Tile[] column : plane)
				if (column != null)
					sizeZ = Math.max(sizeZ, column.length);
		}
		job.sceneTileArrayOffset = sceneOffset;
		if (sizeX == 0 || sizeZ == 0)
		{
			job.work = Collections.emptyList();
			job.builders = Collections.emptyMap();
			return;
		}

		int minimumLocalX = Math.max(-sceneOffset,
			(int) Math.floor((job.sceneCenterX - job.captureRadius)
				/ Perspective.LOCAL_TILE_SIZE));
		int maximumLocalX = Math.min(sizeX - sceneOffset - 1,
			(int) Math.floor((job.sceneCenterX + job.captureRadius)
				/ Perspective.LOCAL_TILE_SIZE));
		int minimumLocalZ = Math.max(-sceneOffset,
			(int) Math.floor((job.sceneCenterZ - job.captureRadius)
				/ Perspective.LOCAL_TILE_SIZE));
		int maximumLocalZ = Math.min(sizeZ - sceneOffset - 1,
			(int) Math.floor((job.sceneCenterZ + job.captureRadius)
				/ Perspective.LOCAL_TILE_SIZE));
		if (minimumLocalX > maximumLocalX || minimumLocalZ > maximumLocalZ)
		{
			job.work = Collections.emptyList();
			job.builders = Collections.emptyMap();
			return;
		}

		int minimumSectorX = Math.floorDiv(job.baseX + minimumLocalX, STATIC_SECTOR_SIZE);
		int maximumSectorX = Math.floorDiv(job.baseX + maximumLocalX, STATIC_SECTOR_SIZE);
		int minimumSectorZ = Math.floorDiv(job.baseY + minimumLocalZ, STATIC_SECTOR_SIZE);
		int maximumSectorZ = Math.floorDiv(job.baseY + maximumLocalZ, STATIC_SECTOR_SIZE);
		List<StaticSectorCaptureWork> work = new ArrayList<>();
		Map<Long, StaticSectorBuilder> builders = new HashMap<>();
		for (int sectorX = minimumSectorX; sectorX <= maximumSectorX; sectorX++)
			for (int sectorZ = minimumSectorZ; sectorZ <= maximumSectorZ; sectorZ++)
			{
				StaticSectorCaptureWork sectorWork =
					new StaticSectorCaptureWork(sectorX, sectorZ);
				work.add(sectorWork);
				builders.put(sectorKey(sectorX, sectorZ), sectorWork.builder);
			}
		// An overlapping map rebase retains the near field. Scan newly exposed
		// sectors before reclassifying that retained terrain, otherwise walking
		// waits behind hundreds of already-visible sectors on every chunk load.
		// Nearby retained tiles still participate in each local shoreline stencil.
		work.sort(Comparator.<StaticSectorCaptureWork>comparingInt(
			sector -> needsStaticSectorCapture(sectorKey(
				sector.builder.sectorX, sector.builder.sectorZ)) ? 0 : 1)
			.thenComparingDouble(sector -> staticCaptureWorkDistanceSquared(sector, job)));
		job.work = work;
		job.builders = builders;
	}

	private boolean needsStaticSectorCapture(long key)
	{
		return !committedStaticSectors.contains(key)
			&& !staticSectorSnapshots.containsKey(key)
			&& !requestedStaticSectors.contains(key);
	}

	private static double staticCaptureWorkDistanceSquared(
		StaticSectorCaptureWork work, StaticCaptureJob job)
	{
		double centerX = ((long) work.builder.sectorX * STATIC_SECTOR_SIZE
			+ STATIC_SECTOR_SIZE * .5 - job.baseX) * Perspective.LOCAL_TILE_SIZE;
		double centerZ = ((long) work.builder.sectorZ * STATIC_SECTOR_SIZE
			+ STATIC_SECTOR_SIZE * .5 - job.baseY) * Perspective.LOCAL_TILE_SIZE;
		double dx = centerX - job.sceneCenterX;
		double dz = centerZ - job.sceneCenterZ;
		return dx * dx + dz * dz;
	}

	private void advanceStaticSectorCapture(Scene scene)
	{
		StaticCaptureJob job = staticCaptureJob;
		if (job == null)
			return;
		if (!job.matches(scene, staticSectorGeneration, client.getPlane()))
		{
			invalidateStaticSectorSnapshots(true);
			return;
		}

		long deadline = System.nanoTime() + STATIC_CAPTURE_BUDGET_NANOS;
		if (!advanceStaticSceneScan(job, deadline))
			return;

		staticCaptureJob = null;
		log.debug(
			"Completed budgeted WebGPU capture of {} static sectors",
			staticSectorSnapshots.size());
	}

	/**
	 * Incrementally visits Scene tile slots on RuneLite's client thread. The job
	 * retains API Tile references only until this scan and subsequent sector
	 * capture complete; workers see only primitive/owned snapshots.
	 */
	private boolean advanceStaticSceneScan(StaticCaptureJob job, long deadline)
	{
		int scannedTileChains = 0;
		int capturedTileChains = 0;
		int inspectedSlots = 0;
		while (job.scanWorkIndex < job.work.size())
		{
			StaticSectorCaptureWork sectorWork = job.work.get(job.scanWorkIndex);
			long key = sectorKey(
				sectorWork.builder.sectorX, sectorWork.builder.sectorZ);
			boolean captureSector = needsStaticSectorCapture(key);
			if (!captureSector)
			{
				releaseStaticCaptureWork(sectorWork);
				job.builders.remove(key);
				job.scanWorkIndex++;
				continue;
			}

			while (!sectorWork.scanComplete)
			{
				if (sectorWork.scanPlane >= job.tiles.length)
				{
					sectorWork.scanComplete = true;
					break;
				}

				int offsetX = sectorWork.scanColumn;
				int offsetZ = sectorWork.scanRow;
				int planeIndex = sectorWork.scanPlane;
				sectorWork.scanRow++;
				if (sectorWork.scanRow >= STATIC_SECTOR_SIZE)
				{
					sectorWork.scanRow = 0;
					sectorWork.scanColumn++;
					if (sectorWork.scanColumn >= STATIC_SECTOR_SIZE)
					{
						sectorWork.scanColumn = 0;
						sectorWork.scanPlane++;
					}
				}

				inspectedSlots++;
				int localX = sectorWork.builder.sectorX * STATIC_SECTOR_SIZE
					- job.baseX + offsetX;
				int localZ = sectorWork.builder.sectorZ * STATIC_SECTOR_SIZE
					- job.baseY + offsetZ;
				int arrayX = localX + job.sceneTileArrayOffset;
				int arrayZ = localZ + job.sceneTileArrayOffset;
				Tile tile = staticCaptureTileAt(
					job.tiles, planeIndex, arrayX, arrayZ);
				if (tile != null)
				{
					int tileX = tile.getSceneLocation().getX();
					int tileZ = tile.getSceneLocation().getY();
					int tileSectorX = Math.floorDiv(
						job.baseX + tileX, STATIC_SECTOR_SIZE);
					int tileSectorZ = Math.floorDiv(
						job.baseY + tileZ, STATIC_SECTOR_SIZE);
					float tileCenterX = tileX * Perspective.LOCAL_TILE_SIZE
						+ Perspective.LOCAL_TILE_SIZE * .5f;
					float tileCenterZ = tileZ * Perspective.LOCAL_TILE_SIZE
						+ Perspective.LOCAL_TILE_SIZE * .5f;
					boolean withinCaptureRadius =
						Math.abs(tileCenterX - job.sceneCenterX) <= job.captureRadius
						&& Math.abs(tileCenterZ - job.sceneCenterZ) <= job.captureRadius;
					if (!withinCaptureRadius
						|| tileSectorX != sectorWork.builder.sectorX
						|| tileSectorZ != sectorWork.builder.sectorZ)
					{
						if (inspectedSlots >= MAX_STATIC_CAPTURE_SCENE_SLOTS_PER_FRAME
							|| System.nanoTime() >= deadline)
							return false;
						continue;
					}
					job.underwaterClassifier.captureUnderwaterTileChain(
						job.scene, tile, job.underwaterCapture, job.underwaterCapturedTiles);
					if (captureSector)
					{
						sectorWork.roots.add(tile);
						if (job.smoothTerrainMaterials)
							addRadialStaticTerrainHaloRoots(
								job, sectorWork, planeIndex, arrayX, arrayZ,
								offsetX, offsetZ);
					}
					scannedTileChains++;
				}

				if (scannedTileChains >= MAX_STATIC_SCAN_TILE_CHAINS_PER_FRAME
					|| inspectedSlots >= MAX_STATIC_CAPTURE_SCENE_SLOTS_PER_FRAME
					|| System.nanoTime() >= deadline)
					return false;
			}

			boolean containsWater = WebGpuSceneBuffer.capturedSectorContainsWater(
				job.underwaterCapture, job.baseX, job.baseY,
				sectorWork.builder.sectorX, sectorWork.builder.sectorZ,
				STATIC_SECTOR_SIZE);
			if (containsWater)
			{
				// A river must not wait for capture of every distant building. Finish
				// only the finite shoreline neighborhood needed by this sector, then
				// publish owned data that survives an ordinary map rebase.
				if (!advanceUnderwaterSectorContext(job, sectorWork, deadline))
					return false;
			}

			// Capture and publish each nearest sector as soon as its own required
			// terrain neighborhood is classified. Classification has
			// its own cap: a 64-tile scan must not exhaust the 32-tile model-copy
			// budget before any geometry was captured. Both share the time deadline.
			while (sectorWork.rootIndex < sectorWork.roots.size())
			{
				if (capturedTileChains >= MAX_STATIC_CAPTURE_TILE_CHAINS_PER_FRAME
					|| System.nanoTime() >= deadline)
					return false;
				captureStaticTileChain(
					job.scene, sectorWork.roots.get(sectorWork.rootIndex++),
					job.builders, job.capturedObjects, job.capturedTiles);
				capturedTileChains++;
			}
			while (sectorWork.haloRootIndex < sectorWork.haloRoots.size())
			{
				if (capturedTileChains >= MAX_STATIC_CAPTURE_TILE_CHAINS_PER_FRAME
					|| System.nanoTime() >= deadline)
					return false;
				captureStaticTerrainHaloChain(
					job.scene, sectorWork.haloRoots.get(sectorWork.haloRootIndex++),
					sectorWork.builder, sectorWork.capturedHaloTiles);
				capturedTileChains++;
			}

			captureWorldLights(
				job.scene, Collections.singletonMap(key, sectorWork.builder));
			if (!sectorWork.builder.isEmpty())
				staticSectorSnapshots.put(key, sectorWork.builder.build(
					job.generation, job.groundTextureResolver,
					job.smoothTerrainMaterials, sectorWork.underwaterCapture,
					job.cameraX, job.cameraY, job.cameraZ,
					job.epochOffsetX, job.epochOffsetZ,
					job.epochBaseX, job.epochBaseY));
			releaseStaticCaptureWork(sectorWork);
			job.builders.remove(key);
			job.scanWorkIndex++;
		}

		job.tiles = null;
		job.underwaterCapturedTiles.clear();
		job.work = Collections.emptyList();
		job.builders = Collections.emptyMap();
		return true;
	}

	private boolean advanceUnderwaterSectorContext(
		StaticCaptureJob job, StaticSectorCaptureWork work, long deadline)
	{
		if (work.underwaterCapture != null)
			return true;
		int padding = WebGpuSceneBuffer.underwaterCapturePaddingTiles();
		int span = STATIC_SECTOR_SIZE + 2 * padding;
		int localX = work.builder.sectorX * STATIC_SECTOR_SIZE - job.baseX;
		int localZ = work.builder.sectorZ * STATIC_SECTOR_SIZE - job.baseY;
		int totalSlots = job.tiles.length * span * span;
		int inspectedSlots = 0;
		int classifiedTiles = 0;
		while (work.underwaterContextSlot < totalSlots)
		{
			if (System.nanoTime() >= deadline
				|| inspectedSlots >= MAX_STATIC_CAPTURE_SCENE_SLOTS_PER_FRAME
				|| classifiedTiles >= MAX_STATIC_SCAN_TILE_CHAINS_PER_FRAME)
				return false;
			int slot = work.underwaterContextSlot++;
			int plane = slot / (span * span);
			int x = slot / span % span + localX - padding;
			int z = slot % span + localZ - padding;
			Tile tile = staticCaptureTileAt(job.tiles, plane,
				x + job.sceneTileArrayOffset, z + job.sceneTileArrayOffset);
			inspectedSlots++;
			if (tile == null || job.underwaterCapturedTiles.contains(tile))
				continue;
			job.underwaterClassifier.captureUnderwaterTileChain(
				job.scene, tile, job.underwaterCapture, job.underwaterCapturedTiles);
			classifiedTiles++;
		}
		work.underwaterCapture = WebGpuSceneBuffer.copyUnderwaterSectorCapture(
			job.underwaterCapture, localX, localZ, STATIC_SECTOR_SIZE);
		return true;
	}

	private static Tile staticCaptureTileAt(
		Tile[][][] tiles, int planeIndex, int x, int z)
	{
		if (tiles == null || planeIndex < 0 || planeIndex >= tiles.length
			|| tiles[planeIndex] == null || x < 0 || x >= tiles[planeIndex].length
			|| tiles[planeIndex][x] == null || z < 0
			|| z >= tiles[planeIndex][x].length)
			return null;
		return tiles[planeIndex][x][z];
	}

	private static void addRadialStaticTerrainHaloRoots(
		StaticCaptureJob job, StaticSectorCaptureWork work,
		int planeIndex, int arrayX, int arrayZ, int offsetX, int offsetZ)
	{
		if (offsetX == 0)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX - 1, arrayZ);
		if (offsetX == STATIC_SECTOR_SIZE - 1)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX + 1, arrayZ);
		if (offsetZ == 0)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX, arrayZ - 1);
		if (offsetZ == STATIC_SECTOR_SIZE - 1)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX, arrayZ + 1);
		if (offsetX == 0 && offsetZ == 0)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX - 1, arrayZ - 1);
		if (offsetX == 0 && offsetZ == STATIC_SECTOR_SIZE - 1)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX - 1, arrayZ + 1);
		if (offsetX == STATIC_SECTOR_SIZE - 1 && offsetZ == 0)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX + 1, arrayZ - 1);
		if (offsetX == STATIC_SECTOR_SIZE - 1
			&& offsetZ == STATIC_SECTOR_SIZE - 1)
			addRadialStaticTerrainHaloRoot(job, work, planeIndex, arrayX + 1, arrayZ + 1);
	}

	private static void addRadialStaticTerrainHaloRoot(
		StaticCaptureJob job, StaticSectorCaptureWork work,
		int planeIndex, int x, int z)
	{
		Tile root = staticCaptureTileAt(job.tiles, planeIndex, x, z);
		if (root != null && work.haloRootSet.add(root))
			work.haloRoots.add(root);
	}

	private static void releaseStaticCaptureWork(StaticSectorCaptureWork work)
	{
		work.roots.clear();
		work.haloRoots.clear();
		work.haloRootSet.clear();
		work.capturedHaloTiles.clear();
		work.underwaterCapture = null;
	}

	private void captureStaticTileChain(
		Scene scene, Tile root, Map<Long, StaticSectorBuilder> builders,
		Set<Long> capturedObjects, Set<Tile> capturedTiles)
	{
		for (Tile tile = root; tile != null; tile = tile.getBridge())
		{
			if (!capturedTiles.add(tile))
				continue;
			// The client's roof and upper-floor selection changes independently of
			// sector residency. Keep these tiles/models out of static geometry and
			// its RTX acceleration structures; their visible callbacks draw them.
			if (!isStaticTileCacheable(scene, tile))
				continue;
			int tileX = tile.getSceneLocation().getX();
			int tileZ = tile.getSceneLocation().getY();
			int sectorX = Math.floorDiv(scene.getBaseX() + tileX, STATIC_SECTOR_SIZE);
			int sectorZ = Math.floorDiv(scene.getBaseY() + tileZ, STATIC_SECTOR_SIZE);
			long key = sectorKey(sectorX, sectorZ);
			if (committedStaticSectors.contains(key))
			{
				Set<Long> residentHashes = committedStaticSectorObjects.get(key);
				if (residentHashes != null)
					capturedObjects.addAll(residentHashes);
				continue;
			}
			StaticSectorBuilder builder = builders.computeIfAbsent(
				key,
				ignored -> new StaticSectorBuilder(sectorX, sectorZ));
			int renderPlane = tile.getRenderLevel();
			int sourcePlane = tile.getPlane();
			SceneTilePaint tilePaint = tile.getSceneTilePaint();
			if (tilePaint != null)
			{
				WebGpuSceneBuffer.CapturedTilePaint capturedPaint =
					WebGpuSceneBuffer.captureTilePaint(
						scene, tilePaint, renderPlane, sourcePlane, tileX, tileZ);
				if (capturedPaint != null)
					builder.terrain.add(new StaticTerrainSnapshot(capturedPaint, null));
			}
			SceneTileModel tileModel = tile.getSceneTileModel();
			if (tileModel != null)
			{
				WebGpuSceneBuffer.CapturedTileModel capturedModel =
					WebGpuSceneBuffer.captureTileModel(
						scene, tile, tileModel,
						renderPlane, sourcePlane, tileX, tileZ);
				if (capturedModel != null)
					builder.terrain.add(new StaticTerrainSnapshot(null, capturedModel));
			}
			captureStaticTileObjects(builder, tile, capturedObjects);
		}
	}

	/**
	 * Captures only the neighboring terrain needed to make material/color
	 * smoothing agree across sector borders. These snapshots never carry
	 * objects, lights, hashes or click state, and their emitted vertices are
	 * discarded before the sector is finalized.
	 */
	private static void captureStaticTerrainHaloChain(
		Scene scene, Tile root, StaticSectorBuilder builder, Set<Tile> capturedTiles)
	{
		for (Tile tile = root; tile != null; tile = tile.getBridge())
		{
			if (!capturedTiles.add(tile))
				continue;
			int tileX = tile.getSceneLocation().getX();
			int tileZ = tile.getSceneLocation().getY();
			int renderPlane = tile.getRenderLevel();
			int sourcePlane = tile.getPlane();
			SceneTilePaint tilePaint = tile.getSceneTilePaint();
			if (tilePaint != null)
			{
				WebGpuSceneBuffer.CapturedTilePaint capturedPaint =
					WebGpuSceneBuffer.captureTilePaint(
						scene, tilePaint, renderPlane, sourcePlane, tileX, tileZ);
				if (capturedPaint != null)
					builder.haloTerrain.add(new StaticTerrainSnapshot(capturedPaint, null));
			}
			SceneTileModel tileModel = tile.getSceneTileModel();
			if (tileModel != null)
			{
				WebGpuSceneBuffer.CapturedTileModel capturedModel =
					WebGpuSceneBuffer.captureTileModel(
						scene, tile, tileModel,
						renderPlane, sourcePlane, tileX, tileZ);
				if (capturedModel != null)
					builder.haloTerrain.add(new StaticTerrainSnapshot(null, capturedModel));
			}
		}
	}

	private void captureStaticTileObjects(
		StaticSectorBuilder builder, Tile tile, Set<Long> capturedObjects)
	{
		WallObject wall = tile.getWallObject();
		if (wall != null && isCacheable(wall.getRenderable1(), wall.getRenderable2())
			&& !capturedObjects.contains(wall.getHash()))
		{
			boolean captured = captureStaticModel(builder, wall.getRenderable1(), 0,
				wall.getX(), wall.getZ(), wall.getY());
			captured |= captureStaticModel(builder, wall.getRenderable2(), 0,
				wall.getX(), wall.getZ(), wall.getY());
			if (captured)
			{
				capturedObjects.add(wall.getHash());
				builder.objectHashes.add(wall.getHash());
				captureStaticLights(builder, lightCatalog == null ? null
					: lightCatalog.objectLights.get(wall.getId()), 0,
					wall.getX(), wall.getZ(), wall.getY());
			}
		}

		GroundObject ground = tile.getGroundObject();
		if (ground != null && isCacheable(ground.getRenderable())
			&& !capturedObjects.contains(ground.getHash()))
		{
			if (captureStaticModel(builder, ground.getRenderable(), 0,
				ground.getX(), ground.getZ(), ground.getY()))
			{
				capturedObjects.add(ground.getHash());
				builder.objectHashes.add(ground.getHash());
				captureStaticLights(builder, lightCatalog == null ? null
					: lightCatalog.objectLights.get(ground.getId()), 0,
					ground.getX(), ground.getZ(), ground.getY());
			}
		}

		DecorativeObject decorative = tile.getDecorativeObject();
		if (decorative != null
			&& isCacheable(decorative.getRenderable(), decorative.getRenderable2())
			&& !capturedObjects.contains(decorative.getHash()))
		{
			boolean captured = captureStaticModel(builder, decorative.getRenderable(), 0,
				decorative.getX() + decorative.getXOffset(), decorative.getZ(),
				decorative.getY() + decorative.getYOffset());
			captured |= captureStaticModel(builder, decorative.getRenderable2(), 0,
				decorative.getX() + decorative.getXOffset2(), decorative.getZ(),
				decorative.getY() + decorative.getYOffset2());
			if (captured)
			{
				capturedObjects.add(decorative.getHash());
				builder.objectHashes.add(decorative.getHash());
				captureStaticLights(builder, lightCatalog == null ? null
					: lightCatalog.objectLights.get(decorative.getId()), 0,
					decorative.getX(), decorative.getZ(), decorative.getY());
			}
		}

		for (GameObject gameObject : tile.getGameObjects())
		{
			if (gameObject == null || !isCacheable(gameObject.getRenderable())
				|| capturedObjects.contains(gameObject.getHash()))
				continue;
			if (!captureStaticModel(builder, gameObject.getRenderable(),
				gameObject.getModelOrientation(),
				gameObject.getX(), gameObject.getZ(), gameObject.getY()))
				continue;
			capturedObjects.add(gameObject.getHash());
			builder.objectHashes.add(gameObject.getHash());
			captureStaticLights(builder, lightCatalog == null ? null
				: lightCatalog.objectLights.get(gameObject.getId()),
				gameObject.getModelOrientation(),
				gameObject.getX(), gameObject.getZ(), gameObject.getY());
		}
	}

	private static boolean captureStaticModel(
		StaticSectorBuilder builder, Renderable renderable,
		int orientation, int x, int y, int z)
	{
		if (renderable == null)
			return false;
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		WebGpuSceneBuffer.CapturedModel captured = WebGpuSceneBuffer.captureModel(model);
		if (captured == null)
			return false;
		builder.models.add(new StaticModelSnapshot(captured, orientation, x, y, z));
		return true;
	}

	private void captureStaticLights(
		StaticSectorBuilder builder, List<WebGpu117ResourceLoader.HdLight> lights,
		int orientation, float x, float y, float z)
	{
		if (lights == null)
			return;
		float sine = orientation == 0 ? 0 : Perspective.SINE[orientation] / 65536f;
		float cosine = orientation == 0 ? 1 : Perspective.COSINE[orientation] / 65536f;
		for (WebGpu117ResourceLoader.HdLight light : lights)
		{
			float offsetX = light.offset[0];
			float offsetZ = light.offset[2];
			builder.lights.add(new StaticLightSnapshot(
				x - cosine * offsetX - sine * offsetZ,
				y - light.height - light.offset[1],
				z - cosine * offsetZ + sine * offsetX, light));
		}
	}

	private void captureWorldLights(
		Scene scene, Map<Long, StaticSectorBuilder> builders)
	{
		if (lightCatalog == null)
			return;
		for (WebGpu117ResourceLoader.HdLight light : lightCatalog.worldLights)
		{
			if (light.worldX == null || light.worldY == null || light.plane != client.getPlane())
				continue;
			int sectorX = Math.floorDiv(light.worldX, STATIC_SECTOR_SIZE);
			int sectorZ = Math.floorDiv(light.worldY, STATIC_SECTOR_SIZE);
			long key = sectorKey(sectorX, sectorZ);
			if (committedStaticSectors.contains(key))
				continue;
			StaticSectorBuilder builder = builders.get(key);
			if (builder == null)
				continue;
			float x = (light.worldX - scene.getBaseX()) * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			float z = (light.worldY - scene.getBaseY()) * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			builder.lights.add(new StaticLightSnapshot(
				x, sampleHeight(scene, light.plane, x, z) - light.height, z, light));
		}
	}

	/**
	 * Reclaims inactive sectors only when enough dead geometry makes the native
	 * epoch rebuild worthwhile. Visibility itself rolls continuously through the
	 * sector manifest; compacting on every chunk boundary would re-upload the
	 * entire retained prefix and rebuild every RTX BLAS, producing a much larger
	 * hitch than the memory it reclaimed.
	 */
	private void maybeStartStaticCompaction()
	{
		long now = System.currentTimeMillis();
		ExecutorService executor = staticSectorExecutor;
		float unloadRadius = staticSectorUnloadRadius();
		int staleSectorCount = 0;
		int staleVertices = 0;
		for (CommittedStaticSectorRange range : committedStaticSectorRanges.values())
		{
			if (!activeStaticSectors.contains(range.key))
			{
				staleSectorCount++;
				staleVertices += range.vertexCount;
			}
		}
		int highWaterVertices = WebGpuSceneBuffer.maxStaticVertices()
			* STATIC_COMPACTION_HIGH_WATER_PERCENT / 100;
		int rollingMinimumStaleVertices = WebGpuSceneBuffer.maxStaticVertices()
			* STATIC_ROLLING_EVICTION_MIN_VERTEX_PERCENT / 100;
		int saturatedMinimumStaleVertices = WebGpuSceneBuffer.maxStaticVertices()
			* STATIC_SATURATED_EVICTION_MIN_VERTEX_PERCENT / 100;
		boolean movedEnough = lastStaticCompactionCenterSectorX == Integer.MIN_VALUE
			|| Math.max(
				Math.abs(manifestCenterSectorX - lastStaticCompactionCenterSectorX),
				Math.abs(manifestCenterSectorZ - lastStaticCompactionCenterSectorZ))
				>= STATIC_ROLLING_EVICTION_MIN_TRAVEL_SECTORS;
		boolean playerSettled = now - lastStaticResidencyMovementMillis
			>= STATIC_ROLLING_EVICTION_IDLE_MILLIS;
		boolean enoughRollingStaleGeometry = staleSectorCount
			>= STATIC_ROLLING_EVICTION_MIN_SECTORS
			&& staleVertices >= rollingMinimumStaleVertices;
		boolean enoughSaturatedStaleGeometry = staleSectorCount
			>= STATIC_SATURATED_EVICTION_MIN_SECTORS
			&& staleVertices >= saturatedMinimumStaleVertices;
		// A full cache must recover while the player is moving. Waiting for the
		// idle timer here leaves newly entered sectors permanently uncommitted and
		// therefore absent from both raster and RTX. The smaller saturated threshold
		// still prevents an identical/no-op rebuild, while normal high-water cleanup
		// remains deliberately conservative and settled-frame only.
		boolean saturatedRecovery = staticCacheSaturated && movedEnough
			&& enoughSaturatedStaleGeometry;
		boolean proactiveRecovery = sceneBuffer.staticVertexCount() >= highWaterVertices
			&& movedEnough && playerSettled && enoughRollingStaleGeometry;
		boolean worthwhileCompaction = saturatedRecovery || proactiveRecovery;
		if (!worthwhileCompaction || staticCompactionInFlight
			|| committedStaticSectorRanges.size() < 2
			|| executor == null || executor.isShutdown()
			|| now - lastStaticCompactionAttemptMillis < STATIC_COMPACTION_RETRY_MILLIS)
			return;

		int generation = staticSectorGeneration;
		final int targetVertices = (WebGpuSceneBuffer.maxStaticVertices()
			* STATIC_COMPACTION_LOW_WATER_PERCENT / 100) / 3 * 3;
		WebGpuSceneBuffer.StaticGeometrySnapshot prefix =
			sceneBuffer.snapshotStaticGeometry();
		List<CommittedStaticSectorRange> ranges = new ArrayList<>(
			committedStaticSectorRanges.values());
		Set<Long> requiredKeys = new HashSet<>(activeStaticSectors);
		float centerX = frameSceneCenterX;
		float centerZ = frameSceneCenterZ;
		int centerSectorX = manifestCenterSectorX;
		int centerSectorZ = manifestCenterSectorZ;
		staticCompactionInFlight = true;
		staticCompactionGeneration = generation;
		lastStaticCompactionAttemptMillis = now;
		try
		{
			executor.execute(() -> buildStaticCompaction(
				generation, prefix, ranges, requiredKeys, centerX, centerZ,
				unloadRadius, targetVertices, centerSectorX, centerSectorZ));
			log.info(
				"Compacting {} WebGPU sectors ({} inactive / {} vertices) toward {} "
					+ "static vertices; the current {}-vertex prefix remains live",
				ranges.size(), staleSectorCount, staleVertices,
				targetVertices, prefix.vertexCount());
		}
		catch (RejectedExecutionException rejected)
		{
			staticCompactionInFlight = false;
			staticCompactionGeneration = -1;
			log.debug("Static-sector executor stopped before compaction", rejected);
		}
	}

	private void buildStaticCompaction(
		int generation, WebGpuSceneBuffer.StaticGeometrySnapshot prefix,
		List<CommittedStaticSectorRange> ranges,
		Set<Long> requiredKeys, float centerX, float centerZ,
		float unloadRadius, int targetVertices,
		int centerSectorX, int centerSectorZ)
	{
		long started = System.nanoTime();
		try
		{
			if (generation != staticSectorGeneration || Thread.currentThread().isInterrupted())
				return;
			ranges.sort(Comparator.comparingDouble(
				range -> range.distanceSquared(centerX, centerZ)));
			List<CommittedStaticSectorRange> retained = new ArrayList<>();
			Set<Long> retainedKeys = new HashSet<>();
			int retainedVertices = 0;
			// Every sector that was still active when the snapshot was taken is
			// mandatory, even when an unusually dense scene exceeds low-water.
			for (CommittedStaticSectorRange range : ranges)
			{
				if (!requiredKeys.contains(range.key))
					continue;
				retained.add(range);
				retainedKeys.add(range.key);
				retainedVertices += range.vertexCount;
			}
			// Preserve a small hysteresis cache only while it fits below low-water.
			for (CommittedStaticSectorRange range : ranges)
			{
				if (retainedKeys.contains(range.key)
					|| !range.isWithin(centerX, centerZ, unloadRadius))
					continue;
				if (range.vertexCount > 0
					&& retainedVertices + range.vertexCount > targetVertices)
					continue;
				retained.add(range);
				retainedKeys.add(range.key);
				retainedVertices += range.vertexCount;
			}
			if (retained.isEmpty() && !ranges.isEmpty())
			{
				CommittedStaticSectorRange nearest = ranges.get(0);
				retained.add(nearest);
			}

			// Keep the copied prefix in nearest-to-player order. Native RTX catch-up
			// consumes the prefix sequentially, so restoring the old append order here
			// made nearby tiles visible in raster while their shadows lagged behind far
			// geometry. The one-time non-contiguous worker copy is cheaper than that
			// persistent priority inversion.
			WebGpuSceneBuffer replacement = new WebGpuSceneBuffer();
			replacement.clear();
			replacement.reserveFullSceneCapacity();
			Map<Long, CommittedStaticSectorRange> compactedRanges = new HashMap<>();
			for (CommittedStaticSectorRange range : retained)
			{
				if (generation != staticSectorGeneration
					|| Thread.currentThread().isInterrupted())
					return;
				int firstVertex = replacement.staticVertexCount();
				int copied = replacement.appendStaticGeometryRange(
					prefix, range.firstVertex, range.vertexCount);
				if (range.vertexCount > 0 && copied != range.vertexCount)
					throw new IllegalStateException("Unable to copy a retained static sector");
				compactedRanges.put(
					range.key, range.withRange(firstVertex, copied));
			}
			if (replacement.staticVertexCount() == 0)
				replacement.markStaticGeometryComplete();
			completedStaticCompactions.add(new CompletedStaticCompaction(
				generation, replacement, compactedRanges, ranges.size(),
				centerSectorX, centerSectorZ,
				(System.nanoTime() - started) / 1_000_000L, null));
		}
		catch (RuntimeException | OutOfMemoryError failure)
		{
			completedStaticCompactions.add(new CompletedStaticCompaction(
				generation, null, Collections.emptyMap(), ranges.size(),
				centerSectorX, centerSectorZ,
				(System.nanoTime() - started) / 1_000_000L, failure));
		}
	}

	/** Atomically publishes a completed prefix before this frame's dynamic tail. */
	private void adoptCompletedStaticCompaction()
	{
		CompletedStaticCompaction result;
		while ((result = completedStaticCompactions.poll()) != null)
		{
			if (result.generation != staticSectorGeneration
				|| result.generation != staticCompactionGeneration)
				continue;
			staticCompactionInFlight = false;
			staticCompactionGeneration = -1;
			if (result.replacement == null)
			{
				log.warn(
					"Unable to compact the WebGPU static sector cache; retaining the old prefix",
					result.failure);
				return;
			}
			if (!result.ranges.keySet().containsAll(activeStaticSectors))
			{
				// The player crossed far enough while the worker was copying that its
				// old window would now punch holes into the current one. Discard it;
				// the still-live prefix remains authoritative and a settled frame can
				// schedule a fresh compaction later.
				log.debug(
					"Discarding stale WebGPU compaction centered at sector {},{}",
					result.centerSectorX, result.centerSectorZ);
				return;
			}

			int previousVertices = sceneBuffer.staticVertexCount();
			int previousSectors = committedStaticSectors.size();
			sceneBuffer.adoptStaticGeometry(result.replacement);
			Set<Long> retainedKeys = result.ranges.keySet();
			committedStaticSectors.retainAll(retainedKeys);
			committedStaticSectorObjects.keySet().retainAll(retainedKeys);
			committedStaticSectorLights.keySet().retainAll(retainedKeys);
			activeStaticSectors.retainAll(retainedKeys);
			committedStaticSectorRanges.clear();
			committedStaticSectorRanges.putAll(result.ranges);
			staticSectorManifestDirty = true;
			staticCacheSaturated = false;
			staticCapacityWarningLogged = false;
			lastStaticSectorCommitMillis = 0;
			lastStaticCompactionCenterSectorX = result.centerSectorX;
			lastStaticCompactionCenterSectorZ = result.centerSectorZ;
			rebuildCommittedStaticObjectHashes();
			retireFarStaticWork();
			log.info(
				"Adopted compacted WebGPU static prefix: {} -> {} vertices, {} -> {} sectors "
					+ "({} evicted, {} ms worker copy); streaming resumes without clearing the scene",
				previousVertices, sceneBuffer.staticVertexCount(), previousSectors,
				committedStaticSectors.size(),
				result.sourceSectorCount - committedStaticSectors.size(), result.buildMillis);
			return;
		}
	}

	private void queueVisibleStaticSectors()
	{
		ExecutorService executor = staticSectorExecutor;
		if (staticCacheSaturated || executor == null || executor.isShutdown()
			|| groundTextureResolver == null)
			return;
		float radius = staticSectorLoadRadius();
		List<StaticSectorSnapshot> requested = new ArrayList<>();
		int availableRequests = MAX_IN_FLIGHT_STATIC_SECTORS
			- requestedStaticSectors.size();
		if (availableRequests <= 0)
			return;
		for (StaticSectorSnapshot snapshot : staticSectorSnapshots.values())
		{
			if (Math.abs(snapshot.centerX - frameSceneCenterX) > radius
				|| Math.abs(snapshot.centerZ - frameSceneCenterZ) > radius
				|| committedStaticSectors.contains(snapshot.key)
				|| requestedStaticSectors.contains(snapshot.key))
				continue;
			requested.add(snapshot);
		}
		requested.sort(Comparator.comparingDouble(this::staticSectorDistanceSquared));
		for (StaticSectorSnapshot snapshot : requested)
		{
			if (availableRequests-- <= 0)
				break;
			try
			{
				Future<?> task = executor.submit(() -> buildStaticSector(snapshot));
				requestedStaticSectors.add(snapshot.key);
				requestedStaticSectorTasks.put(snapshot.key, task);
			}
			catch (RejectedExecutionException rejected)
			{
				log.debug("Static-sector executor stopped while queueing work", rejected);
				break;
			}
		}
	}

	private double staticSectorDistanceSquared(StaticSectorSnapshot snapshot)
	{
		double dx = snapshot.centerX - frameSceneCenterX;
		double dz = snapshot.centerZ - frameSceneCenterZ;
		return dx * dx + dz * dz;
	}

	private void buildStaticSector(StaticSectorSnapshot snapshot)
	{
		if (!isStaticSectorWorkCurrent(snapshot))
		{
			if (snapshot.generation == staticSectorGeneration)
				completedStaticSectors.add(new CompletedStaticSector(snapshot, null, 0));
			return;
		}
		long started = System.nanoTime();
		try
		{
			WebGpuSceneBuffer sector = new WebGpuSceneBuffer();
			sector.setGroundTextureResolver(snapshot.groundTextureResolver);
			sector.setSmoothTerrainMaterials(snapshot.smoothTerrainMaterials);
			sector.clear();
			if (snapshot.underwaterCapture != null)
				sector.sharePreparedUnderwaterTerrain(
					WebGpuSceneBuffer.finalizeUnderwaterTerrain(snapshot.underwaterCapture));
			for (StaticTerrainSnapshot terrain : snapshot.terrain)
			{
				if (!isStaticSectorWorkCurrent(snapshot)
					|| Thread.currentThread().isInterrupted())
					return;
				if (terrain.paint != null)
					sector.addTilePaint(terrain.paint);
				else
					sector.addTileModel(terrain.model);
			}
			for (StaticModelSnapshot model : snapshot.models)
			{
				if (!isStaticSectorWorkCurrent(snapshot)
					|| Thread.currentThread().isInterrupted())
					return;
				sector.addModel(
					model.model, model.orientation, model.x, model.y, model.z);
			}
			int retainedVertexCount = sector.vertexCount();
			for (StaticTerrainSnapshot terrain : snapshot.haloTerrain)
			{
				if (!isStaticSectorWorkCurrent(snapshot)
					|| Thread.currentThread().isInterrupted())
					return;
				if (terrain.paint != null)
					sector.addTilePaint(terrain.paint);
				else
					sector.addTileModel(terrain.model);
			}
			long now = System.currentTimeMillis();
			for (StaticLightSnapshot light : snapshot.lights)
				sector.addPointLight(
					light.x, light.y, light.z,
					light.animatedRadius(now),
					light.animatedStrength(now), light.color(),
					snapshot.cameraX, snapshot.cameraY, snapshot.cameraZ);
			sector.translateStagedGeometry(snapshot.epochOffsetX, 0, snapshot.epochOffsetZ);
			sector.markStaticGeometryCompleteRetaining(retainedVertexCount);
			if (isStaticSectorWorkCurrent(snapshot))
				completedStaticSectors.add(new CompletedStaticSector(
					snapshot, sector,
					(System.nanoTime() - started) / 1_000_000L));
			else if (snapshot.generation == staticSectorGeneration)
				completedStaticSectors.add(new CompletedStaticSector(
					snapshot, null,
					(System.nanoTime() - started) / 1_000_000L));
		}
		catch (RuntimeException failure)
		{
			log.warn(
				"Unable to build WebGPU static sector {},{}",
				snapshot.sectorX, snapshot.sectorZ, failure);
			if (snapshot.generation == staticSectorGeneration)
				completedStaticSectors.add(new CompletedStaticSector(
					snapshot, null, (System.nanoTime() - started) / 1_000_000L));
		}
	}

	/** Commits completed immutable sector tails only on RuneLite's render thread. */
	private void commitCompletedStaticSectors()
	{
		long now = System.currentTimeMillis();
		// The compactor reads finalized ranges from one immutable prefix.  New
		// commits are held in the completed queue until that prefix is adopted or
		// abandoned, so every recorded first/count pair stays valid.
		if (staticCompactionInFlight || completedStaticSectors.isEmpty()
			|| now - lastStaticSectorCommitMillis < STATIC_SECTOR_COMMIT_INTERVAL_MILLIS)
			return;
		List<CompletedStaticSector> completed = new ArrayList<>();
		CompletedStaticSector result;
		while ((result = completedStaticSectors.poll()) != null)
		{
			requestedStaticSectorTasks.remove(result.snapshot.key);
			if (result.snapshot.generation != staticSectorGeneration
				|| committedStaticSectors.contains(result.snapshot.key))
			{
				requestedStaticSectors.remove(result.snapshot.key);
				continue;
			}
			if (!isWithinStaticSectorRadius(
				result.snapshot.centerX, result.snapshot.centerZ,
				staticSectorUnloadRadius()))
			{
				requestedStaticSectors.remove(result.snapshot.key);
				staticSectorSnapshots.remove(result.snapshot.key);
				continue;
			}
			if (result.buffer == null)
			{
				requestedStaticSectors.remove(result.snapshot.key);
				continue;
			}
			completed.add(result);
		}
		completed.sort(Comparator.comparingDouble(
			sector -> staticSectorDistanceSquared(sector.snapshot)));
		if (completed.isEmpty())
			return;
		List<CompletedStaticSector> selected = new ArrayList<>();
		int selectedVertices = 0;
		for (CompletedStaticSector sector : completed)
		{
			int sectorVertices = sector.buffer.staticVertexCount();
			if (!selected.isEmpty()
				&& selectedVertices + sectorVertices > MAX_STATIC_SECTOR_COMMIT_VERTICES)
			{
				completedStaticSectors.add(sector);
				continue;
			}
			selected.add(sector);
			selectedVertices += sectorVertices;
		}
		int committedVertices = 0;
		long buildMillis = 0;
		int committedSectorCount = 0;
		for (int selectedIndex = 0; selectedIndex < selected.size(); selectedIndex++)
		{
			CompletedStaticSector sector = selected.get(selectedIndex);
			int sectorVertexCount = sector.buffer.staticVertexCount();
			int firstVertex = sceneBuffer.staticVertexCount();
			int appended = sectorVertexCount == 0
				? 0 : sceneBuffer.appendStaticGeometry(sector.buffer);
			if (sectorVertexCount > 0 && appended <= 0)
			{
				// Capacity is an admission limit, never a reason to destroy the
				// already-visible world. Keep the resident prefix and stop admitting
				// more sectors until a genuine world/plane/instance epoch change.
				staticCacheSaturated = true;
				requestedStaticSectors.remove(sector.snapshot.key);
				requestedStaticSectorTasks.remove(sector.snapshot.key);
				// These workers already finished successfully. Keep every later tail
				// queued while compaction frees headroom instead of losing it with a
				// latched requested key. Successful adoption deliberately clears the
				// queue and recaptures against the new resident window.
				for (int pendingIndex = selectedIndex + 1;
					pendingIndex < selected.size(); pendingIndex++)
					completedStaticSectors.add(selected.get(pendingIndex));
				if (!staticCapacityWarningLogged)
				{
					staticCapacityWarningLogged = true;
					log.warn(
						"WebGPU static sector cache saturated at {} vertices; "
							+ "retaining all committed chunks instead of resetting them",
						sceneBuffer.staticVertexCount());
				}
				break;
			}
			committedStaticSectors.add(sector.snapshot.key);
			if (isWithinStaticSectorRadius(
				sector.snapshot.centerX, sector.snapshot.centerZ,
				staticSectorLoadRadius()))
				activeStaticSectors.add(sector.snapshot.key);
			committedStaticSectorRanges.put(
				sector.snapshot.key,
				new CommittedStaticSectorRange(
					sector.snapshot.key, sector.snapshot.sectorX,
					sector.snapshot.sectorZ, sector.snapshot.centerX,
					sector.snapshot.centerZ, firstVertex, appended));
			Set<Long> committedObjects = sectorVertexCount > 0
				? sector.snapshot.objectHashes : Collections.emptySet();
			committedStaticSectorObjects.put(sector.snapshot.key, committedObjects);
			stagedStaticObjects.addAll(committedObjects);
			List<StaticLightSnapshot> committedLights = new ArrayList<>(
				sector.snapshot.lights.size());
			for (StaticLightSnapshot light : sector.snapshot.lights)
				committedLights.add(new StaticLightSnapshot(
					light, sector.snapshot.epochOffsetX, 0,
					sector.snapshot.epochOffsetZ));
			committedStaticSectorLights.put(
				sector.snapshot.key, committedLights);
			staticSectorSnapshots.remove(sector.snapshot.key);
			requestedStaticSectors.remove(sector.snapshot.key);
			requestedStaticSectorTasks.remove(sector.snapshot.key);
			committedSectorCount++;
			committedVertices += appended;
			buildMillis += sector.buildMillis;
		}
		if (committedSectorCount > 0)
		{
			staticSectorManifestDirty = true;
			lastStaticSectorCommitMillis = now;
			log.debug(
				"Committed {} radial WebGPU sectors ({} vertices, {} ms worker time); "
					+ "static prefix now {} vertices",
				committedSectorCount, committedVertices, buildMillis,
				sceneBuffer.staticVertexCount());
		}
	}

	/**
	 * Publishes only when residency changes. The arrays are tiny (one record per
	 * 8x8 sector) and never enter the per-vertex Java/native marshalling path.
	 */
	private void publishStaticSectorManifestIfNeeded()
	{
		if (!staticSectorManifestDirty || runtime == null)
			return;
		List<CommittedStaticSectorRange> ranges = new ArrayList<>();
		for (CommittedStaticSectorRange range : committedStaticSectorRanges.values())
			if (range.vertexCount > 0)
				ranges.add(range);
		ranges.sort(Comparator.comparingInt(range -> range.firstVertex));
		int count = ranges.size();
		long[] sectorIds = new long[count];
		int[] firstVertices = new int[count];
		int[] vertexCounts = new int[count];
		int[] flags = new int[count];
		for (int index = 0; index < count; index++)
		{
			CommittedStaticSectorRange range = ranges.get(index);
			sectorIds[index] = range.key;
			firstVertices[index] = range.firstVertex;
			vertexCounts[index] = range.vertexCount;
			flags[index] = activeStaticSectors.contains(range.key) ? 1 : 0;
		}
		runtime.setStaticSectorManifest(
			sectorIds, firstVertices, vertexCounts, flags);
		staticSectorManifestDirty = false;
	}

	private float staticSectorLoadRadius()
	{
		return (webGpuDrawDistance + STATIC_SECTOR_PREFETCH_TILES
			+ STATIC_SECTOR_SIZE) * Perspective.LOCAL_TILE_SIZE;
	}

	private float staticSectorUnloadRadius()
	{
		return (webGpuDrawDistance + STATIC_SECTOR_PREFETCH_TILES
			+ STATIC_SECTOR_SIZE + STATIC_SECTOR_UNLOAD_HYSTERESIS_TILES)
			* Perspective.LOCAL_TILE_SIZE;
	}

	private boolean isWithinStaticSectorRadius(
		float centerX, float centerZ, float radius)
	{
		return Math.abs(centerX - staticResidencyCenterX) <= radius
			&& Math.abs(centerZ - staticResidencyCenterZ) <= radius;
	}

	private boolean isStaticSectorWorkCurrent(StaticSectorSnapshot snapshot)
	{
		return snapshot.generation == staticSectorGeneration
			&& isWithinStaticSectorRadius(
				snapshot.centerX, snapshot.centerZ, staticSectorUnloadRadius());
	}

	private boolean isStaticSectorKeyWithin(long key, float radius)
	{
		int sectorX = (int) (key >> 32);
		int sectorZ = (int) key;
		float centerX = ((long) sectorX * STATIC_SECTOR_SIZE
			+ STATIC_SECTOR_SIZE * .5f - staticEpochBaseX)
			* Perspective.LOCAL_TILE_SIZE;
		float centerZ = ((long) sectorZ * STATIC_SECTOR_SIZE
			+ STATIC_SECTOR_SIZE * .5f - staticEpochBaseY)
			* Perspective.LOCAL_TILE_SIZE;
		return isWithinStaticSectorRadius(centerX, centerZ, radius);
	}

	/** Re-evaluates active sector masks only after crossing an 8x8 boundary. */
	private void refreshStaticSectorManifestCenter()
	{
		staticResidencyCenterX = frameSceneCenterX;
		staticResidencyCenterZ = frameSceneCenterZ;
		long now = System.currentTimeMillis();
		if (Float.isNaN(lastStaticResidencyObservedX)
			|| Math.abs(frameSceneCenterX - lastStaticResidencyObservedX) >= 1f
			|| Math.abs(frameSceneCenterZ - lastStaticResidencyObservedZ) >= 1f)
		{
			lastStaticResidencyObservedX = frameSceneCenterX;
			lastStaticResidencyObservedZ = frameSceneCenterZ;
			lastStaticResidencyMovementMillis = now;
		}
		int span = STATIC_SECTOR_SIZE * Perspective.LOCAL_TILE_SIZE;
		int sectorX = Math.floorDiv((int) Math.floor(frameSceneCenterX), span);
		int sectorZ = Math.floorDiv((int) Math.floor(frameSceneCenterZ), span);
		if (sectorX == manifestCenterSectorX && sectorZ == manifestCenterSectorZ)
			return;
		manifestCenterSectorX = sectorX;
		manifestCenterSectorZ = sectorZ;
		float loadRadius = staticSectorLoadRadius();
		float unloadRadius = staticSectorUnloadRadius();
		boolean changed = false;
		for (CommittedStaticSectorRange range : committedStaticSectorRanges.values())
		{
			boolean active = activeStaticSectors.contains(range.key);
			boolean shouldBeActive = range.isWithin(
				frameSceneCenterX, frameSceneCenterZ,
				active ? unloadRadius : loadRadius);
			if (shouldBeActive && !active)
				changed |= activeStaticSectors.add(range.key);
			else if (!shouldBeActive && active)
				changed |= activeStaticSectors.remove(range.key);
		}
		if (changed)
			staticSectorManifestDirty = true;
		retireFarStaticWork();
	}

	/** Cancels queued/captured work that has fallen outside the rolling cache. */
	private void retireFarStaticWork()
	{
		float unloadRadius = staticSectorUnloadRadius();
		requestedStaticSectorTasks.entrySet().removeIf(entry ->
		{
			if (isStaticSectorKeyWithin(entry.getKey(), unloadRadius))
				return false;
			entry.getValue().cancel(false);
			requestedStaticSectors.remove(entry.getKey());
			return true;
		});
		staticSectorSnapshots.entrySet().removeIf(entry ->
			!isWithinStaticSectorRadius(
				entry.getValue().centerX, entry.getValue().centerZ, unloadRadius));
		completedStaticSectors.removeIf(completed ->
		{
			if (isWithinStaticSectorRadius(
				completed.snapshot.centerX, completed.snapshot.centerZ, unloadRadius))
				return false;
			requestedStaticSectors.remove(completed.snapshot.key);
			requestedStaticSectorTasks.remove(completed.snapshot.key);
			return true;
		});
	}

	private void rebuildCommittedStaticObjectHashes()
	{
		stagedStaticObjects.clear();
		for (Set<Long> residentHashes : committedStaticSectorObjects.values())
			stagedStaticObjects.addAll(residentHashes);
	}

	/** Re-evaluates animated authored lights against the current epoch camera. */
	private void stageCommittedStaticLights()
	{
		long now = System.currentTimeMillis();
		for (Map.Entry<Long, List<StaticLightSnapshot>> entry
			: committedStaticSectorLights.entrySet())
		{
			if (!activeStaticSectors.contains(entry.getKey()))
				continue;
			List<StaticLightSnapshot> sectorLights = entry.getValue();
			for (StaticLightSnapshot light : sectorLights)
			{
				sceneBuffer.addPointLight(
					light.x, light.y, light.z,
					light.animatedRadius(now), light.animatedStrength(now),
					light.colorR, light.colorG, light.colorB,
					frameCameraPosition[0], frameCameraPosition[1],
					frameCameraPosition[2]);
			}
		}
	}

	private static int expandedMapLoadingChunks(int drawDistance)
	{
		int additionalTiles = Math.max(
			0, drawDistance + STATIC_SECTOR_PREFETCH_TILES
				- UNEXPANDED_SCENE_RADIUS_TILES);
		return Math.min(
			MAX_EXPANDED_MAP_LOADING_CHUNKS,
			(additionalTiles + Constants.CHUNK_SIZE - 1) / Constants.CHUNK_SIZE);
	}

	private static long sectorKey(int sectorX, int sectorZ)
	{
		return (long) sectorX << 32 | sectorZ & 0xffffffffL;
	}

	private void uploadTextureAtlasIfReady()
	{
		if (texturesUploaded || runtime == null)
			return;
		TextureProvider provider = client.getTextureProvider();
		if (provider == null || provider.getTextures() == null || provider.getTextures().length == 0)
			return;

		try
		{
			WebGpu117ResourceLoader.Atlas atlas = WebGpu117ResourceLoader.buildAtlas(
				provider, WEBGPU_TEXTURE_SIZE, TEXTURE_ATLAS_COLUMNS);
			if (atlas == null)
				return;
			runtime.uploadTextureAtlas(
				atlas.rgbaPixels, atlas.vanillaRgbaPixels,
				atlas.normalPixels, atlas.materialParameters,
				atlas.width, atlas.height, atlas.tileSize, atlas.textureCount);
			sceneBuffer.setGroundTextureResolver(atlas.groundTextureResolver);
			groundTextureResolver = atlas.groundTextureResolver;
			lightCatalog = atlas.lightCatalog;
			texturesUploaded = true;
			log.info(
				"Uploaded {} vanilla textures ({} replaced) plus {} 117 ground textures to a {}x{} WebGPU atlas",
				atlas.loadedTextureCount, atlas.hdTextureCount, atlas.groundTextureCount,
				atlas.width, atlas.height);
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to load the 117 HD material resources", ex);
		}
	}

	private void stageStaticScene(Scene scene) {
		stagedStaticObjects.clear();
		stagedStaticLightSources.clear();
		buildingStaticScene = true;
		try
		{
			stageWorldLights(scene);
			sceneBuffer.prepareUnderwaterTerrain(
				scene, rawFrameSceneCenterX, rawFrameSceneCenterZ, webGpuDrawDistance);
			List<Tile> visibleTiles = new ArrayList<>();
			Tile[][][] tiles = scene.getExtendedTiles();
			for (Tile[][] plane : tiles) {
				for (Tile[] column : plane) {
					for (Tile tile : column) {
						if (tile == null)
							continue;
					int tileCenterX = tile.getSceneLocation().getX() * Perspective.LOCAL_TILE_SIZE
						+ Perspective.LOCAL_TILE_SIZE / 2;
					int tileCenterZ = tile.getSceneLocation().getY() * Perspective.LOCAL_TILE_SIZE
						+ Perspective.LOCAL_TILE_SIZE / 2;
					int maximumDistance = webGpuDrawDistance * Perspective.LOCAL_TILE_SIZE;
					if (Math.abs(tileCenterX - frameSceneCenterX) > maximumDistance
						|| Math.abs(tileCenterZ - frameSceneCenterZ) > maximumDistance)
						continue;
						visibleTiles.add(tile);
					}
				}
			}
			visibleTiles.sort(Comparator.comparingDouble(tile -> {
				float x = tile.getSceneLocation().getX() * Perspective.LOCAL_TILE_SIZE
					+ Perspective.LOCAL_TILE_SIZE / 2f - frameSceneCenterX;
				float z = tile.getSceneLocation().getY() * Perspective.LOCAL_TILE_SIZE
					+ Perspective.LOCAL_TILE_SIZE / 2f - frameSceneCenterZ;
				return x * x + z * z;
			}));
			for (Tile tile : visibleTiles)
			{
				if (!sceneBuffer.hasStaticCapacity())
					break;
					if (tile.getSceneTilePaint() != null) {
						int tileX = tile.getSceneLocation().getX();
						int tileZ = tile.getSceneLocation().getY();
						sceneBuffer.addTilePaint(
							scene, tile.getSceneTilePaint(), tile.getRenderLevel(),
							tile.getPlane(), tileX, tileZ);
					}
					if (tile.getSceneTileModel() != null)
						sceneBuffer.addTileModel(
							scene, tile, tile.getSceneTileModel(), tile.getRenderLevel(),
							tile.getPlane(),
							tile.getSceneLocation().getX(), tile.getSceneLocation().getY());
					stageTileObjects(tile);
			}
		}
		finally
		{
			buildingStaticScene = false;
		}
	}

	private void stageTileObjects(Tile tile) {
		WallObject wall = tile.getWallObject();
		if (wall != null && isCacheable(wall.getRenderable1(), wall.getRenderable2())
			&& stagedStaticObjects.add(wall.getHash())) {
			stageObjectLights(wall, 0, wall.getX(), wall.getZ(), wall.getY());
			stageRenderable(wall.getRenderable1(), 0, wall.getX(), wall.getZ(), wall.getY());
			stageRenderable(wall.getRenderable2(), 0, wall.getX(), wall.getZ(), wall.getY());
		}

		GroundObject ground = tile.getGroundObject();
		if (ground != null && isCacheable(ground.getRenderable())
			&& stagedStaticObjects.add(ground.getHash())) {
			stageObjectLights(ground, 0, ground.getX(), ground.getZ(), ground.getY());
			stageRenderable(ground.getRenderable(), 0, ground.getX(), ground.getZ(), ground.getY());
		}

		DecorativeObject decorative = tile.getDecorativeObject();
		if (decorative != null
			&& isCacheable(decorative.getRenderable(), decorative.getRenderable2())
			&& stagedStaticObjects.add(decorative.getHash())) {
			stageObjectLights(
				decorative, 0, decorative.getX(), decorative.getZ(), decorative.getY());
			stageRenderable(
				decorative.getRenderable(), 0,
				decorative.getX() + decorative.getXOffset(), decorative.getZ(),
				decorative.getY() + decorative.getYOffset());
			stageRenderable(
				decorative.getRenderable2(), 0,
				decorative.getX() + decorative.getXOffset2(), decorative.getZ(),
				decorative.getY() + decorative.getYOffset2());
		}

		for (GameObject gameObject : tile.getGameObjects()) {
			if (gameObject != null && isCacheable(gameObject.getRenderable())
				&& stagedStaticObjects.add(gameObject.getHash())) {
				stageObjectLights(
					gameObject, gameObject.getModelOrientation(),
					gameObject.getX(), gameObject.getZ(), gameObject.getY());
				stageRenderable(
					gameObject.getRenderable(), gameObject.getModelOrientation(),
					gameObject.getX(), gameObject.getZ(), gameObject.getY());
			}
		}
	}

	static boolean isCacheable(Renderable... renderables)
	{
		for (Renderable renderable : renderables)
			if (renderable instanceof DynamicObject || renderable instanceof Actor)
				return false;
		return true;
	}

	private void stageWorldLights(Scene scene)
	{
		if (lightCatalog == null)
			return;
		for (WebGpu117ResourceLoader.HdLight light : lightCatalog.worldLights)
		{
			if (light.worldX == null || light.worldY == null || light.plane != client.getPlane())
				continue;
			float x = (light.worldX - scene.getBaseX()) * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			float z = (light.worldY - scene.getBaseY()) * Perspective.LOCAL_TILE_SIZE
				+ Perspective.LOCAL_TILE_SIZE / 2f;
			if (Math.abs(x - rawFrameSceneCenterX) > webGpuDrawDistance * Perspective.LOCAL_TILE_SIZE
				|| Math.abs(z - rawFrameSceneCenterZ) > webGpuDrawDistance * Perspective.LOCAL_TILE_SIZE)
				continue;
			float y = sampleHeight(scene, light.plane, x, z) - light.height;
			stageHdLights(
				List.of(light), 0,
				x + sceneEpochOffsetX, y, z + sceneEpochOffsetZ,
				lightSourceKey(1, light.worldX, light.worldY, light.plane));
		}
	}

	private void stageObjectLights(TileObject object, int orientation, int x, int y, int z)
	{
		if (lightCatalog == null || object == null)
			return;
		stageHdLights(
			lightCatalog.objectLights.get(object.getId()), orientation, x, y, z,
			lightSourceKey(2, object.getId(), x, z));
	}

	private void stageRenderableLights(Renderable renderable, int orientation, int x, int y, int z)
	{
		if (lightCatalog == null || renderable == null)
			return;
		if (renderable instanceof NPC)
		{
			NPC npc = (NPC) renderable;
			stageHdLights(
				lightCatalog.npcLights.get(npc.getId()), orientation, x, y, z,
				lightSourceKey(3, npc.getId(), x, z));
		}
		else if (renderable instanceof Projectile)
		{
			Projectile projectile = (Projectile) renderable;
			stageHdLights(
				lightCatalog.projectileLights.get(projectile.getId()), orientation, x, y, z,
				lightSourceKey(4, projectile.getId(), x, z));
		}
		else if (renderable instanceof GraphicsObject)
		{
			GraphicsObject graphicsObject = (GraphicsObject) renderable;
			stageHdLights(
				lightCatalog.graphicsObjectLights.get(graphicsObject.getId()), orientation, x, y, z,
				lightSourceKey(5, graphicsObject.getId(), x, z));
		}
	}

	private void stageHdLights(
		List<WebGpu117ResourceLoader.HdLight> lights,
		int orientation,
		float x,
		float y,
		float z,
		long sourceKey)
	{
		if (lights == null || lights.isEmpty() || !markLightSource(sourceKey))
			return;
		float sine = orientation == 0 ? 0 : Perspective.SINE[orientation] / 65536f;
		float cosine = orientation == 0 ? 1 : Perspective.COSINE[orientation] / 65536f;
		long now = System.currentTimeMillis();
		for (WebGpu117ResourceLoader.HdLight light : lights)
		{
			float offsetX = light.offset[0];
			float offsetZ = light.offset[2];
			float lightX = x - cosine * offsetX - sine * offsetZ;
			float lightY = y - light.height - light.offset[1];
			float lightZ = z - cosine * offsetZ + sine * offsetX;
			sceneBuffer.addPointLight(
				lightX, lightY, lightZ,
				light.animatedRadius(now), light.animatedStrength(now), light.color,
				frameCameraPosition[0], frameCameraPosition[1], frameCameraPosition[2]);
		}
	}

	private boolean markLightSource(long sourceKey)
	{
		if (buildingStaticScene)
			return stagedStaticLightSources.add(sourceKey);
		return !stagedStaticLightSources.contains(sourceKey)
			&& stagedFrameLightSources.add(sourceKey);
	}

	private static long lightSourceKey(int type, int id, int x, int z)
	{
		long hash = type;
		hash = hash * 0x9e3779b97f4a7c15L + id;
		hash = hash * 0x9e3779b97f4a7c15L + x;
		return hash * 0x9e3779b97f4a7c15L + z;
	}

	private static float sampleHeight(Scene scene, int plane, float localX, float localZ)
	{
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? (net.runelite.api.Constants.EXTENDED_SCENE_SIZE - net.runelite.api.Constants.SCENE_SIZE) / 2
			: 0;
		int tileX = (int) Math.floor(localX / Perspective.LOCAL_TILE_SIZE) + offset;
		int tileZ = (int) Math.floor(localZ / Perspective.LOCAL_TILE_SIZE) + offset;
		int[][][] heights = scene.getTileHeights();
		if (plane < 0 || plane >= heights.length || tileX < 0 || tileZ < 0
			|| tileX >= heights[plane].length || tileZ >= heights[plane][tileX].length)
			return 0;
		return heights[plane][tileX][tileZ];
	}

	private void stageRenderable(Renderable renderable, int orientation, int x, int y, int z) {
		if (renderable == null)
			return;
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model != null)
			sceneBuffer.addModel(model, orientation, x, y, z);
	}

	private void prepareSceneFrame(
		double cameraX,
		double cameraY,
		double cameraZ,
		double cameraPitch,
		double cameraYaw
	) {
		float[] projection = Mat4.scale(client.getScale(), client.getScale(), 1);
		Mat4.mul(projection, Mat4.perspectiveInfiniteReverseZ(
			Math.max(1, client.getViewportWidth()),
			Math.max(1, client.getViewportHeight()),
			50
		));
		float[] matrix = projection.clone();
		Mat4.mul(matrix, Mat4.rotateX((float) cameraPitch));
		Mat4.mul(matrix, Mat4.rotateY((float) cameraYaw));
		float renderCameraX = (float) cameraX + sceneEpochOffsetX;
		float renderCameraZ = (float) cameraZ + sceneEpochOffsetZ;
		Mat4.mul(matrix, Mat4.translate(-renderCameraX, (float) -cameraY, -renderCameraZ));
		viewProjection = matrix;
		cameraViewToClip = projection;
		frameCameraX = renderCameraX;
		frameCameraZ = renderCameraZ;
		frameCameraPosition[0] = renderCameraX;
		frameCameraPosition[1] = (float) cameraY;
		frameCameraPosition[2] = renderCameraZ;
		rawFrameCameraPosition[0] = (float) cameraX;
		rawFrameCameraPosition[1] = (float) cameraY;
		rawFrameCameraPosition[2] = (float) cameraZ;
		sceneFrameReady = true;
	}

	private void updateSceneCenter()
	{
		Actor localPlayer = client.getLocalPlayer();
		if (localPlayer != null && localPlayer.getLocalLocation() != null)
		{
			rawFrameSceneCenterX = localPlayer.getLocalLocation().getX();
			rawFrameSceneCenterZ = localPlayer.getLocalLocation().getY();
		}
		else
		{
			rawFrameSceneCenterX = rawFrameCameraPosition[0];
			rawFrameSceneCenterZ = rawFrameCameraPosition[2];
		}
		frameSceneCenterX = rawFrameSceneCenterX + sceneEpochOffsetX;
		frameSceneCenterZ = rawFrameSceneCenterZ + sceneEpochOffsetZ;
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ) {
		Tile tile = findTerrainCallbackTile(scene, paint, tileX, tileZ);
		if (!shouldStageCallbackTerrain(scene, tile, paint))
			return;
		int firstVertex = sceneBuffer.vertexCount();
		sceneBuffer.addTilePaint(
			scene, paint, tile.getRenderLevel(), tile.getPlane(), tileX, tileZ);
		finishCallbackTerrain(paint, firstVertex);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ) {
		Tile tile = findTerrainCallbackTile(scene, model, tileX, tileZ);
		if (!shouldStageCallbackTerrain(scene, tile, model))
			return;
		int firstVertex = sceneBuffer.vertexCount();
		sceneBuffer.addTileModel(
			scene, tile, model, tile.getRenderLevel(), tile.getPlane(), tileX, tileZ);
		finishCallbackTerrain(model, firstVertex);
	}

	private boolean shouldStageCallbackTerrain(Scene scene, Tile tile, Object surface)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL || tile == null
			|| isStaticTileCacheable(scene, tile))
			return false;
		return callbackVisibility(scene).isVisible(scene, tile) && stagedFrameTerrain.add(surface);
	}

	private int minimumStaticDrawLevel(Scene scene)
	{
		return visibilityScene == scene && staticVisibilityMinLevel >= 0
			? staticVisibilityMinLevel : scene.getMinLevel();
	}

	private int currentStaticDrawLevel(Scene scene)
	{
		return visibilityScene == scene && staticVisibilityCurrentLevel >= 0
			? staticVisibilityCurrentLevel : client.getPlane();
	}

	private boolean isStaticTileCacheable(Scene scene, Tile tile)
	{
		return WebGpuSceneVisibility.isCacheable(
			scene, tile, minimumStaticDrawLevel(scene), currentStaticDrawLevel(scene));
	}

	private WebGpuSceneVisibility callbackVisibility(Scene scene)
	{
		return visibilityScene == scene && sceneVisibility != null ? sceneVisibility
			: new WebGpuSceneVisibility(
				scene.getMinLevel(), client.getPlane(), Constants.MAX_Z - 1,
				Collections.emptySet(), client.getVarbitValue(VarbitID.OPTION_HIDE_ROOFTOPS) != 0);
	}

	private void finishCallbackTerrain(Object surface, int firstVertex)
	{
		int addedVertices = sceneBuffer.vertexCount() - firstVertex;
		if (addedVertices <= 0)
			return;
		sceneBuffer.translateVertexRange(
			firstVertex, addedVertices, sceneEpochOffsetX, 0, sceneEpochOffsetZ);
		// Terrain participates in the same ordinal-motion ordering as models.
		// Swapping equal-sized roof surfaces must reset ambiguous DLSS history.
		currentDynamicSubmissions.add(new DynamicSubmission(
			surface, 0, true, addedVertices, addedVertices));
	}

	private static Tile findTerrainCallbackTile(Scene scene, Object surface, int tileX, int tileZ)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL || surface == null)
			return null;
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null)
			return null;
		int offset = WebGpuSceneVisibility.sceneOffset(scene);
		int x = tileX + offset;
		int z = tileZ + offset;
		// The paint callback's height plane is not necessarily its draw plane;
		// shaped-tile callbacks carry no plane. Match the actual surface identity,
		// including bridge chains, instead of guessing from the player's level.
		for (Tile[][] level : tiles)
		{
			if (level == null || x < 0 || x >= level.length || level[x] == null
				|| z < 0 || z >= level[x].length)
				continue;
			for (Tile tile = level[x][z]; tile != null; tile = tile.getBridge())
				if (tile.getSceneTilePaint() == surface || tile.getSceneTileModel() == surface)
					return tile;
		}
		return null;
	}

	private boolean isVisibleCallbackObject(Scene scene, TileObject object)
	{
		if (object == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
			return true;
		int tileX = Math.floorDiv(object.getX(), Perspective.LOCAL_TILE_SIZE);
		int tileZ = Math.floorDiv(object.getY(), Perspective.LOCAL_TILE_SIZE);
		if (object instanceof GameObject)
		{
			var minimum = ((GameObject) object).getSceneMinLocation();
			if (minimum != null)
			{
				tileX = minimum.getX();
				tileZ = minimum.getY();
			}
		}
		int offset = WebGpuSceneVisibility.sceneOffset(scene);
		int x = tileX + offset;
		int z = tileZ + offset;
		Tile[][][] tiles = scene.getExtendedTiles();
		if (tiles == null)
			return true;
		for (Tile[][] level : tiles)
		{
			if (level == null || x < 0 || x >= level.length || level[x] == null
				|| z < 0 || z >= level[x].length)
				continue;
			for (Tile tile = level[x][z]; tile != null; tile = tile.getBridge())
			{
				if (tile.getWallObject() == object || tile.getDecorativeObject() == object
					|| tile.getGroundObject() == object)
					return callbackVisibility(scene).isVisible(scene, tile);
				GameObject[] gameObjects = tile.getGameObjects();
				if (gameObjects != null)
					for (GameObject candidate : gameObjects)
						if (candidate == object)
							return callbackVisibility(scene).isVisible(scene, tile);
			}
		}
		// Temporary client effects need not belong to a persistent scene tile.
		// Their engine callback remains authoritative when no owning tile exists.
		return true;
	}

	@Override
	public void draw(
		Projection projection,
		Scene scene,
		Renderable renderable,
		int orientation,
		int x,
		int y,
		int z,
		long hash
	) {
		Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
		if (model == null)
			return;
		model.calculateBoundsCylinder();
		client.checkClickbox(projection, model, orientation, x, y, z, hash);
		if (fishingSpotReplacer != null && fishingSpotReplacer.isOriginalFishingSpot(renderable))
			return;
		int renderX = x + sceneEpochOffsetX;
		int renderZ = z + sceneEpochOffsetZ;
		stageRenderableLights(renderable, orientation, renderX, y, renderZ);
		recordActorSubmission(renderable, model, x, z);
		if (renderable instanceof Actor || shouldStageFrameObject(hash))
			stageDynamicModel(
				renderable, hash, renderable, model, orientation, renderX, y, renderZ);
	}

	@Override
	public void drawDynamic(
		Projection worldProjection,
		Scene scene,
		TileObject tileObject,
		Renderable renderable,
		Model model,
		int orientation,
		int x,
		int y,
		int z
	) {
		if (model == null || !(renderable instanceof Actor) && !isVisibleCallbackObject(scene, tileObject))
			return;
		long hash = tileObject == null ? 0 : tileObject.getHash();
		model.calculateBoundsCylinder();
		client.checkClickbox(worldProjection, model, orientation, x, y, z, hash);
		if (fishingSpotReplacer != null && fishingSpotReplacer.isOriginalFishingSpot(renderable))
			return;
		int renderX = x + sceneEpochOffsetX;
		int renderZ = z + sceneEpochOffsetZ;
		stageRenderableLights(renderable, orientation, renderX, y, renderZ);
		recordActorSubmission(renderable, model, x, z);
		if (renderable instanceof Actor || shouldStageFrameObject(hash))
			stageDynamicModel(
				tileObject == null ? renderable : tileObject,
				hash, renderable, model, orientation, renderX, y, renderZ);
	}

	@Override
	public void drawTemp(
		Projection worldProjection,
		Scene scene,
		GameObject gameObject,
		Model model,
		int orientation,
		int x,
		int y,
		int z
	) {
		if (model == null)
			return;
		Renderable renderable = gameObject == null ? null : gameObject.getRenderable();
		if (!(renderable instanceof Actor) && !isVisibleCallbackObject(scene, gameObject))
			return;
		long hash = gameObject == null ? 0 : gameObject.getHash();
		model.calculateBoundsCylinder();
		client.checkClickbox(worldProjection, model, orientation, x, y, z, hash);
		int renderX = x + sceneEpochOffsetX;
		int renderZ = z + sceneEpochOffsetZ;
		stageRenderableLights(renderable, orientation, renderX, y, renderZ);
		recordActorSubmission(renderable, model, x, z);
		if (renderable instanceof Actor || shouldStageFrameObject(hash))
			stageDynamicModel(
				gameObject == null ? renderable : gameObject,
				hash, renderable, model, orientation, renderX, y, renderZ);
	}

	private void stageDynamicModel(
		Object owner,
		long stableHash,
		Renderable renderable,
		Model model,
		int orientation,
		int x,
		int y,
		int z)
	{
		int addedVertices = sceneBuffer.addModel(
			model, orientation, x, y, z, renderable instanceof Actor);
		if (addedVertices <= 0)
			return;

		// Actor instances are stable across frames and distinguish two actors that
		// happen to share the same client tag/model. Tile-object tags are the more
		// stable identity for non-actors, whose Renderable wrapper may be rebuilt.
		boolean compareOwnerIdentity = renderable instanceof Actor || stableHash == 0;
		currentDynamicSubmissions.add(new DynamicSubmission(
			owner, stableHash, compareOwnerIdentity, addedVertices,
			modelTopologyFingerprint(model)));
	}

	private void prepareDynamicHistoryBoundary()
	{
		int dynamicVertexCount = sceneBuffer.vertexCount() - sceneBuffer.staticVertexCount();
		boolean orderChanged = hasPreviousDynamicSubmissions
			&& previousDynamicVertexCount == dynamicVertexCount
			&& !sameDynamicSubmissionOrder(
				previousDynamicSubmissions, currentDynamicSubmissions);
		boolean temporalAccumulationActive = appliedDlssMode != DlssMode.OFF
			|| appliedNeuralRendering;
		if (orderChanged && temporalAccumulationActive)
		{
			// With the current native ABI, dynamic previous positions are ordinal.
			// A same-size reorder would therefore assign another actor's motion to a
			// model. The static revision is the existing hard history-boundary signal;
			// use it only for this ambiguous case. Count changes are already detected
			// natively, while normal animation keeps valid per-vertex motion.
			sceneBuffer.requestTemporalReset();
			log.debug("Reset WebGPU temporal history after dynamic submission order changed");
		}

		previousDynamicSubmissions.clear();
		previousDynamicSubmissions.addAll(currentDynamicSubmissions);
		previousDynamicVertexCount = dynamicVertexCount;
		hasPreviousDynamicSubmissions = true;
	}

	private static boolean sameDynamicSubmissionOrder(
		List<DynamicSubmission> previous,
		List<DynamicSubmission> current)
	{
		if (previous.size() != current.size())
			return false;
		for (int index = 0; index < previous.size(); index++)
			if (!previous.get(index).sameIdentityAndLayout(current.get(index)))
				return false;
		return true;
	}

	private static long modelTopologyFingerprint(Model model)
	{
		int[] faceA = model.getFaceIndices1();
		int[] faceB = model.getFaceIndices2();
		int[] faceC = model.getFaceIndices3();
		int[] colorC = model.getFaceColors3();
		short[] textures = model.getFaceTextures();
		byte[] transparencies = model.getFaceTransparencies();
		int faceCount = Math.min(
			model.getFaceCount(), Math.min(faceA.length, Math.min(faceB.length, faceC.length)));
		long hash = 0xcbf29ce484222325L;
		hash = mixFingerprint(hash, model.getVerticesX().length);
		hash = mixFingerprint(hash, faceCount);
		int step = Math.max(1, faceCount / 64);
		for (int face = 0; face < faceCount; face += step)
		{
			hash = mixFingerprint(hash, faceA[face]);
			hash = mixFingerprint(hash, faceB[face]);
			hash = mixFingerprint(hash, faceC[face]);
			hash = mixFingerprint(hash, colorC == null || face >= colorC.length ? 0 : colorC[face]);
			hash = mixFingerprint(hash, textures == null || face >= textures.length ? -1 : textures[face]);
			int transparency = transparencies == null || face >= transparencies.length
				? 0 : transparencies[face] & 0xff;
			hash = mixFingerprint(hash, transparency >= 253 ? 1 : 0);
		}
		return hash;
	}

	private static long mixFingerprint(long hash, int value)
	{
		hash ^= value & 0xffffffffL;
		return hash * 0x100000001b3L;
	}

	private static final class StaticSectorThreadFactory implements ThreadFactory
	{
		private int index;

		@Override
		public synchronized Thread newThread(Runnable task)
		{
			Thread thread = new Thread(task, "117hd-webgpu-sector-" + ++index);
			thread.setDaemon(true);
			thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
			return thread;
		}
	}

	/** Client-thread-only references and cursors for incremental API capture. */
	private static final class StaticCaptureJob
	{
		private final int generation;
		private final Scene scene;
		private final int worldViewId;
		private final int plane;
		private final int baseX;
		private final int baseY;
		private Tile[][][] tiles;
		private WebGpuSceneBuffer underwaterClassifier;
		private WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture;
		private final Set<Tile> underwaterCapturedTiles =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private List<StaticSectorCaptureWork> work = Collections.emptyList();
		private Map<Long, StaticSectorBuilder> builders = Collections.emptyMap();
		private final Set<Long> capturedObjects;
		private final Set<Tile> capturedTiles =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver;
		private final boolean smoothTerrainMaterials;
		private final float sceneCenterX;
		private final float sceneCenterZ;
		private final float captureRadius;
		private final float cameraX;
		private final float cameraY;
		private final float cameraZ;
		private final int epochOffsetX;
		private final int epochOffsetZ;
		private final int epochBaseX;
		private final int epochBaseY;
		private int sceneTileArrayOffset;
		private int scanWorkIndex;

		private StaticCaptureJob(
			int generation, Scene scene, int worldViewId, int plane,
			int baseX, int baseY,
			Tile[][][] tiles, WebGpuSceneBuffer underwaterClassifier,
			WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture,
			Set<Long> capturedObjects,
			WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver,
			boolean smoothTerrainMaterials,
			float sceneCenterX, float sceneCenterZ, float captureRadius,
			float cameraX, float cameraY, float cameraZ,
			int epochOffsetX, int epochOffsetZ,
			int epochBaseX, int epochBaseY)
		{
			this.generation = generation;
			this.scene = scene;
			this.worldViewId = worldViewId;
			this.plane = plane;
			this.baseX = baseX;
			this.baseY = baseY;
			this.tiles = tiles;
			this.underwaterClassifier = underwaterClassifier;
			this.underwaterCapture = underwaterCapture;
			this.capturedObjects = capturedObjects;
			this.groundTextureResolver = groundTextureResolver;
			this.smoothTerrainMaterials = smoothTerrainMaterials;
			this.sceneCenterX = sceneCenterX;
			this.sceneCenterZ = sceneCenterZ;
			this.captureRadius = captureRadius;
			this.cameraX = cameraX;
			this.cameraY = cameraY;
			this.cameraZ = cameraZ;
			this.epochOffsetX = epochOffsetX;
			this.epochOffsetZ = epochOffsetZ;
			this.epochBaseX = epochBaseX;
			this.epochBaseY = epochBaseY;
		}

		private void cancel()
		{
			tiles = null;
			underwaterClassifier = null;
			underwaterCapture = null;
			work = Collections.emptyList();
			builders = Collections.emptyMap();
		}

		private boolean matches(Scene currentScene, int currentGeneration, int currentPlane)
		{
			return generation == currentGeneration && scene == currentScene
				&& worldViewId == currentScene.getWorldViewId()
				&& plane == currentPlane
				&& baseX == currentScene.getBaseX() && baseY == currentScene.getBaseY();
		}
	}

	private static final class StaticSectorCaptureWork
	{
		private final StaticSectorBuilder builder;
		private final List<Tile> roots = new ArrayList<>();
		private final List<Tile> haloRoots = new ArrayList<>();
		private final Set<Tile> haloRootSet =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<Tile> capturedHaloTiles =
			Collections.newSetFromMap(new IdentityHashMap<>());
		private int rootIndex;
		private int haloRootIndex;
		private int scanPlane;
		private int scanColumn;
		private int scanRow;
		private boolean scanComplete;
		private int underwaterContextSlot;
		private WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture;

		private StaticSectorCaptureWork(int sectorX, int sectorZ)
		{
			builder = new StaticSectorBuilder(sectorX, sectorZ);
		}
	}

	private static final class StaticSectorBuilder
	{
		private final int sectorX;
		private final int sectorZ;
		private final List<StaticTerrainSnapshot> terrain = new ArrayList<>();
		private final List<StaticTerrainSnapshot> haloTerrain = new ArrayList<>();
		private final List<StaticModelSnapshot> models = new ArrayList<>();
		private final List<StaticLightSnapshot> lights = new ArrayList<>();
		private final Set<Long> objectHashes = new HashSet<>();

		private StaticSectorBuilder(int sectorX, int sectorZ)
		{
			this.sectorX = sectorX;
			this.sectorZ = sectorZ;
		}

		private boolean isEmpty()
		{
			return terrain.isEmpty() && models.isEmpty() && lights.isEmpty();
		}

		private StaticSectorSnapshot build(
			int generation,
			WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver,
			boolean smoothTerrainMaterials,
			WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture,
			float cameraX, float cameraY, float cameraZ,
			int epochOffsetX, int epochOffsetZ,
			int epochBaseX, int epochBaseY)
		{
			return new StaticSectorSnapshot(
				generation, sectorX, sectorZ,
				Collections.unmodifiableList(new ArrayList<>(terrain)),
				Collections.unmodifiableList(new ArrayList<>(haloTerrain)),
				Collections.unmodifiableList(new ArrayList<>(models)),
				Collections.unmodifiableList(new ArrayList<>(lights)),
				Collections.unmodifiableSet(new HashSet<>(objectHashes)),
				groundTextureResolver, smoothTerrainMaterials, underwaterCapture,
				cameraX, cameraY, cameraZ, epochOffsetX, epochOffsetZ,
				epochBaseX, epochBaseY);
		}
	}

	private static final class StaticSectorSnapshot
	{
		private final int generation;
		private final int sectorX;
		private final int sectorZ;
		private final long key;
		private final float centerX;
		private final float centerZ;
		private final List<StaticTerrainSnapshot> terrain;
		private final List<StaticTerrainSnapshot> haloTerrain;
		private final List<StaticModelSnapshot> models;
		private final List<StaticLightSnapshot> lights;
		private final Set<Long> objectHashes;
		private final WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver;
		private final boolean smoothTerrainMaterials;
		private final WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture;
		private final float cameraX;
		private final float cameraY;
		private final float cameraZ;
		private final int epochOffsetX;
		private final int epochOffsetZ;

		private StaticSectorSnapshot(
			int generation, int sectorX, int sectorZ,
			List<StaticTerrainSnapshot> terrain,
			List<StaticTerrainSnapshot> haloTerrain,
			List<StaticModelSnapshot> models,
			List<StaticLightSnapshot> lights,
			Set<Long> objectHashes,
			WebGpu117ResourceLoader.GroundTextureResolver groundTextureResolver,
			boolean smoothTerrainMaterials,
			WebGpuSceneBuffer.UnderwaterTerrainCapture underwaterCapture,
			float cameraX, float cameraY, float cameraZ,
			int epochOffsetX, int epochOffsetZ,
			int epochBaseX, int epochBaseY)
		{
			this.generation = generation;
			this.sectorX = sectorX;
			this.sectorZ = sectorZ;
			this.key = sectorKey(sectorX, sectorZ);
			this.centerX = ((long) sectorX * STATIC_SECTOR_SIZE
				+ STATIC_SECTOR_SIZE * .5f - epochBaseX) * Perspective.LOCAL_TILE_SIZE;
			this.centerZ = ((long) sectorZ * STATIC_SECTOR_SIZE
				+ STATIC_SECTOR_SIZE * .5f - epochBaseY) * Perspective.LOCAL_TILE_SIZE;
			this.terrain = terrain;
			this.haloTerrain = haloTerrain;
			this.models = models;
			this.lights = lights;
			this.objectHashes = objectHashes;
			this.groundTextureResolver = groundTextureResolver;
			this.smoothTerrainMaterials = smoothTerrainMaterials;
			this.underwaterCapture = underwaterCapture;
			this.cameraX = cameraX;
			this.cameraY = cameraY;
			this.cameraZ = cameraZ;
			this.epochOffsetX = epochOffsetX;
			this.epochOffsetZ = epochOffsetZ;
		}
	}

	private static final class StaticTerrainSnapshot
	{
		private final WebGpuSceneBuffer.CapturedTilePaint paint;
		private final WebGpuSceneBuffer.CapturedTileModel model;

		private StaticTerrainSnapshot(
			WebGpuSceneBuffer.CapturedTilePaint paint,
			WebGpuSceneBuffer.CapturedTileModel model)
		{
			this.paint = paint;
			this.model = model;
		}
	}

	private static final class StaticModelSnapshot
	{
		private final WebGpuSceneBuffer.CapturedModel model;
		private final int orientation;
		private final int x;
		private final int y;
		private final int z;

		private StaticModelSnapshot(
			WebGpuSceneBuffer.CapturedModel model,
			int orientation, int x, int y, int z)
		{
			this.model = model;
			this.orientation = orientation;
			this.x = x;
			this.y = y;
			this.z = z;
		}
	}

	private static final class StaticLightSnapshot
	{
		private final float x;
		private final float y;
		private final float z;
		private final float radius;
		private final float strength;
		private final float colorR;
		private final float colorG;
		private final float colorB;
		private final int animationType;
		private final float duration;
		private final float range;

		private StaticLightSnapshot(
			float x, float y, float z, WebGpu117ResourceLoader.HdLight light)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = light.radius;
			this.strength = light.strength;
			this.colorR = light.color.length > 0 ? light.color[0] : 1;
			this.colorG = light.color.length > 1 ? light.color[1] : colorR;
			this.colorB = light.color.length > 2 ? light.color[2] : colorG;
			this.animationType = "PULSE".equals(light.type) ? 1
				: "FLICKER".equals(light.type) ? 2 : 0;
			this.duration = light.duration;
			this.range = light.range;
		}

		private StaticLightSnapshot(
			StaticLightSnapshot source, float offsetX, float offsetY, float offsetZ)
		{
			this.x = source.x + offsetX;
			this.y = source.y + offsetY;
			this.z = source.z + offsetZ;
			this.radius = source.radius;
			this.strength = source.strength;
			this.colorR = source.colorR;
			this.colorG = source.colorG;
			this.colorB = source.colorB;
			this.animationType = source.animationType;
			this.duration = source.duration;
			this.range = source.range;
		}

		private float animatedStrength(long milliseconds)
		{
			return strength * animationMultiplier(milliseconds);
		}

		private float animatedRadius(long milliseconds)
		{
			float multiplier = animationMultiplier(milliseconds);
			return animationType == 2 ? radius * 1.5f : radius * multiplier;
		}

		private float animationMultiplier(long milliseconds)
		{
			float amount = range / 100f;
			if (amount == 0)
				return 1;
			if (animationType == 1 && duration > 0)
			{
				float phase = (milliseconds % Math.max(1L, (long) duration)) / duration;
				float triangle = 1 - 2 * Math.abs(phase - .5f);
				return Math.max(0, 1 + (2 * triangle - 1) * amount);
			}
			if (animationType == 2)
			{
				double time = milliseconds * .001;
				double noise = (Math.sin(time * 11) + Math.sin(time * 23) * .5
					+ Math.sin(time * 47) * .25) / 1.75;
				return Math.max(0, 1 + (float) noise * amount);
			}
			return 1;
		}

		private float[] color()
		{
			return new float[] { colorR, colorG, colorB };
		}
	}

	/** Immutable location of one committed sector inside the static prefix. */
	private static final class CommittedStaticSectorRange
	{
		private final long key;
		private final int sectorX;
		private final int sectorZ;
		private final float centerX;
		private final float centerZ;
		private final int firstVertex;
		private final int vertexCount;

		private CommittedStaticSectorRange(
			long key, int sectorX, int sectorZ, float centerX, float centerZ,
			int firstVertex, int vertexCount)
		{
			this.key = key;
			this.sectorX = sectorX;
			this.sectorZ = sectorZ;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.firstVertex = firstVertex;
			this.vertexCount = vertexCount;
		}

		private double distanceSquared(float x, float z)
		{
			double dx = centerX - x;
			double dz = centerZ - z;
			return dx * dx + dz * dz;
		}

		private boolean isWithin(float x, float z, float radius)
		{
			return Math.abs(centerX - x) <= radius
				&& Math.abs(centerZ - z) <= radius;
		}

		private CommittedStaticSectorRange withRange(
			int replacementFirstVertex, int replacementVertexCount)
		{
			return new CommittedStaticSectorRange(
				key, sectorX, sectorZ, centerX, centerZ,
				replacementFirstVertex, replacementVertexCount);
		}
	}

	/** Worker result adopted at the next render-thread scene boundary. */
	private static final class CompletedStaticCompaction
	{
		private final int generation;
		private final WebGpuSceneBuffer replacement;
		private final Map<Long, CommittedStaticSectorRange> ranges;
		private final int sourceSectorCount;
		private final int centerSectorX;
		private final int centerSectorZ;
		private final long buildMillis;
		private final Throwable failure;

		private CompletedStaticCompaction(
			int generation, WebGpuSceneBuffer replacement,
			Map<Long, CommittedStaticSectorRange> ranges,
			int sourceSectorCount, int centerSectorX, int centerSectorZ,
			long buildMillis, Throwable failure)
		{
			this.generation = generation;
			this.replacement = replacement;
			this.ranges = ranges;
			this.sourceSectorCount = sourceSectorCount;
			this.centerSectorX = centerSectorX;
			this.centerSectorZ = centerSectorZ;
			this.buildMillis = buildMillis;
			this.failure = failure;
		}
	}

	private static final class CompletedStaticSector
	{
		private final StaticSectorSnapshot snapshot;
		private final WebGpuSceneBuffer buffer;
		private final long buildMillis;

		private CompletedStaticSector(
			StaticSectorSnapshot snapshot, WebGpuSceneBuffer buffer, long buildMillis)
		{
			this.snapshot = snapshot;
			this.buffer = buffer;
			this.buildMillis = buildMillis;
		}
	}

	private static final class DynamicSubmission
	{
		private final Object owner;
		private final long stableHash;
		private final boolean compareOwnerIdentity;
		private final int vertexCount;
		private final long topologyFingerprint;

		private DynamicSubmission(
			Object owner,
			long stableHash,
			boolean compareOwnerIdentity,
			int vertexCount,
			long topologyFingerprint)
		{
			this.owner = owner;
			this.stableHash = stableHash;
			this.compareOwnerIdentity = compareOwnerIdentity;
			this.vertexCount = vertexCount;
			this.topologyFingerprint = topologyFingerprint;
		}

		private boolean sameIdentityAndLayout(DynamicSubmission other)
		{
			if (compareOwnerIdentity != other.compareOwnerIdentity
				|| vertexCount != other.vertexCount
				|| topologyFingerprint != other.topologyFingerprint)
				return false;
			return compareOwnerIdentity ? owner == other.owner : stableHash == other.stableHash;
		}
	}

	private boolean shouldStageFrameObject(long hash)
	{
		return hash == 0 || (!stagedStaticObjects.contains(hash) && stagedFrameObjects.add(hash));
	}

	@Override
	public void postDrawScene()
	{
		traceCallback("postDrawScene");
	}

	private void recordActorSubmission(Renderable renderable, Model model, int x, int z)
	{
		if (!(renderable instanceof Actor))
			return;
		actorCallbackCount++;
		if (renderable == client.getLocalPlayer())
		{
			traceCallback("localPlayer");
			localPlayerSubmitted = true;
			submittedPlayerX = x;
			submittedPlayerZ = z;
			submittedPlayerModelHash = modelFingerprint(model);
		}
	}

	private static long modelFingerprint(Model model)
	{
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		int count = Math.min(x.length, Math.min(y.length, z.length));
		long hash = 0xcbf29ce484222325L;
		int step = Math.max(1, count / 32);
		for (int vertex = 0; vertex < count; vertex += step)
		{
			hash ^= Float.floatToRawIntBits(x[vertex]);
			hash *= 0x100000001b3L;
			hash ^= Float.floatToRawIntBits(y[vertex]);
			hash *= 0x100000001b3L;
			hash ^= Float.floatToRawIntBits(z[vertex]);
			hash *= 0x100000001b3L;
		}
		return hash;
	}

	private void traceCallback(String callback)
	{
		if (callbackTrace.length() >= 160)
			return;
		if (callbackTrace.length() > 0)
			callbackTrace.append('>');
		callbackTrace.append(callback);
	}

	private void logMotionDiagnostics()
	{
		Actor player = client.getLocalPlayer();
		var local = player == null ? null : player.getLocalLocation();
		var destination = client.getLocalDestinationLocation();
		long javaHash = sceneBuffer.dynamicFingerprint();
		long nativeHash = runtime.lastDynamicHash();
		log.info(
			"WebGPU motion diag: frame={} order={} actorCallbacks={} playerLocal={},{} " +
				"destination={},{} animation={}/{} submitted={} at {},{} model={} " +
				"dynamicVertices={} javaHash={} nativeHash={} match={}",
			frameSequence, callbackTrace, actorCallbackCount,
			local == null ? -1 : local.getX(), local == null ? -1 : local.getY(),
			destination == null ? -1 : destination.getX(), destination == null ? -1 : destination.getY(),
			player == null ? -1 : player.getAnimation(), player == null ? -1 : player.getPoseAnimationFrame(),
			localPlayerSubmitted, submittedPlayerX, submittedPlayerZ,
			Long.toUnsignedString(submittedPlayerModelHash, 16),
			sceneBuffer.vertexCount() - sceneBuffer.staticVertexCount(),
			Long.toUnsignedString(javaHash, 16), Long.toUnsignedString(nativeHash, 16),
			javaHash == nativeHash);
	}

	@Override
	public void swapScene(Scene scene) {
		// The next draw decides whether this is an overlapping 8-tile rebase or a
		// true world/plane/instance transition. Preserve immutable sector work in
		// the meantime; only live Tile references from the outgoing Scene are stale.
		softInvalidateStaticCapture();
	}

	private int[] physicalCanvasDimensions() {
		AffineTransform transform = canvas.getGraphicsConfiguration() == null
			? new AffineTransform()
			: canvas.getGraphicsConfiguration().getDefaultTransform();
		return new int[] {
			Math.max(1, (int) Math.ceil(canvas.getWidth() * transform.getScaleX())),
			Math.max(1, (int) Math.ceil(canvas.getHeight() * transform.getScaleY()))
		};
	}
}
