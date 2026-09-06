package rs117.hd;

import java.io.InputStream;
import java.awt.Frame;
import java.util.Properties;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;

/**
 * Production entry point used by the Jagex-managed RuneLite launcher.
 *
 * <p>All command-line arguments are passed unchanged to RuneLite so the Jagex
 * Launcher authentication hand-off continues to work.</p>
 */
@Slf4j
public final class WebGpuRuneLite
{
	private WebGpuRuneLite()
	{
	}

	public static void main(String[] args) throws Exception
	{
		useLatestPluginHub();
		ExternalPluginManager.loadBuiltin(WebGpuPlugin.class);
		// Preserve the Jagex Launcher's authentication hand-off exactly as it was
		// supplied. Do not add --insecure-write-credentials: the launcher starts
		// this process with the live session already available, and the WebGPU
		// renderer has no reason to persist account tokens to disk.
		RuneLite.main(args);

		PluginManager pluginManager = RuneLite.getInjector().getInstance(PluginManager.class);
		SwingUtilities.invokeLater(() ->
		{
			setCustomWindowTitle();
			enableWebGpuPlugin(pluginManager);
		});
	}

	private static void setCustomWindowTitle()
	{
		for (Frame frame : Frame.getFrames())
		{
			if (frame.isDisplayable())
			{
				frame.setTitle("RuneLite DLSS 5 - CUSTOM JAR");
			}
		}
	}

	private static void enableWebGpuPlugin(PluginManager pluginManager)
	{
		for (Plugin plugin : pluginManager.getPlugins())
		{
			if (plugin instanceof WebGpuPlugin)
			{
				try
				{
					pluginManager.setPluginEnabled(plugin, true);
					pluginManager.startPlugin(plugin);
				}
				catch (Exception ex)
				{
					log.error("Unable to start the experimental WebGPU plugin", ex);
				}
				return;
			}
		}
		log.error("The experimental WebGPU plugin was not loaded");
	}

	private static void useLatestPluginHub()
	{
		if (System.getProperty("runelite.pluginhub.version") != null)
		{
			return;
		}

		try
		{
			Properties properties = new Properties();
			try (InputStream input = RuneLiteProperties.class.getResourceAsStream("runelite.properties"))
			{
				if (input == null)
				{
					return;
				}
				properties.load(input);
			}

			String version = properties.getProperty("runelite.pluginhub.version");
			if (version == null)
			{
				return;
			}

			String[] parts = version.split("[.-]");
			if (parts.length > 3 && parts[3].equals("SNAPSHOT"))
			{
				int patch = Integer.parseInt(parts[2]) - 1;
				System.setProperty("runelite.pluginhub.version", parts[0] + "." + parts[1] + "." + patch);
			}
		}
		catch (Exception ex)
		{
			log.error("Failed to select the latest plugin-hub version", ex);
		}
	}
}
