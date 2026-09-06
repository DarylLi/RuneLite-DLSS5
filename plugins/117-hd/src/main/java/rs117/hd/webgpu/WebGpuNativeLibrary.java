package rs117.hd.webgpu;

import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

final class WebGpuNativeLibrary {
	static final String NATIVE_DIR_PROPERTY = "rlhd.webgpu.native-dir";
	static final String NATIVE_DIR_ENVIRONMENT = "RLHD_WEBGPU_NATIVE_DIR";
	private static final String LIBRARY_NAME = "runelite_webgpu";

	private WebGpuNativeLibrary() {}

	static RuneLiteWebGpuNative load() {
		Path directory = locateRuntimeDirectory();
		if (directory == null) {
			throw new IllegalStateException(
				"WebGPU runtime was not found. Run native/Build-Native.ps1 or set -D" +
				NATIVE_DIR_PROPERTY + "=<directory>");
		}
		NativeLibrary.addSearchPath(LIBRARY_NAME, directory.toString());
		return Native.load(LIBRARY_NAME, RuneLiteWebGpuNative.class);
	}

	static Path locateRuntimeDirectory() {
		List<Path> candidates = new ArrayList<>();
		addCandidate(candidates, System.getProperty(NATIVE_DIR_PROPERTY));
		addCandidate(candidates, System.getenv(NATIVE_DIR_ENVIRONMENT));

		Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
		for (int i = 0; current != null && i < 5; i++, current = current.getParent())
			candidates.add(current.resolve(".runtime").resolve("runelite-webgpu"));

		return candidates.stream()
			.map(Path::toAbsolutePath)
			.filter(path -> Files.isRegularFile(path.resolve("runelite_webgpu.dll")))
			.findFirst()
			.orElse(null);
	}

	private static void addCandidate(List<Path> candidates, String value) {
		if (value != null && !value.isBlank())
			candidates.add(Paths.get(value));
	}
}
