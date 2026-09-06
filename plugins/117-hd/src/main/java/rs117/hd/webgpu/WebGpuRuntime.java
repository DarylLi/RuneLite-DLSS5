package rs117.hd.webgpu;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.awt.Canvas;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Owns the Java side of the ThreeBrowser WebGPU runtime.
 *
 * This is deliberately independent of the OpenGL renderer. The eventual WebGPU
 * renderer can consume 117 HD scene data without creating an OpenGL context.
 */
public final class WebGpuRuntime implements AutoCloseable {
	private final RuneLiteWebGpuNative nativeApi;
	private boolean attached;
	private ByteBuffer interfaceUploadBuffer;

	public WebGpuRuntime() {
		this(WebGpuNativeLibrary.load());
	}

	WebGpuRuntime(RuneLiteWebGpuNative nativeApi) {
		this.nativeApi = Objects.requireNonNull(nativeApi);
	}

	public void attach(Canvas canvas, int width, int height) {
		Objects.requireNonNull(canvas, "canvas");
		if (!System.getProperty("os.name", "").startsWith("Windows"))
			throw new UnsupportedOperationException("The current ThreeBrowserNVNGX bridge is Windows-only");
		Pointer hwnd = Native.getComponentPointer(canvas);
		if (hwnd == null || nativeApi.rlw_create(hwnd, width, height) == 0)
			throw failure("Unable to create WebGPU on the RuneLite canvas");
		attached = true;
	}

	public WebGpuCapabilities capabilities() {
		RuneLiteWebGpuNative.GpuCapabilities capabilities = new RuneLiteWebGpuNative.GpuCapabilities();
		capabilities.write();
		if (nativeApi.rlw_get_capabilities(capabilities) == 0)
			throw failure("Unable to query WebGPU capabilities");
		capabilities.read();
		return new WebGpuCapabilities(capabilities);
	}

	public WebGpuDlssStatus dlssStatus() {
		RuneLiteWebGpuNative.DlssRuntimeStatus status =
			new RuneLiteWebGpuNative.DlssRuntimeStatus();
		status.write();
		if (nativeApi.rlw_get_dlss_runtime_status(status) == 0)
			throw failure("Unable to query the effective DLSS state");
		status.read();
		return new WebGpuDlssStatus(status);
	}

	public void requestDlss(
		DlssMode mode,
		int outputWidth,
		int outputHeight,
		boolean frameGeneration,
		boolean rayReconstruction,
		boolean neuralRendering
	) {
		if (outputWidth <= 0 || outputHeight <= 0)
			throw new IllegalArgumentException("DLSS output dimensions must be positive");
		if (neuralRendering && mode != DlssMode.OFF)
			throw new IllegalArgumentException(
				"DLSS 5 Neural Rendering and DLSS Super Resolution cannot be evaluated in the same frame");
		RuneLiteWebGpuNative.GpuFeatureRequest request = new RuneLiteWebGpuNative.GpuFeatureRequest();
		request.dlssMode = Objects.requireNonNull(mode).nativeValue;
		request.outputWidth = outputWidth;
		request.outputHeight = outputHeight;
		request.frameGeneration = frameGeneration ? 1 : 0;
		request.rayReconstruction = rayReconstruction ? 1 : 0;
		request.neuralRendering = neuralRendering ? 1 : 0;
		request.write();
		if (nativeApi.rlw_request_features(request) == 0)
			throw failure("The native runtime rejected the DLSS feature request");
	}

	public void resize(int width, int height) {
		if (width > 0 && height > 0)
			nativeApi.rlw_resize(width, height);
	}

	public void setVsync(boolean enabled) {
		nativeApi.rlw_set_vsync(enabled ? 1 : 0);
	}

	public void setRtxEnabled(boolean enabled) {
		nativeApi.rlw_set_rtx_enabled(enabled ? 1 : 0);
	}

	public void setRtxDynamicShadows(boolean enabled) {
		nativeApi.rlw_set_rtx_dynamic_shadows(enabled ? 1 : 0);
	}

