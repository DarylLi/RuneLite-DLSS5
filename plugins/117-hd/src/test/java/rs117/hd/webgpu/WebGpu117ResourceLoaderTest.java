package rs117.hd.webgpu;

import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.junit.Test;
import rs117.hd.utils.ColorUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WebGpu117ResourceLoaderTest
{
	@Test
	public void preservesAuthoredWaterColorsAcrossTriangleVertices() throws Exception
	{
		TextureProvider provider = mock(TextureProvider.class);
		Texture[] textures = new Texture[3];
		textures[2] = mock(Texture.class);
		when(provider.getTextures()).thenReturn(textures);
		WebGpu117ResourceLoader.Atlas atlas = WebGpu117ResourceLoader.buildAtlas(provider, 16, 4);
		WebGpu117ResourceLoader.GroundTextureResolver resolver = atlas.groundTextureResolver;
		// ALL-area rules select ordinary and swamp water independently of location.
		WebGpu117ResourceLoader.GroundTextureResolver.Surface water = resolver.resolveSurface(
			6, 0, 1000, 1000, 0, 90, true);
		WebGpu117ResourceLoader.GroundTextureResolver.Surface swamp = resolver.resolveSurface(
			7, 0, 1000, 1000, 0, 90, true);
		int waterColor = ColorUtils.srgbToPackedHsl(new float[] {105 / 255f, 128 / 255f, 156 / 255f});
		int swampColor = ColorUtils.srgbToPackedHsl(new float[] {23 / 255f, 33 / 255f, 20 / 255f});
		assertTrue(water.water);
		assertTrue(swamp.water);
		assertEquals("Water keeps the native classification sentinel", -2, water.textureId);
		assertEquals(-2, swamp.textureId);
		assertEquals("WATER uses #69809C from water_types.json", waterColor, water.packedHsl);
		assertEquals("SWAMP_WATER uses #172114 from water_types.json", swampColor, swamp.packedHsl);
		assertEquals("Other vertices must not revert to texture brightness", waterColor, water.modifyColor(30));
		assertEquals(swampColor, swamp.modifyColor(127));
		WebGpu117ResourceLoader.GroundTextureResolver.Surface dry = resolver.resolveSurface(
			2, 0, 1000, 1000, 0, 90, true);
		assertEquals("Dry terrain retains its ordinary color modifier", dry.packedHsl, dry.modifyColor(90));
	}

	@Test
	public void replacesVanillaBrickWithGenuine117Asset() throws Exception
	{
		TextureProvider provider = mock(TextureProvider.class);
		Texture[] textures = new Texture[3];
		textures[2] = mock(Texture.class); // materials.json maps vanilla ID 2 to BRICK.
		when(provider.getTextures()).thenReturn(textures);

		WebGpu117ResourceLoader.Atlas atlas = WebGpu117ResourceLoader.buildAtlas(provider, 64, 4);

		assertNotNull(atlas);
		assertEquals(1, atlas.loadedTextureCount);
		assertEquals(1, atlas.hdTextureCount); // BRICK -> HD_BRICK when modelTextures is enabled.
		assertTrue("117 ground textures should be appended to the atlas", atlas.groundTextureCount > 0);
		assertNotNull(atlas.groundTextureResolver);
		assertNotNull(atlas.lightCatalog);
		assertTrue("117 world lights should be loaded", atlas.lightCatalog.worldLights.size() > 100);
		assertTrue("117 object lights should resolve gamevals", atlas.lightCatalog.objectLights.size() > 100);
		assertTrue("117 NPC lights should resolve gamevals", atlas.lightCatalog.npcLights.size() > 10);
		int grassTexture = atlas.groundTextureResolver.resolveTextureId(
			0, 7, 3222, 3218, 0, 0); // Lumbridge and underlay 7 are in 117's OVERWORLD grass rule.
		assertTrue("117 grass must resolve outside the vanilla texture range", grassTexture >= textures.length);
		int gravelTexture = atlas.groundTextureResolver.resolveTextureId(
			2, 0, 1000, 1000, 0, 0); // ALL-area overlay rule, independent of the overworld.
		assertTrue("117 global overlay rules must resolve", gravelTexture >= textures.length);
		int originX = 2 * atlas.tileSize;
		boolean containsColor = false;
		for (int y = 0; y < atlas.tileSize && !containsColor; y++)
			for (int x = 0; x < atlas.tileSize; x++)
				if (atlas.rgbaPixels[y * atlas.width + originX + x] != 0)
				{
					containsColor = true;
					break;
				}
		assertTrue("The HD brick texture cell should contain image data", containsColor);
	}
}
