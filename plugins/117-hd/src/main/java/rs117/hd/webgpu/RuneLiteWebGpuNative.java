package rs117.hd.webgpu;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;

interface RuneLiteWebGpuNative extends Library {
	int rlw_create(Pointer canvasHwnd, int width, int height);
	void rlw_resize(int width, int height);
	void rlw_set_vsync(int enabled);
	void rlw_set_rtx_enabled(int enabled);
	void rlw_set_rtx_dynamic_shadows(int enabled);
	void rlw_set_rtx_reflections(int enabled);
	int rlw_set_visual_settings(VisualSettings settings);
	int rlw_clear(float red, float green, float blue, float alpha);
	int rlw_present_bgra(ByteBuffer pixels, int width, int height);
	int rlw_upload_texture_atlas(
		int[] rgbaPixels,
		int[] vanillaRgbaPixels,
		int[] normalPixels,
		float[] materialParameters,
		int width,
		int height,
		int tileSize,
		int textureCount
	);
	int rlw_set_static_sector_manifest(
		long[] sectorIds,
		int[] firstVertices,
		int[] vertexCounts,
		int[] flags,
		int count
	);
	int rlw_present_scene(
		ByteBuffer packedVertices,
		int vertexCount,
		int staticVertexCount,
		long staticRevision,
		float[] lightRecords,
		int lightCount,
		float[] viewProjection,
		float[] cameraViewToClip,
		float[] cameraPosition,
		int viewportX,
		int viewportY,
		int viewportWidth,
		int viewportHeight,
		int clearColor,
		ByteBuffer interfacePixels,
		int interfaceWidth,
		int interfaceHeight
	);
	void rlw_destroy();
	Pointer rlw_last_error();
	Pointer rlw_backend_name();
	int rlw_get_capabilities(GpuCapabilities capabilities);
	int rlw_get_dlss_runtime_status(DlssRuntimeStatus status);
	int rlw_request_features(GpuFeatureRequest request);
	long rlw_last_dynamic_hash();

	@Structure.FieldOrder({
		"structSize", "vendorId", "deviceId", "isRtx", "streamlinePresent",
		"streamlineInitialized", "vulkanAttached", "dlssSuperResolution",
		"dlssFrameGeneration", "dlssRayReconstruction", "reflex", "adapterName",
		"status", "nativeRayTracing", "rayQuery", "dlssNeuralRendering",
		"dlssNeuralRenderingApiLoaded", "dlssNeuralRenderingDllLoaded",
		"dlssNeuralRenderingDirectSupported", "dlssNeuralRenderingNgxMappingPatched",
		"dlssFrameGenerationPresentHook", "dlssRayReconstructionGuides",
		"dlssFrameGenerationHardware"
	})
	class GpuCapabilities extends Structure {
		public int structSize;
		public int vendorId;
		public int deviceId;
		public int isRtx;
		public int streamlinePresent;
		public int streamlineInitialized;
		public int vulkanAttached;
		public int dlssSuperResolution;
		public int dlssFrameGeneration;
		public int dlssRayReconstruction;
		public int reflex;
		public byte[] adapterName = new byte[128];
		public byte[] status = new byte[256];
		public int nativeRayTracing;
		public int rayQuery;
		public int dlssNeuralRendering;
		public int dlssNeuralRenderingApiLoaded;
		public int dlssNeuralRenderingDllLoaded;
		public int dlssNeuralRenderingDirectSupported;
		public int dlssNeuralRenderingNgxMappingPatched;
		public int dlssFrameGenerationPresentHook;
		public int dlssRayReconstructionGuides;
		public int dlssFrameGenerationHardware;

		GpuCapabilities() {
			structSize = size();
		}

		String adapterName() {
			return decode(adapterName);
		}

		String status() {
			return decode(status);
		}

		private static String decode(byte[] bytes) {
			int length = 0;
			while (length < bytes.length && bytes[length] != 0)
				length++;
			return new String(bytes, 0, length, StandardCharsets.UTF_8);
		}
	}