	public void setRtxReflections(boolean enabled) {
		nativeApi.rlw_set_rtx_reflections(enabled ? 1 : 0);
	}

	public void setVisualSettings(WebGpuSettings settings) {
		Objects.requireNonNull(settings, "settings");
		RuneLiteWebGpuNative.VisualSettings nativeSettings = new RuneLiteWebGpuNative.VisualSettings();
		nativeSettings.styleMode = settings.styleMode.nativeValue;
		nativeSettings.renderScale = settings.renderScalePercent / 100.0f;
		nativeSettings.normalStrength = settings.normalStrength;
		nativeSettings.saturation = settings.saturation;
		nativeSettings.contrast = settings.contrast;
		nativeSettings.ambientIntensity = settings.ambientIntensity;
		nativeSettings.exposure = settings.exposure;
		nativeSettings.timeOfDay = settings.timeOfDay;
		nativeSettings.texturesEnabled = settings.texturesEnabled ? 1 : 0;
		nativeSettings.weatherMode = settings.weatherMode.nativeValue;
		nativeSettings.weatherIntensity = settings.weatherIntensityPercent / 100.0f;
		nativeSettings.windStrength = settings.windStrengthPercent / 100.0f;
		nativeSettings.waterQuality = settings.waterQuality.nativeValue;
		nativeSettings.waterWaveStrength = settings.waterWaveStrength;
		nativeSettings.waterClarity = settings.waterClarityPercent / 100.0f;
		nativeSettings.rtxShadowStrength = settings.rtxShadowStrengthPercent / 100.0f;
		nativeSettings.rtxReflectionStrength = settings.rtxReflectionStrengthPercent / 100.0f;
		nativeSettings.rtxReflectionQuality = settings.rtxReflectionQuality.nativeValue;
		nativeSettings.dlssSharpness = settings.sharpnessPercent / 100.0f;
		nativeSettings.smoothModelShading = settings.smoothModelShading ? 1 : 0;
		nativeSettings.modelSmoothingStrength =
			settings.modelSmoothingStrengthPercent / 100.0f;
		nativeSettings.write();
		if (nativeApi.rlw_set_visual_settings(nativeSettings) == 0)
			throw failure("The native runtime rejected the visual settings");
	}

	public long lastDynamicHash() {
		return nativeApi.rlw_last_dynamic_hash();
	}

	public void clear(float red, float green, float blue, float alpha) {
		if (nativeApi.rlw_clear(red, green, blue, alpha) == 0)
			throw failure("Unable to present the WebGPU frame");
	}

	public void presentBgra(int[] pixels, int width, int height) {
		Objects.requireNonNull(pixels, "pixels");
		if (width <= 0 || height <= 0 || pixels.length < width * height)
			throw new IllegalArgumentException("Invalid RuneLite interface buffer dimensions");
		if (nativeApi.rlw_present_bgra(packInterfacePixels(pixels, width, height), width, height) == 0)
			throw failure("Unable to composite the RuneLite interface through WebGPU");
	}

	public void uploadTextureAtlas(
		int[] rgbaPixels,
		int[] vanillaRgbaPixels,
		int[] normalPixels,
		float[] materialParameters,
		int width,
		int height,
		int tileSize,
		int textureCount
	) {
		Objects.requireNonNull(rgbaPixels, "rgbaPixels");
		Objects.requireNonNull(vanillaRgbaPixels, "vanillaRgbaPixels");
		Objects.requireNonNull(normalPixels, "normalPixels");
		Objects.requireNonNull(materialParameters, "materialParameters");
		if (width <= 0 || height <= 0 || tileSize <= 0 || textureCount <= 0
			|| rgbaPixels.length < width * height || vanillaRgbaPixels.length < width * height
			|| normalPixels.length < width * height
			|| materialParameters.length < textureCount * 4)
			throw new IllegalArgumentException("Invalid RuneLite texture atlas dimensions");
		if (nativeApi.rlw_upload_texture_atlas(
			rgbaPixels, vanillaRgbaPixels, normalPixels, materialParameters,
			width, height, tileSize, textureCount) == 0)
			throw failure("Unable to upload the RuneLite texture atlas");
	}

