package rs117.hd.webgpu;

public final class WebGpuCapabilities {
	public final int vendorId;
	public final int deviceId;
	public final boolean rtx;
	public final boolean streamlinePresent;
	public final boolean streamlineInitialized;
	public final boolean vulkanAttached;
	public final boolean dlssSuperResolution;
	public final boolean frameGeneration;
	public final boolean frameGenerationHardware;
	public final boolean frameGenerationPresentHook;
	public final boolean rayReconstruction;
	public final boolean rayReconstructionRuntime;
	public final boolean rayReconstructionGuides;
	public final boolean neuralRendering;
	public final boolean neuralRenderingApiLoaded;
	public final boolean neuralRenderingDllLoaded;
	public final boolean neuralRenderingDirectSupported;
	public final boolean neuralRenderingNgxMappingPatched;
	public final boolean reflex;
	public final boolean rayQuery;
	public final String adapterName;
	public final String status;

	WebGpuCapabilities(RuneLiteWebGpuNative.GpuCapabilities nativeCapabilities) {
		vendorId = nativeCapabilities.vendorId;
		deviceId = nativeCapabilities.deviceId;
		rtx = nativeCapabilities.isRtx != 0;
		streamlinePresent = nativeCapabilities.streamlinePresent != 0;
		streamlineInitialized = nativeCapabilities.streamlineInitialized != 0;
		vulkanAttached = nativeCapabilities.vulkanAttached != 0;
		dlssSuperResolution = nativeCapabilities.dlssSuperResolution != 0;
		frameGenerationHardware = nativeCapabilities.dlssFrameGenerationHardware != 0;
		frameGenerationPresentHook = nativeCapabilities.dlssFrameGenerationPresentHook != 0;
		frameGeneration = nativeCapabilities.dlssFrameGeneration != 0
			&& frameGenerationHardware && frameGenerationPresentHook;
		rayReconstructionRuntime = nativeCapabilities.dlssRayReconstruction != 0;
		rayReconstructionGuides = nativeCapabilities.dlssRayReconstructionGuides != 0;
		rayReconstruction = rayReconstructionRuntime && rayReconstructionGuides;
		neuralRenderingApiLoaded = nativeCapabilities.dlssNeuralRenderingApiLoaded != 0;
		neuralRenderingDllLoaded = nativeCapabilities.dlssNeuralRenderingDllLoaded != 0;
		neuralRenderingDirectSupported = nativeCapabilities.dlssNeuralRenderingDirectSupported != 0;
		neuralRenderingNgxMappingPatched = nativeCapabilities.dlssNeuralRenderingNgxMappingPatched != 0;
		// A DLL on disk is not a capability. Only expose DLSS 5 when Streamline
		// accepted feature 1004 and its callable API was imported successfully.
		neuralRendering = nativeCapabilities.dlssNeuralRendering != 0
			&& neuralRenderingApiLoaded && neuralRenderingDllLoaded;
		reflex = nativeCapabilities.reflex != 0;
		rayQuery = nativeCapabilities.rayQuery != 0;
		adapterName = nativeCapabilities.adapterName();
		status = nativeCapabilities.status();
	}
}
