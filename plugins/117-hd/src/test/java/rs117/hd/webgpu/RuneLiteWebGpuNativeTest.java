package rs117.hd.webgpu;

import com.sun.jna.Structure;
import java.lang.reflect.Method;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuneLiteWebGpuNativeTest {
	@Test
	public void capabilityLayoutMatchesNativeAbi() throws ReflectiveOperationException {
		RuneLiteWebGpuNative.GpuCapabilities capabilities =
			new RuneLiteWebGpuNative.GpuCapabilities();
		assertEquals(468, capabilities.size());
		assertEquals(468, capabilities.structSize);
		assertOffset(capabilities, "adapterName", 44);
		assertOffset(capabilities, "status", 172);
		assertOffset(capabilities, "nativeRayTracing", 428);
		assertOffset(capabilities, "rayQuery", 432);
		assertOffset(capabilities, "dlssNeuralRendering", 436);
		assertOffset(capabilities, "dlssNeuralRenderingApiLoaded", 440);
		assertOffset(capabilities, "dlssNeuralRenderingDllLoaded", 444);
		assertOffset(capabilities, "dlssNeuralRenderingDirectSupported", 448);
		assertOffset(capabilities, "dlssNeuralRenderingNgxMappingPatched", 452);
		assertOffset(capabilities, "dlssFrameGenerationPresentHook", 456);
		assertOffset(capabilities, "dlssRayReconstructionGuides", 460);
		assertOffset(capabilities, "dlssFrameGenerationHardware", 464);
	}

	@Test
	public void featureRequestLayoutMatchesNativeAbi() throws ReflectiveOperationException {
		RuneLiteWebGpuNative.GpuFeatureRequest request =
			new RuneLiteWebGpuNative.GpuFeatureRequest();
		assertEquals(48, request.size());
		assertEquals(48, request.structSize);
		assertOffset(request, "preExposure", 16);
		assertOffset(request, "exposureScale", 20);
		assertOffset(request, "colorBuffersHdr", 24);
		assertOffset(request, "frameGeneration", 36);
		assertOffset(request, "rayReconstruction", 40);
		assertOffset(request, "neuralRendering", 44);
	}

	@Test
	public void visualSettingsLayoutMatchesNativeAbi() throws ReflectiveOperationException {
		RuneLiteWebGpuNative.VisualSettings settings =
			new RuneLiteWebGpuNative.VisualSettings();
		assertEquals(88, settings.size());
		assertEquals(88, settings.structSize);
		assertOffset(settings, "renderScale", 8);
		assertOffset(settings, "timeOfDay", 32);
		assertOffset(settings, "texturesEnabled", 36);
		assertOffset(settings, "weatherMode", 40);
		assertOffset(settings, "weatherIntensity", 44);
		assertOffset(settings, "windStrength", 48);
		assertOffset(settings, "waterQuality", 52);
		assertOffset(settings, "waterWaveStrength", 56);
		assertOffset(settings, "waterClarity", 60);
		assertOffset(settings, "rtxShadowStrength", 64);
		assertOffset(settings, "rtxReflectionStrength", 68);
		assertOffset(settings, "rtxReflectionQuality", 72);
		assertOffset(settings, "dlssSharpness", 76);
		assertOffset(settings, "smoothModelShading", 80);
		assertOffset(settings, "modelSmoothingStrength", 84);
	}

	@Test
	public void runtimeStatusLayoutMatchesNativeAbi() throws ReflectiveOperationException {
		RuneLiteWebGpuNative.DlssRuntimeStatus status =
			new RuneLiteWebGpuNative.DlssRuntimeStatus();
		assertEquals(896, status.size());
		assertEquals(896, status.structSize);
		assertOffset(status, "renderWidth", 44);
		assertOffset(status, "outputHeight", 56);
		// The native uint64_t counters start after four bytes of alignment padding.
		assertOffset(status, "superResolutionEvaluations", 64);
		assertOffset(status, "superResolutionFailures", 72);
		assertOffset(status, "rayReconstructionEvaluations", 80);
		assertOffset(status, "rayReconstructionFailures", 88);
		assertOffset(status, "neuralRenderingEvaluations", 96);
		assertOffset(status, "neuralRenderingFailures", 104);
		assertOffset(status, "superResolutionLastResult", 112);
		assertOffset(status, "rayReconstructionLastResult", 116);
		assertOffset(status, "neuralRenderingLastResult", 120);
		assertOffset(status, "superResolutionReason", 124);
		assertOffset(status, "rayReconstructionReason", 380);
		assertOffset(status, "neuralRenderingReason", 636);
	}

	// Expected offsets come from native/include/runelite_webgpu.h. A same-size
	// field reorder can break the ABI too, so checking only total sizes is unsafe.
	private static void assertOffset(Structure structure, String field, int expected)
		throws ReflectiveOperationException {
		Method fieldOffset = Structure.class.getDeclaredMethod("fieldOffset", String.class);
		fieldOffset.setAccessible(true);
		assertEquals(field, expected, ((Number) fieldOffset.invoke(structure, field)).intValue());
	}
}
