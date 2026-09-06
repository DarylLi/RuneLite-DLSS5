package rs117.hd.webgpu;

import java.nio.ByteBuffer;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import org.junit.BeforeClass;
import org.junit.Test;
import rs117.hd.utils.ColorUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebGpuWaterColorCaptureTest
{
	private static WebGpu117ResourceLoader.GroundTextureResolver resolver;
	private static final int WATER_COLOR = ColorUtils.srgbToPackedHsl(
		new float[] { 105 / 255f, 128 / 255f, 156 / 255f });

	@BeforeClass
	public static void loadResolverWithoutWaterTexture() throws Exception
	{
		TextureProvider provider = mock(TextureProvider.class);
		Texture[] textures = new Texture[3];
		textures[2] = mock(Texture.class);
		when(provider.getTextures()).thenReturn(textures);
		resolver = WebGpu117ResourceLoader.buildAtlas(provider, 16, 4).groundTextureResolver;
		assertFalse(resolver.isKnownVanillaTexture(130));
		assertFalse(resolver.isKnownVanillaTexture(999));
	}

	@Test
	public void paintedWaterUsesAuthoredTintWithoutProviderTexture()
	{
		WebGpuSceneBuffer buffer = paintBuffer(130);
		assertSurface(buffer, -2, WATER_COLOR, 6);
	}

	@Test
	public void shapedWaterUsesAuthoredTintWithoutProviderTexture()
	{
		WebGpuSceneBuffer buffer = modelBuffer(130);
		assertSurface(buffer, -2, WATER_COLOR, 3);
	}

	@Test
	public void unknownNonWaterPaintRetainsItsTextureAndColor()
	{
		WebGpuSceneBuffer buffer = paintBuffer(999);
		assertEquals(6, buffer.vertexCount());
		assertSurface(buffer, 999, 90, 6);
	}

	@Test
	public void unknownNonWaterModelRetainsItsTextureAndColor()
	{
		WebGpuSceneBuffer buffer = modelBuffer(999);
		assertEquals(3, buffer.vertexCount());
		assertSurface(buffer, 999, 90, 3);
	}

	private static WebGpuSceneBuffer paintBuffer(int texture)
	{
		SceneTilePaint paint = mock(SceneTilePaint.class);
		when(paint.getTexture()).thenReturn(texture);
		when(paint.getSwColor()).thenReturn(90);
		when(paint.getSeColor()).thenReturn(90);
		when(paint.getNwColor()).thenReturn(90);
		when(paint.getNeColor()).thenReturn(90);
		WebGpuSceneBuffer buffer = buffer();
		buffer.addTilePaint(scene(), paint, 0, 0, 0);
		return buffer;
	}

	private static WebGpuSceneBuffer modelBuffer(int texture)
	{
		SceneTileModel model = mock(SceneTileModel.class);
		when(model.getShape()).thenReturn(1);
		when(model.getFaceX()).thenReturn(new int[] { 0 });
		when(model.getFaceY()).thenReturn(new int[] { 1 });
		when(model.getFaceZ()).thenReturn(new int[] { 2 });
		when(model.getVertexX()).thenReturn(new int[] { 0, 128, 0 });
		when(model.getVertexY()).thenReturn(new int[] { 0, 0, 0 });
		when(model.getVertexZ()).thenReturn(new int[] { 0, 0, 128 });
		when(model.getTriangleColorA()).thenReturn(new int[] { 90 });
		when(model.getTriangleColorB()).thenReturn(new int[] { 90 });
		when(model.getTriangleColorC()).thenReturn(new int[] { 90 });
		when(model.getTriangleTextureId()).thenReturn(new int[] { texture });
		Tile tile = mock(Tile.class);
		when(tile.getSceneTileModel()).thenReturn(model);
		WebGpuSceneBuffer buffer = buffer();
		buffer.addTileModel(scene(), tile, model, 0, 0, 0);
		return buffer;
	}

	private static WebGpuSceneBuffer buffer()
	{
		WebGpuSceneBuffer buffer = new WebGpuSceneBuffer();
		buffer.setGroundTextureResolver(resolver);
		return buffer;
	}

	private static Scene scene()
	{
		Scene scene = mock(Scene.class);
		when(scene.getWorldViewId()).thenReturn(1);
		when(scene.getBaseX()).thenReturn(1000);
		when(scene.getBaseY()).thenReturn(1000);
		when(scene.getTileHeights()).thenReturn(new int[1][2][2]);
		when(scene.getOverlayIds()).thenReturn(new short[][][] { { { 6 } } });
		when(scene.getUnderlayIds()).thenReturn(new short[1][1][1]);
		return scene;
	}

	private static void assertSurface(WebGpuSceneBuffer buffer, int texture, int color, int count)
	{
		int matched = 0;
		ByteBuffer vertices = buffer.packedVertices();
		for (int vertex = 0; vertex < buffer.vertexCount(); vertex++)
		{
			int offset = vertex * WebGpuSceneBuffer.PACKED_VERTEX_BYTES;
			if (vertices.getInt(offset + 24) != texture)
				continue;
			assertEquals(color, vertices.getInt(offset + 12));
			assertEquals("The original color stream remains intact", 90, vertices.getInt(offset + 32));
			matched++;
		}
		assertEquals(count, matched);
	}
}