	/**
	 * Publishes the stable world-sector identity and packed vertex range used by
	 * both raster culling and the segmented RTX scene. This is intentionally a
	 * small side-band call, so the per-frame vertex upload ABI stays unchanged.
	 */
	public void setStaticSectorManifest(
		long[] sectorIds,
		int[] firstVertices,
		int[] vertexCounts,
		int[] flags
	) {
		int count = sectorIds == null ? 0 : sectorIds.length;
		if (count == 0) {
			if (nativeApi.rlw_set_static_sector_manifest(null, null, null, null, 0) == 0)
				throw failure("Unable to clear the WebGPU static-sector manifest");
			return;
		}
		Objects.requireNonNull(firstVertices, "firstVertices");
		Objects.requireNonNull(vertexCounts, "vertexCounts");
		Objects.requireNonNull(flags, "flags");
		if (firstVertices.length != count || vertexCounts.length != count
			|| flags.length != count)
			throw new IllegalArgumentException("Static-sector manifest arrays must have equal lengths");
		if (nativeApi.rlw_set_static_sector_manifest(
			sectorIds, firstVertices, vertexCounts, flags, count) == 0)
			throw failure("Unable to publish the WebGPU static-sector manifest");
	}

	public void presentScene(
		WebGpuSceneBuffer scene,
		float[] viewProjection,
		float[] cameraViewToClip,
		float[] cameraPosition,
		int viewportX,
		int viewportY,
		int viewportWidth,
		int viewportHeight,
		int clearColor,
		int[] interfacePixels,
		int interfaceWidth,
		int interfaceHeight
	) {
		Objects.requireNonNull(scene, "scene");
		Objects.requireNonNull(viewProjection, "viewProjection");
		Objects.requireNonNull(cameraViewToClip, "cameraViewToClip");
		Objects.requireNonNull(cameraPosition, "cameraPosition");
		Objects.requireNonNull(interfacePixels, "interfacePixels");
		if (viewProjection.length < 16 || cameraViewToClip.length < 16 || cameraPosition.length < 3)
			throw new IllegalArgumentException("View-projection, camera projection and position are required");
		if (nativeApi.rlw_present_scene(
			scene.packedVertices(), scene.vertexCount(), scene.staticVertexCount(),
			scene.staticRevision(), scene.lightRecords(),
			scene.lightCount(), viewProjection,
			cameraViewToClip, cameraPosition,
			viewportX, viewportY, viewportWidth, viewportHeight, clearColor,
			packInterfacePixels(interfacePixels, interfaceWidth, interfaceHeight),
			interfaceWidth, interfaceHeight) == 0)
			throw failure("Unable to render the RuneLite WebGPU scene");
	}

	@Override
	public void close() {
		if (attached) {
			attached = false;
			nativeApi.rlw_destroy();
		}
	}

	private IllegalStateException failure(String prefix) {
		Pointer error = nativeApi.rlw_last_error();
		String message = error == null ? "" : error.getString(0);
		return new IllegalStateException(message.isBlank() ? prefix : prefix + ": " + message);
	}

	private ByteBuffer packInterfacePixels(int[] pixels, int width, int height)
	{
		int pixelCount = Math.multiplyExact(width, height);
		int byteCount = Math.multiplyExact(pixelCount, Integer.BYTES);
		if (interfaceUploadBuffer == null || interfaceUploadBuffer.capacity() < byteCount)
			interfaceUploadBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder());
		interfaceUploadBuffer.clear();
		interfaceUploadBuffer.asIntBuffer().put(pixels, 0, pixelCount);
		interfaceUploadBuffer.limit(byteCount);
		return interfaceUploadBuffer;
	}
}
