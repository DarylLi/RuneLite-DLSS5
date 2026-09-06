package rs117.hd;

import java.io.InputStream;
import java.util.Properties;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import rs117.hd.utils.Props;

@Slf4j
public class WebGpuPluginTest {
	public static void main(String[] args) throws Exception {
		Props.DEVELOPMENT = true;
		Props.set("rlhd.resource-path", "src/main/resources");
		ClassLoader.getSystemClassLoader().setDefaultAssertionStatus(true);
		useLatestPluginHub();
		ExternalPluginManager.loadBuiltin(WebGpuPlugin.class);
		RuneLite.main(args);

		PluginManager pluginManager = RuneLite.getInjector().getInstance(PluginManager.class);
		SwingUtilities.invokeLater(() -> {
			for (Plugin plugin : pluginManager.getPlugins()) {
				if (plugin instanceof WebGpuPlugin) {
					try {
						pluginManager.setPluginEnabled(plugin, true);
						pluginManager.startPlugin(plugin);
					} catch (Exception ex) {
						log.error("Unable to start the experimental WebGPU plugin", ex);
					}
					return;
				}
			}
			log.error("The experimental WebGPU plugin was not loaded");
		});
	}

	private static void useLatestPluginHub() {
		if (System.getProperty("runelite.pluginhub.version") != null)
			return;
		try {
			Properties properties = new Properties();
			try (InputStream input = RuneLiteProperties.class.getResourceAsStream("runelite.properties")) {
				properties.load(input);
			}
			String version = properties.getProperty("runelite.pluginhub.version");
			String[] parts = version.split("[.-]");
			if (parts.length > 3 && parts[3].equals("SNAPSHOT")) {
				int patch = Integer.parseInt(parts[2]) - 1;
				System.setProperty("runelite.pluginhub.version", parts[0] + "." + parts[1] + "." + patch);
			}
		} catch (Exception ex) {
			log.error("Failed to select the latest plugin-hub version", ex);
		}
	}
}
