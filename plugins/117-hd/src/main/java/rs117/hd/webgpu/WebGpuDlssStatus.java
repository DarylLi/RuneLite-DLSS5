package rs117.hd.webgpu;

/** Effective native DLSS state for the active RuneLite viewport. */
public final class WebGpuDlssStatus
{
	public final DlssMode requestedMode;
	public final boolean superResolutionRequested;
	public final boolean superResolutionConfigured;
	public final boolean superResolutionActive;
	public final boolean rayReconstructionRequested;
	public final boolean rayReconstructionConfigured;
	public final boolean rayReconstructionActive;
	public final boolean neuralRenderingRequested;
	public final boolean neuralRenderingConfigured;
	public final boolean neuralRenderingActive;
	public final int renderWidth;
	public final int renderHeight;
	public final int outputWidth;
	public final int outputHeight;
	public final long superResolutionEvaluations;
	public final long superResolutionFailures;
	public final long rayReconstructionEvaluations;
	public final long rayReconstructionFailures;
	public final long neuralRenderingEvaluations;
	public final long neuralRenderingFailures;
	public final int superResolutionLastResult;
	public final int rayReconstructionLastResult;
	public final int neuralRenderingLastResult;
	public final String superResolutionReason;
	public final String rayReconstructionReason;
	public final String neuralRenderingReason;

	WebGpuDlssStatus(RuneLiteWebGpuNative.DlssRuntimeStatus nativeStatus)
	{
		DlssMode[] modes = DlssMode.values();
		requestedMode = nativeStatus.requestedMode >= 0
			&& nativeStatus.requestedMode < modes.length
			? modes[nativeStatus.requestedMode]
			: DlssMode.OFF;
		superResolutionRequested = nativeStatus.superResolutionRequested != 0;
		superResolutionConfigured = nativeStatus.superResolutionConfigured != 0;
		superResolutionActive = nativeStatus.superResolutionActive != 0;
		rayReconstructionRequested = nativeStatus.rayReconstructionRequested != 0;
		rayReconstructionConfigured = nativeStatus.rayReconstructionConfigured != 0;
		rayReconstructionActive = nativeStatus.rayReconstructionActive != 0;
		neuralRenderingRequested = nativeStatus.neuralRenderingRequested != 0;
		neuralRenderingConfigured = nativeStatus.neuralRenderingConfigured != 0;
		neuralRenderingActive = nativeStatus.neuralRenderingActive != 0;
		renderWidth = nativeStatus.renderWidth;
		renderHeight = nativeStatus.renderHeight;
		outputWidth = nativeStatus.outputWidth;
		outputHeight = nativeStatus.outputHeight;
		superResolutionEvaluations = nativeStatus.superResolutionEvaluations;
		superResolutionFailures = nativeStatus.superResolutionFailures;
		rayReconstructionEvaluations = nativeStatus.rayReconstructionEvaluations;
		rayReconstructionFailures = nativeStatus.rayReconstructionFailures;
		neuralRenderingEvaluations = nativeStatus.neuralRenderingEvaluations;
		neuralRenderingFailures = nativeStatus.neuralRenderingFailures;
		superResolutionLastResult = nativeStatus.superResolutionLastResult;
		rayReconstructionLastResult = nativeStatus.rayReconstructionLastResult;
		neuralRenderingLastResult = nativeStatus.neuralRenderingLastResult;
		superResolutionReason = nativeStatus.superResolutionReason();
		rayReconstructionReason = nativeStatus.rayReconstructionReason();
		neuralRenderingReason = nativeStatus.neuralRenderingReason();
	}
}
