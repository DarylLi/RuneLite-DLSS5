package rs117.hd.webgpu;

import net.runelite.api.Model;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebGpuSceneBufferTest
{
	@Test
	public void stagesVanillaUvMappedFace()
	{
		Model model = mock(Model.class);
		when(model.getVerticesX()).thenReturn(new float[] { 0, 1, 0 });
		when(model.getVerticesY()).thenReturn(new float[] { 0, 0, 0 });
		when(model.getVerticesZ()).thenReturn(new float[] { 0, 0, 1 });
		when(model.getFaceIndices1()).thenReturn(new int[] { 0 });
		when(model.getFaceIndices2()).thenReturn(new int[] { 1 });
		when(model.getFaceIndices3()).thenReturn(new int[] { 2 });
		when(model.getFaceColors1()).thenReturn(new int[] { 90 });
		when(model.getFaceColors2()).thenReturn(new int[] { 90 });
		when(model.getFaceColors3()).thenReturn(new int[] { 90 });
		when(model.getFaceTextures()).thenReturn(new short[] { 7 });
		when(model.getTextureFaces()).thenReturn(new byte[] { 0 });
		when(model.getTexIndices1()).thenReturn(new int[] { 0 });
		when(model.getTexIndices2()).thenReturn(new int[] { 1 });
		when(model.getTexIndices3()).thenReturn(new int[] { 2 });
		when(model.getFaceCount()).thenReturn(1);

		WebGpuSceneBuffer buffer = new WebGpuSceneBuffer();
		buffer.addModel(model, 0, 0, 0, 0);

		assertEquals(3, buffer.vertexCount());
		assertArrayEquals(new float[] { 0, 0, 1, 0, 0, 1 },
			java.util.Arrays.copyOf(buffer.uvs(), 6), 0.0001f);
		assertArrayEquals(new int[] { 7, 7, 7 },
			java.util.Arrays.copyOf(buffer.textureIds(), 3));
		assertArrayEquals(new int[] { 1, 1, 1 },
			java.util.Arrays.copyOf(buffer.sourceFlags(), 3));
	}

	@Test
	public void retainsStaticPrefixAcrossFrames()
	{
		WebGpuSceneBuffer buffer = new WebGpuSceneBuffer();
		buffer.addPointLight(1, 2, 3, 100, 1, new float[] { 1, 1, 1 }, 0, 0, 0);
		buffer.markStaticGeometryComplete();
		long revision = buffer.staticRevision();

		buffer.addPointLight(4, 5, 6, 100, 1, new float[] { 1, 0, 0 }, 0, 0, 0);
		assertEquals(2, buffer.lightCount());

		buffer.resetToStaticGeometry();
		assertEquals(1, buffer.lightCount());
		assertEquals(revision, buffer.staticRevision());
	}

	@Test
	public void classifierAndEmptyEpochDoNotAllocateVertexStorage()
	{
		WebGpuSceneBuffer buffer = new WebGpuSceneBuffer();
		assertEquals(0, buffer.packedVertices().capacity());
		buffer.markStaticGeometryComplete();
		assertEquals(0, buffer.snapshotStaticGeometry().vertexCount());
		buffer.clearWithFreshStorage();
		assertEquals(0, buffer.packedVertices().capacity());
	}

	@Test
	public void freshEpochPreservesOldSnapshotAndAllocatesStorageOnDemand()
	{
		Model model = mock(Model.class);
		when(model.getVerticesX()).thenReturn(new float[] { 7, 8, 7 });
		when(model.getVerticesY()).thenReturn(new float[] { 0, 0, 0 });
		when(model.getVerticesZ()).thenReturn(new float[] { 0, 0, 1 });
		when(model.getFaceIndices1()).thenReturn(new int[] { 0 });
		when(model.getFaceIndices2()).thenReturn(new int[] { 1 });
		when(model.getFaceIndices3()).thenReturn(new int[] { 2 });
		when(model.getFaceColors1()).thenReturn(new int[] { 90 });
		when(model.getFaceColors2()).thenReturn(new int[] { 90 });
		when(model.getFaceColors3()).thenReturn(new int[] { 90 });
		when(model.getFaceCount()).thenReturn(1);
		WebGpuSceneBuffer buffer = new WebGpuSceneBuffer();
		buffer.addModel(model, 0, 0, 0, 0);
		buffer.markStaticGeometryComplete();
		WebGpuSceneBuffer.StaticGeometrySnapshot snapshot = buffer.snapshotStaticGeometry();
		buffer.clearWithFreshStorage();
		buffer.addModel(model, 0, 100, 0, 0);
		assertEquals(107f, buffer.packedVertices().getFloat(0), 0f);

		WebGpuSceneBuffer copy = new WebGpuSceneBuffer();
		assertEquals(3, copy.appendStaticGeometryRange(snapshot, 0, 3));
		assertEquals(7f, copy.packedVertices().getFloat(0), 0f);
	}
}