	@Structure.FieldOrder({
		"structSize", "requestedMode", "superResolutionRequested",
		"superResolutionConfigured", "superResolutionActive",
		"rayReconstructionRequested", "rayReconstructionConfigured",
		"rayReconstructionActive", "neuralRenderingRequested",
		"neuralRenderingConfigured", "neuralRenderingActive", "renderWidth",
		"renderHeight", "outputWidth", "outputHeight",
		"superResolutionEvaluations", "superResolutionFailures",
		"rayReconstructionEvaluations", "rayReconstructionFailures",
		"neuralRenderingEvaluations", "neuralRenderingFailures",
		"superResolutionLastResult", "rayReconstructionLastResult",
		"neuralRenderingLastResult", "superResolutionReason",
		"rayReconstructionReason", "neuralRenderingReason"
	})
	class DlssRuntimeStatus extends Structure {
		public int structSize;
		public int requestedMode;
		public int superResolutionRequested;
		public int superResolutionConfigured;
		public int superResolutionActive;
		public int rayReconstructionRequested;
		public int rayReconstructionConfigured;
		public int rayReconstructionActive;
		public int neuralRenderingRequested;
		public int neuralRenderingConfigured;
		public int neuralRenderingActive;
		public int renderWidth;
		public int renderHeight;
		public int outputWidth;
		public int outputHeight;
		public long superResolutionEvaluations;
		public long superResolutionFailures;
		public long rayReconstructionEvaluations;
		public long rayReconstructionFailures;
		public long neuralRenderingEvaluations;
		public long neuralRenderingFailures;
		public int superResolutionLastResult;
		public int rayReconstructionLastResult;
		public int neuralRenderingLastResult;
		public byte[] superResolutionReason = new byte[256];
		public byte[] rayReconstructionReason = new byte[256];
		public byte[] neuralRenderingReason = new byte[256];

		DlssRuntimeStatus() {
			structSize = size();
		}

		String superResolutionReason() {
			return decode(superResolutionReason);
		}

		String rayReconstructionReason() {
			return decode(rayReconstructionReason);
		}

		String neuralRenderingReason() {
			return decode(neuralRenderingReason);
		}

		private static String decode(byte[] bytes) {
			int length = 0;
			while (length < bytes.length && bytes[length] != 0)
				length++;
			return new String(bytes, 0, length, StandardCharsets.UTF_8);
		}
	}

	@Structure.FieldOrder({
		"structSize", "dlssMode", "outputWidth", "outputHeight", "preExposure",
		"exposureScale", "colorBuffersHdr", "autoExposure", "alphaUpscaling",
		"frameGeneration", "rayReconstruction", "neuralRendering"
	})
	class GpuFeatureRequest extends Structure {
		public int structSize;
		public int dlssMode;
		public int outputWidth;
		public int outputHeight;
		public float preExposure = 1.0f;
		public float exposureScale = 1.0f;
		public int colorBuffersHdr = 1;
		public int autoExposure;
		public int alphaUpscaling;
		public int frameGeneration;
		public int rayReconstruction;
		public int neuralRendering;

		GpuFeatureRequest() {
			structSize = size();
		}
	}

	@Structure.FieldOrder({
		"structSize", "styleMode", "renderScale", "normalStrength", "saturation",
		"contrast", "ambientIntensity", "exposure", "timeOfDay", "texturesEnabled",
		"weatherMode", "weatherIntensity", "windStrength", "waterQuality",
		"waterWaveStrength", "waterClarity", "rtxShadowStrength",
		"rtxReflectionStrength", "rtxReflectionQuality", "dlssSharpness",
		"smoothModelShading", "modelSmoothingStrength"
	})
	class VisualSettings extends Structure {
		public int structSize;
		public int styleMode;
		public float renderScale;
		public float normalStrength;
		public float saturation;
		public float contrast;
		public float ambientIntensity;
		public float exposure;
		public float timeOfDay;
		public int texturesEnabled;
		public int weatherMode;
		public float weatherIntensity;
		public float windStrength;
		public int waterQuality;
		public float waterWaveStrength;
		public float waterClarity;
		public float rtxShadowStrength;
		public float rtxReflectionStrength;
		public int rtxReflectionQuality;
		public float dlssSharpness;
		public int smoothModelShading;
		public float modelSmoothingStrength;

		VisualSettings() {
			structSize = size();
		}
	}
}
