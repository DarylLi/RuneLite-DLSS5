package rs117.hd.webgpu;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WebGpuNativeSmokeTest {
	@Test
	public void loadsStandaloneRuneLiteRuntime() {
		Assume.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
		Assume.assumeNotNull(WebGpuNativeLibrary.locateRuntimeDirectory());

		RuneLiteWebGpuNative nativeApi = WebGpuNativeLibrary.load();
		assertNotNull(nativeApi.rlw_backend_name());

		RuneLiteWebGpuNative.GpuCapabilities capabilities = new RuneLiteWebGpuNative.GpuCapabilities();
		capabilities.write();
		assertEquals(1, nativeApi.rlw_get_capabilities(capabilities));
	}
}
