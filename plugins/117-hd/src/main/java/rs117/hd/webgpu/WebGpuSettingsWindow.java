package rs117.hd.webgpu;

import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Hashtable;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.FontManager;

/** RuneScape-styled, renderer-owned settings overlay toggled by Shift+Tab. */
public final class WebGpuSettingsWindow implements AutoCloseable
{
	private static final Color STONE = new Color(48, 43, 36);
	private static final Color STONE_LIGHT = new Color(76, 66, 52);
	private static final Color PARCHMENT = new Color(221, 201, 158);
	private static final Color GOLD = new Color(255, 196, 0);
	private static final Color TEXT = new Color(238, 221, 181);
	private static final Color MUTED = new Color(170, 156, 126);

	private final Canvas canvas;
	private final WebGpuSettings settings;
	private final Consumer<WebGpuSettings> onChanged;
	private final JDialog dialog;
	private final java.awt.KeyEventDispatcher keyDispatcher;
	private final ButtonGroup styleButtons = new ButtonGroup();
	private final JRadioButton[] styleControls = new JRadioButton[WebGpuStyleMode.values().length];
	private JCheckBox textures;
	private JCheckBox smoothTerrainMaterials;
	private JCheckBox smoothModelShading;
	private JCheckBox vsync;
	private JCheckBox rtxShadows;
	private JCheckBox dynamicShadows;
	private JCheckBox reflections;
	private JCheckBox rayReconstruction;
	private JCheckBox neuralRendering;
	private JCheckBox frameGeneration;
	private JComboBox<DlssMode> dlss;
	private JComboBox<RtxReflectionQuality> reflectionQuality;
	private JComboBox<WebGpuWeatherMode> weatherMode;
	private JComboBox<WebGpuWaterQuality> waterQuality;
	private JSlider drawDistance;
	private JSlider renderScale;
	private JSlider fpsLimit;
	private JSlider normalStrength;
	private JSlider modelSmoothingStrength;
	private JSlider saturation;
	private JSlider contrast;
	private JSlider ambient;
	private JSlider exposure;
	private JSlider timeOfDay;
	private JSlider weatherIntensity;
	private JSlider windStrength;
	private JSlider waterWaveStrength;
	private JSlider waterClarity;
	private JSlider shadowStrength;
	private JSlider reflectionStrength;
	private JSlider dlssSharpness;
	private JLabel dlssRuntimeStatus;
	private JLabel rayTracingStatus;
	private WebGpuDlssStatus effectiveDlssStatus;
	private String dlssFallbackReason = "";
	private boolean rayTracingAvailable;
	private boolean dlssAvailable;
	private boolean rayReconstructionAvailable;
	private boolean neuralRenderingAvailable;
	private String rayReconstructionUnavailableReason = "";
	private String neuralRenderingUnavailableReason = "";
	private boolean syncing;
	private Point dragOrigin;

	public WebGpuSettingsWindow(
		Canvas canvas,
		WebGpuSettings settings,
		WebGpuCapabilities capabilities,
		Consumer<WebGpuSettings> onChanged)
	{
		this.canvas = canvas;
		this.settings = settings;
		this.onChanged = onChanged;
		Window owner = SwingUtilities.getWindowAncestor(canvas);
		dialog = new JDialog(owner);
		dialog.setUndecorated(true);
		dialog.setModal(false);
		dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
		dialog.setContentPane(buildContent(capabilities));
		dialog.setSize(new Dimension(1320, 760));
		dialog.setMinimumSize(new Dimension(1120, 680));
		syncControls();

		keyDispatcher = event -> {
			if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_TAB
				&& event.isShiftDown() && !event.isControlDown() && !event.isAltDown())
			{
				toggle();
				return true;
			}
			return false;
		};
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher);
	}

	public void toggle()
	{
		SwingUtilities.invokeLater(() -> {
			if (dialog.isVisible())
			{
				dialog.setVisible(false);
				canvas.requestFocusInWindow();
			}
			else
			{
				syncControls();
				dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(canvas));
				dialog.setVisible(true);
				dialog.toFront();
			}
		});
	}

	public void refreshSettings()
	{
		SwingUtilities.invokeLater(this::syncControls);
	}

	/**
	 * Updates optional DLSS feature availability after the renderer quarantines a
	 * failed preview feature for the remainder of this RuneLite session.
	 */
	public void setOptionalDlssAvailability(
		boolean rayReconstructionAvailable,
		boolean neuralRenderingAvailable,
		String rayReconstructionUnavailableReason,
		String neuralRenderingUnavailableReason)
	{
		SwingUtilities.invokeLater(() -> {
			this.rayReconstructionAvailable = rayReconstructionAvailable;
			this.neuralRenderingAvailable = neuralRenderingAvailable;
			this.rayReconstructionUnavailableReason =
				rayReconstructionUnavailableReason == null
					? "" : rayReconstructionUnavailableReason;
			this.neuralRenderingUnavailableReason =
				neuralRenderingUnavailableReason == null
					? "" : neuralRenderingUnavailableReason;
			syncControls();
		});
	}

	/** Updates the read-only status using state reported by Streamline itself. */
	public void setDlssRuntimeStatus(
		WebGpuDlssStatus status,
		String fallbackReason)
	{
		SwingUtilities.invokeLater(() -> {
			effectiveDlssStatus = status;
			dlssFallbackReason = fallbackReason == null ? "" : fallbackReason;
			refreshDlssStatusLabel();
			syncFeatureAvailability();
		});
	}

	@Override
	public void close()
	{
		KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher);
		SwingUtilities.invokeLater(dialog::dispose);
	}

	private JPanel buildContent(WebGpuCapabilities capabilities)
	{
		JPanel root = new JPanel(new BorderLayout());
		root.setBackground(STONE);
		root.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(28, 24, 19), 4),
			BorderFactory.createLineBorder(new Color(139, 112, 67), 2)));
		root.add(buildTitle(), BorderLayout.NORTH);

		JPanel controls = new JPanel(new GridLayout(1, 4, 10, 0));
		controls.setBackground(STONE);
		controls.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
		controls.add(column(buildStyle(), buildPerformance()));
		controls.add(column(buildMaterials(), buildLighting()));
		controls.add(column(buildEnvironment()));
		controls.add(column(buildRtx(capabilities)));
		root.add(controls, BorderLayout.CENTER);

		JLabel footer = label("Shift + Tab closes this panel", MUTED, 13);
		footer.setHorizontalAlignment(SwingConstants.CENTER);
		footer.setBorder(BorderFactory.createEmptyBorder(8, 4, 9, 4));
		root.add(footer, BorderLayout.SOUTH);
		return root;
	}

	private JPanel buildTitle()
	{
		JPanel title = new JPanel(new BorderLayout());
		title.setBackground(new Color(32, 29, 25));
		title.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 8));
		JLabel text = label("117 HD  •  WEBGPU  •  RTX", GOLD, 19);
		title.add(text, BorderLayout.WEST);
		JButton close = button("X");
		close.addActionListener(event -> toggle());
		title.add(close, BorderLayout.EAST);
		MouseAdapter drag = new MouseAdapter()
		{
			@Override public void mousePressed(MouseEvent event) { dragOrigin = event.getPoint(); }
			@Override public void mouseDragged(MouseEvent event)
			{
				Point at = dialog.getLocation();
				dialog.setLocation(at.x + event.getX() - dragOrigin.x, at.y + event.getY() - dragOrigin.y);
			}
		};
		title.addMouseListener(drag);
		title.addMouseMotionListener(drag);
		return title;
	}

	private JPanel buildStyle()
	{
		JPanel panel = page();
		panel.add(sectionTitle("VISUAL PRESET"));
		JPanel styles = new JPanel(new GridLayout(1, 3, 8, 0));
		styles.setOpaque(false);
		for (WebGpuStyleMode mode : WebGpuStyleMode.values())
		{
			JRadioButton control = new JRadioButton(mode.toString());
			control.setFont(runescapeFont(16));
			control.setForeground(TEXT);
			control.setBackground(STONE_LIGHT);
			control.setHorizontalAlignment(SwingConstants.CENTER);
			control.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(112, 88, 49), 2),
				BorderFactory.createEmptyBorder(10, 3, 10, 3)));
			control.addActionListener(event -> {
				if (!syncing)
				{
					settings.applyPreset(mode);
					syncControls();
					changed();
				}
			});
			styleButtons.add(control);
			styleControls[mode.ordinal()] = control;
			styles.add(control);
		}
		panel.add(styles);
		panel.add(Box.createVerticalStrut(8));
		panel.add(info("Classic keeps a flatter Old School look. 117 HD is the balanced default. Cinematic increases material depth and contrast."));
		return panel;
	}

	private JPanel buildPerformance()
	{
		JPanel panel = page();
		panel.add(sectionTitle("PERFORMANCE"));
		drawDistance = slider(
			panel, "Draw distance", 10, WebGpuSettings.MAX_DRAW_DISTANCE,
			settings.drawDistance, 10, " tiles", value -> settings.drawDistance = value);
		renderScale = slider(panel, "Internal resolution", 50, 100, settings.renderScalePercent, 10, "%", value -> settings.renderScalePercent = value);
		fpsLimit = slider(panel, "Frame limit", 30, 240, settings.fpsLimit, 30, " FPS", value -> settings.fpsLimit = value);
		vsync = checkBox("Vertical sync", settings.vsync, value -> settings.vsync = value);
		panel.add(vsync);
		panel.add(Box.createVerticalStrut(6));
		panel.add(info("Draw distance reaches 72 tiles and automatically expands map loading in eight-tile chunks (within RuneLite's five-chunk engine limit). Higher distances use more Java staging memory, GPU memory and RTX acceleration-structure build time. Internal resolution affects only the 3D scene; widgets and text remain full resolution."));
		return panel;
	}

	private JPanel buildMaterials()
	{
		JPanel panel = page();
		panel.add(sectionTitle("117 HD MATERIALS"));
		textures = checkBox("117 HD textures", settings.texturesEnabled, value -> settings.texturesEnabled = value);
		panel.add(textures);
		smoothTerrainMaterials = checkBox(
			"Smooth tiles / terrain materials",
			settings.smoothTerrainMaterials,
			value -> settings.smoothTerrainMaterials = value);
		panel.add(smoothTerrainMaterials);
		smoothModelShading = checkBox(
			"Smooth model shading",
			settings.smoothModelShading,
			value -> {
				settings.smoothModelShading = value;
				if (modelSmoothingStrength != null)
					modelSmoothingStrength.setEnabled(value);
			});
		panel.add(smoothModelShading);
		modelSmoothingStrength = slider(panel, "Model smoothing strength", 0, 100,
			settings.modelSmoothingStrengthPercent, 25, "%",
			value -> settings.modelSmoothingStrengthPercent = value);
		modelSmoothingStrength.setEnabled(settings.smoothModelShading);
		normalStrength = decimalSlider(panel, "Normal-map strength", 0, 200, settings.normalStrength, "%", value -> settings.normalStrength = value / 100.0f);
		saturation = decimalSlider(panel, "Saturation", 0, 200, settings.saturation, "%", value -> settings.saturation = value / 100.0f);
		contrast = decimalSlider(panel, "Contrast", 50, 150, settings.contrast, "%", value -> settings.contrast = value / 100.0f);
		return panel;
	}

	private JPanel buildLighting()
	{
		JPanel panel = page();
		panel.add(sectionTitle("LIGHTING"));
		ambient = decimalSlider(panel, "Ambient intensity", 5, 100, settings.ambientIntensity, "%", value -> settings.ambientIntensity = value / 100.0f);
		exposure = decimalSlider(panel, "Exposure", 25, 200, settings.exposure, "%", value -> settings.exposure = value / 100.0f);
		timeOfDay = slider(panel, "Time of day", 0, 24, Math.round(settings.timeOfDay), 3, ":00", value -> settings.timeOfDay = value);
		panel.add(Box.createVerticalStrut(6));
		panel.add(info("Sunlight follows the time of day. Cast shadows fade at sunrise, sunset and under cloud cover; ambient light remains in shade. The RTX controls apply to this WebGPU renderer."));
		return panel;
	}

	private JPanel buildEnvironment()
	{
		JPanel panel = page();
		panel.add(sectionTitle("ENVIRONMENT"));
		panel.add(rowLabel("Weather"));
		weatherMode = comboBox(WebGpuWeatherMode.values(), settings.weatherMode);
		weatherMode.addActionListener(event -> {
			if (!syncing)
			{
				settings.weatherMode = (WebGpuWeatherMode) weatherMode.getSelectedItem();
				changed();
			}
		});
		panel.add(weatherMode);
		weatherIntensity = slider(panel, "Weather intensity", 0, 100,
			settings.weatherIntensityPercent, 25, "%", value -> settings.weatherIntensityPercent = value);
		windStrength = slider(panel, "Wind", 0, 100,
			settings.windStrengthPercent, 25, "%", value -> settings.windStrengthPercent = value);
		panel.add(Box.createVerticalStrut(5));
		panel.add(rowLabel("Water quality"));
		waterQuality = comboBox(WebGpuWaterQuality.values(), settings.waterQuality);
		waterQuality.addActionListener(event -> {
			if (!syncing)
			{
				settings.waterQuality = (WebGpuWaterQuality) waterQuality.getSelectedItem();
				changed();
			}
		});
		panel.add(waterQuality);
		waterWaveStrength = decimalSlider(panel, "Wave detail", 0, 200,
			settings.waterWaveStrength, "%", value -> settings.waterWaveStrength = value / 100.0f);
		waterClarity = slider(panel, "Water clarity", 0, 100,
			settings.waterClarityPercent, 25, "%", value -> settings.waterClarityPercent = value);
		panel.add(Box.createVerticalStrut(5));
		panel.add(info("Weather uses the procedural sky, clouds and screen rain. Water quality controls wave layers and reflection tint; it does not claim ray-traced refraction."));
		return panel;
	}

	private JPanel buildRtx(WebGpuCapabilities capabilities)
	{
		JPanel panel = page();
		panel.add(sectionTitle("NVIDIA RTX / DLSS 5"));
		String hardware = capabilities.rtx
			? capabilities.adapterName + " detected"
			: "Compatible RTX hardware was not detected";
		panel.add(info(hardware));
		panel.add(Box.createVerticalStrut(5));
		rayTracingAvailable = capabilities.rtx && capabilities.rayQuery;
		rayTracingStatus = info("");
		panel.add(rayTracingStatus);
		panel.add(Box.createVerticalStrut(6));

		rtxShadows = checkBox("RTX ray-traced shadows", settings.rtxEnabled,
			value -> {
				settings.rtxEnabled = value;
				syncFeatureAvailability();
			});
		rtxShadows.setEnabled(rayTracingAvailable);
		rtxShadows.setToolTipText(
			"Controls cast shadows in WebGPU. The standard 117 HD plug-in's shadow-map controls do not change this renderer.");
		panel.add(rtxShadows);
		shadowStrength = slider(panel, "Shadow strength", 0, 100,
			settings.rtxShadowStrengthPercent, 25, "%",
			value -> settings.rtxShadowStrengthPercent = value);
		dynamicShadows = checkBox("Dynamic actor shadows", settings.dynamicRtxShadows,
			value -> settings.dynamicRtxShadows = value);
		panel.add(dynamicShadows);
		reflections = checkBox("Water-only RT reflections", settings.rtxReflections,
			value -> {
				settings.rtxReflections = value;
				if (!value)
					settings.rayReconstruction = false;
				syncFeatureAvailability();
			});
		panel.add(reflections);
		reflectionStrength = slider(panel, "Reflection strength", 0, 100,
			settings.rtxReflectionStrengthPercent, 25, "%",
			value -> settings.rtxReflectionStrengthPercent = value);
		panel.add(rowLabel("Reflection quality"));
		reflectionQuality = comboBox(RtxReflectionQuality.values(), settings.rtxReflectionQuality);
		reflectionQuality.setToolTipText(
			"Performance traces one water ray per 4x4 block, Balanced per 2x2 block, and High per pixel.");
		reflectionQuality.addActionListener(event -> {
			if (!syncing)
			{
				settings.rtxReflectionQuality =
					(RtxReflectionQuality) reflectionQuality.getSelectedItem();
				changed();
			}
		});
		panel.add(reflectionQuality);
		panel.add(rowLabel("DLSS Super Resolution"));
		dlssAvailable = capabilities.dlssSuperResolution;
		rayReconstructionAvailable = capabilities.rayReconstruction;
		neuralRenderingAvailable = capabilities.neuralRendering;
		if (!dlssAvailable)
			settings.dlssMode = DlssMode.OFF;
		dlss = comboBox(selectableDlssModes(), settings.dlssMode);
		dlss.setPrototypeDisplayValue(DlssMode.ULTRA_PERFORMANCE);
		dlss.setEnabled(dlssAvailable);
		dlss.setToolTipText(
			"Choosing a Super Resolution mode turns off the separate DLSS 5 Neural Rendering preview.");
		dlss.addActionListener(event -> {
			if (!syncing)
			{
				settings.dlssMode = (DlssMode) dlss.getSelectedItem();
				if (settings.dlssMode != DlssMode.OFF)
					settings.neuralRendering = false;
				else
					settings.rayReconstruction = false;
				if (renderScale != null)
					renderScale.setEnabled(settings.dlssMode == DlssMode.OFF);
				syncControls();
				changed();
			}
		});
		panel.add(dlss);
		panel.add(Box.createVerticalStrut(4));
		panel.add(rowLabel("Effective DLSS status"));
		dlssRuntimeStatus = info("Reading the native Streamline state...");
		panel.add(dlssRuntimeStatus);
		dlssSharpness = slider(panel, "DLSS sharpness", 0, 100,
			settings.sharpnessPercent, 25, "%", value -> settings.sharpnessPercent = value);
		rayReconstruction = checkBox("DLSS Ray Reconstruction", settings.rayReconstruction,
			value -> settings.rayReconstruction = value);
		rayReconstruction.setToolTipText(
			"Requires DLSS Super Resolution, water reflections and complete depth/motion/material guides.");
		panel.add(rayReconstruction);
		neuralRendering = checkBox(
			"DLSS 5 Neural Rendering", settings.neuralRendering,
			value -> {
				settings.neuralRendering = value;
				if (value)
				{
					settings.dlssMode = DlssMode.OFF;
					settings.rayReconstruction = false;
				}
				syncControls();
			});
		neuralRendering.setEnabled(neuralRenderingAvailable);
		neuralRendering.setToolTipText(
			"Neural Rendering uses NVIDIA's signed preview color pipeline and turns Super Resolution off.");
		panel.add(neuralRendering);
		if (!neuralRenderingAvailable)
		{
			settings.neuralRendering = false;
			panel.add(info(capabilities.neuralRenderingDllLoaded
				? "The signed DLSS 5 preview runtime was found, but the current driver did not accept Neural Rendering."
				: "The signed DLSS 5 Neural Rendering preview runtime is unavailable."));
		}
		frameGeneration = disabledCheck("DLSS Frame Generation", false);
		frameGeneration.setToolTipText(
			"Unavailable: Vulkan Present interception is not implemented, so HUD-less color, depth/motion and DPI-correct UI tags cannot be retained until Present.");
		panel.add(frameGeneration);
		panel.add(info("Frame Generation is disabled: Vulkan Present interception is not implemented, so HUD-less color, depth/motion and DPI-correct UI tags cannot be retained until Present. Dynamic MFG is not offered on Vulkan."));
		panel.add(Box.createVerticalStrut(5));
		panel.add(info(dlssAvailable
			? "Super Resolution selects its own internal size. Ultra Quality is a legacy preset and is normalized to Quality. Neural Rendering remains a separate signed DLSS 5 path."
			: "DLSS Super Resolution is unavailable on the active Vulkan adapter."));
		refreshDlssStatusLabel();
		syncFeatureAvailability();
		return panel;
	}

	private void syncControls()
	{
		syncing = true;
		try
		{
			if (styleControls[settings.styleMode.ordinal()] != null)
				styleControls[settings.styleMode.ordinal()].setSelected(true);
			set(drawDistance, settings.drawDistance);
			set(renderScale, settings.renderScalePercent);
			if (renderScale != null)
				renderScale.setEnabled(settings.dlssMode == DlssMode.OFF);
			set(fpsLimit, settings.fpsLimit);
			set(normalStrength, Math.round(settings.normalStrength * 100));
			set(modelSmoothingStrength, settings.modelSmoothingStrengthPercent);
			set(saturation, Math.round(settings.saturation * 100));
			set(contrast, Math.round(settings.contrast * 100));
			set(ambient, Math.round(settings.ambientIntensity * 100));
			set(exposure, Math.round(settings.exposure * 100));
			set(timeOfDay, Math.round(settings.timeOfDay));
			set(weatherIntensity, settings.weatherIntensityPercent);
			set(windStrength, settings.windStrengthPercent);
			set(waterWaveStrength, Math.round(settings.waterWaveStrength * 100));
			set(waterClarity, settings.waterClarityPercent);
			set(shadowStrength, settings.rtxShadowStrengthPercent);
			set(reflectionStrength, settings.rtxReflectionStrengthPercent);
			set(dlssSharpness, settings.sharpnessPercent);
			if (weatherMode != null) weatherMode.setSelectedItem(settings.weatherMode);
			if (waterQuality != null) waterQuality.setSelectedItem(settings.waterQuality);
			if (reflectionQuality != null)
				reflectionQuality.setSelectedItem(settings.rtxReflectionQuality);
			if (textures != null) textures.setSelected(settings.texturesEnabled);
			if (smoothTerrainMaterials != null)
				smoothTerrainMaterials.setSelected(settings.smoothTerrainMaterials);
			if (smoothModelShading != null)
			{
				smoothModelShading.setSelected(settings.smoothModelShading);
				if (modelSmoothingStrength != null)
					modelSmoothingStrength.setEnabled(settings.smoothModelShading);
			}
			if (vsync != null) vsync.setSelected(settings.vsync);
			if (rtxShadows != null) rtxShadows.setSelected(settings.rtxEnabled);
			if (dynamicShadows != null)
			{
				dynamicShadows.setSelected(settings.dynamicRtxShadows);
				dynamicShadows.setEnabled(rayTracingAvailable && settings.rtxEnabled);
			}
			if (reflections != null)
			{
				reflections.setSelected(settings.rtxReflections);
				reflections.setEnabled(rayTracingAvailable);
			}
			if (dlss != null)
			{
				dlss.setSelectedItem(settings.dlssMode);
				dlss.setEnabled(dlssAvailable);
			}
			if (neuralRendering != null)
				neuralRendering.setSelected(settings.neuralRendering);
			if (rayReconstruction != null)
				rayReconstruction.setSelected(settings.rayReconstruction);
			if (frameGeneration != null)
				frameGeneration.setSelected(false);
			refreshDlssStatusLabel();
			syncFeatureAvailability();
		}
		finally
		{
			syncing = false;
		}
	}

	private void syncFeatureAvailability()
	{
		refreshRayTracingStatus();
		boolean shadowsActive = rayTracingAvailable && settings.rtxEnabled;
		boolean reflectionsActive = rayTracingAvailable && settings.rtxReflections;
		boolean superResolutionActive = dlssAvailable
			&& settings.dlssMode != DlssMode.OFF && !settings.neuralRendering;
		if (dynamicShadows != null) dynamicShadows.setEnabled(shadowsActive);
		if (shadowStrength != null) shadowStrength.setEnabled(shadowsActive);
		if (reflections != null) reflections.setEnabled(rayTracingAvailable);
		if (reflectionStrength != null) reflectionStrength.setEnabled(reflectionsActive);
		if (reflectionQuality != null) reflectionQuality.setEnabled(reflectionsActive);
		if (dlssSharpness != null) dlssSharpness.setEnabled(superResolutionActive);
		if (rayReconstruction != null)
		{
			boolean eligible = rayReconstructionAvailable
				&& reflectionsActive && superResolutionActive;
			if (!eligible)
			{
				settings.rayReconstruction = false;
				rayReconstruction.setSelected(false);
			}
			rayReconstruction.setEnabled(eligible);
			if (!rayReconstructionAvailable)
				rayReconstruction.setToolTipText(optionalFeatureReason(
					"Ray Reconstruction is unavailable for this RuneLite session.",
					!rayReconstructionUnavailableReason.isBlank()
						? rayReconstructionUnavailableReason
						: effectiveDlssStatus == null ? ""
							: effectiveDlssStatus.rayReconstructionReason));
			else if (!reflectionsActive)
				rayReconstruction.setToolTipText(
					"Enable Water-only RT reflections before Ray Reconstruction.");
			else if (!superResolutionActive)
				rayReconstruction.setToolTipText(
					"Select a DLSS Super Resolution mode before Ray Reconstruction.");
			else
				rayReconstruction.setToolTipText(
					"Uses the water reflection, depth, motion and material guides supplied by WebGPU.");
		}
		if (neuralRendering != null)
		{
			neuralRendering.setEnabled(neuralRenderingAvailable);
			neuralRendering.setToolTipText(neuralRenderingAvailable
				? "Separate signed NVIDIA preview path; enabling it turns Super Resolution off."
				: optionalFeatureReason(
					"DLSS 5 Neural Rendering is unavailable for this RuneLite session.",
					!neuralRenderingUnavailableReason.isBlank()
						? neuralRenderingUnavailableReason
						: effectiveDlssStatus == null ? ""
							: effectiveDlssStatus.neuralRenderingReason));
		}
		if (dlss != null && !dlssAvailable)
			dlss.setToolTipText(
				"DLSS Super Resolution is unavailable on the active Vulkan adapter/runtime.");
	}

	private void refreshDlssStatusLabel()
	{
		if (dlssRuntimeStatus == null)
			return;
		WebGpuDlssStatus status = effectiveDlssStatus;
		String requested = settings.neuralRendering
			? "DLSS 5 Neural Rendering"
			: settings.dlssMode.toString();
		if (status == null)
		{
			setInfoText(dlssRuntimeStatus,
				"Requested: " + escapeHtml(requested)
					+ "<br>Effective state: waiting for native diagnostics");
			return;
		}

		StringBuilder text = new StringBuilder("Requested: ")
			.append(escapeHtml(requested));
		if (status.neuralRenderingRequested)
		{
			text.append("<br>Neural Rendering: ")
				.append(stateText(status.neuralRenderingActive,
					status.neuralRenderingConfigured));
		}
		else if (status.superResolutionRequested)
		{
			text.append("<br>Super Resolution: ")
				.append(stateText(status.superResolutionActive,
					status.superResolutionConfigured))
				.append(" (").append(escapeHtml(status.requestedMode.toString()))
				.append(')');
			if (status.renderWidth > 0 && status.renderHeight > 0
				&& status.outputWidth > 0 && status.outputHeight > 0)
			{
				double scale = status.renderWidth * 100.0 / status.outputWidth;
				text.append("<br>Internal: ")
					.append(status.renderWidth).append('×').append(status.renderHeight)
					.append(" → ").append(status.outputWidth).append('×')
					.append(status.outputHeight).append(" (")
					.append(String.format(Locale.ROOT, "%.1f", scale)).append("%)");
			}
			text.append("<br>Evaluations: ")
				.append(status.superResolutionEvaluations).append(" passed / ")
				.append(status.superResolutionFailures).append(" failed");
		}
		else
		{
			text.append("<br>Super Resolution: <font color='#b8aa8a'>OFF — native WebGPU</font>");
		}

		String reason = dlssFallbackReason;
		if (reason.isBlank())
		{
			if (status.neuralRenderingRequested && !status.neuralRenderingActive)
				reason = status.neuralRenderingReason;
			else if (status.superResolutionRequested && !status.superResolutionActive)
				reason = status.superResolutionReason;
		}
		if (!reason.isBlank())
		{
			dlssRuntimeStatus.setToolTipText(reason);
			text.append("<br><font color='#ffb14a'>")
				.append(escapeHtml(shortStatusReason(reason))).append("</font>");
		}
		else
			dlssRuntimeStatus.setToolTipText(null);
		setInfoText(dlssRuntimeStatus, text.toString());
	}

	private static String shortStatusReason(String reason)
	{
		final int limit = 150;
		return reason.length() <= limit ? reason : reason.substring(0, limit - 1) + '…';
	}

	private static String stateText(boolean active, boolean configured)
	{
		if (active)
			return "<font color='#7ed67a'>ACTIVE</font>";
		if (configured)
			return "<font color='#ffc400'>configured, waiting for a scene frame</font>";
		return "<font color='#ff7a65'>NOT ACTIVE</font>";
	}

	private static String optionalFeatureReason(String fallback, String nativeReason)
	{
		return nativeReason == null || nativeReason.isBlank()
			? fallback
			: fallback + " " + nativeReason;
	}

	private static String escapeHtml(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;")
			.replace(">", "&gt;").replace("\"", "&quot;");
	}

	private JSlider decimalSlider(JPanel panel, String name, int minimum, int maximum,
		float current, String suffix, java.util.function.IntConsumer setter)
	{
		return slider(panel, name, minimum, maximum, Math.round(current * 100), 25, suffix, setter);
	}

	private JSlider slider(JPanel panel, String name, int minimum, int maximum, int current,
		int majorTick, String suffix, java.util.function.IntConsumer setter)
	{
		JLabel value = label(current + suffix, GOLD, 13);
		JPanel heading = new JPanel(new BorderLayout());
		heading.setOpaque(false);
		heading.setAlignmentX(Component.LEFT_ALIGNMENT);
		heading.add(rowLabel(name), BorderLayout.WEST);
		heading.add(value, BorderLayout.EAST);
		panel.add(heading);
		JSlider slider = new JSlider(minimum, maximum, current);
		slider.setOpaque(false);
		slider.setForeground(GOLD);
		slider.setMajorTickSpacing(majorTick);
		slider.setPaintTicks(true);
		slider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		slider.setAlignmentX(Component.LEFT_ALIGNMENT);
		slider.addChangeListener(event -> {
			value.setText(slider.getValue() + suffix);
			if (!syncing)
			{
				setter.accept(slider.getValue());
				if (!slider.getValueIsAdjusting()) changed();
			}
		});
		panel.add(slider);
		panel.add(Box.createVerticalStrut(3));
		return slider;
	}

	private JCheckBox checkBox(String name, boolean selected, Consumer<Boolean> setter)
	{
		JCheckBox control = new JCheckBox(name, selected);
		control.setFont(runescapeFont(15));
		control.setForeground(TEXT);
		control.setOpaque(false);
		control.setAlignmentX(Component.LEFT_ALIGNMENT);
		control.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		control.addActionListener(event -> {
			if (!syncing)
			{
				setter.accept(control.isSelected());
				changed();
			}
		});
		return control;
	}

	private JCheckBox disabledCheck(String name, boolean selected)
	{
		JCheckBox control = checkBox(name, selected, ignored -> {});
		control.setEnabled(false);
		return control;
	}

	private static <T> JComboBox<T> comboBox(T[] values, T selected)
	{
		JComboBox<T> control = new JComboBox<>(values);
		control.setSelectedItem(selected);
		control.setFont(runescapeFont(14));
		control.setPreferredSize(new Dimension(290, 28));
		control.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		control.setAlignmentX(Component.LEFT_ALIGNMENT);
		return control;
	}

	private static DlssMode[] selectableDlssModes()
	{
		return new DlssMode[] {
			DlssMode.OFF,
			DlssMode.MAX_PERFORMANCE,
			DlssMode.BALANCED,
			DlssMode.MAX_QUALITY,
			DlssMode.ULTRA_PERFORMANCE,
			DlssMode.DLAA
		};
	}

	private void changed()
	{
		refreshRayTracingStatus();
		onChanged.accept(settings);
	}

	private void refreshRayTracingStatus()
	{
		if (rayTracingStatus == null)
			return;
		if (!rayTracingAvailable)
		{
			setInfoText(rayTracingStatus, "Hardware ray tracing is unavailable on this adapter.");
			return;
		}
		String shadows;
		if (!settings.rtxEnabled)
			shadows = "Cast shadows: OFF. Enable RTX ray-traced shadows below.";
		else if (settings.rtxShadowStrengthPercent == 0)
			shadows = "Cast shadows enabled, but shadow strength is 0%.";
		else
		{
			double sunElevation = Math.sin((settings.timeOfDay - 6.0) * Math.PI / 12.0);
			shadows = sunElevation <= 0.001
				? "Cast shadows enabled. The sun is at or below the horizon; horizontal ground has no direct sunlight to cast a shadow."
				: "Cast shadows enabled for sunlit surfaces.";
			if (settings.ambientIntensity >= 0.8f)
				shadows += " High ambient light keeps shaded surfaces bright.";
		}
		String reflectionState = settings.rtxReflections
			? "Water reflections: enabled."
			: "Water reflections: OFF.";
		setInfoText(rayTracingStatus, shadows + "<br>" + reflectionState);
	}

	private static JPanel column(JPanel... sections)
	{
		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(STONE);
		column.setBorder(BorderFactory.createLineBorder(new Color(76, 66, 52), 1));
		for (int index = 0; index < sections.length; index++)
		{
			JPanel section = sections[index];
			section.setAlignmentX(Component.LEFT_ALIGNMENT);
			column.add(section);
			if (index + 1 < sections.length)
			{
				JPanel divider = new JPanel();
				divider.setBackground(new Color(76, 66, 52));
				divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
				divider.setPreferredSize(new Dimension(1, 1));
				column.add(divider);
			}
		}
		column.add(Box.createVerticalGlue());
		return column;
	}

	private static JPanel page()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(STONE);
		panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		return panel;
	}

	private static JLabel sectionTitle(String text)
	{
		JLabel label = label(text, PARCHMENT, 18);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		label.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		return label;
	}

	private static JLabel rowLabel(String text)
	{
		return label(text, TEXT, 14);
	}

	private static JLabel info(String text)
	{
		JLabel label = label("<html><div style='width:285px'>" + text + "</div></html>", MUTED, 13);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static void setInfoText(JLabel label, String text)
	{
		label.setText("<html><div style='width:285px'>" + text + "</div></html>");
	}

	private static JLabel label(String text, Color color, int size)
	{
		JLabel label = new JLabel(text);
		label.setForeground(color);
		label.setFont(runescapeFont(size));
		return label;
	}

	private static JButton button(String text)
	{
		JButton button = new JButton(text);
		button.setFont(runescapeFont(16));
		button.setForeground(GOLD);
		button.setBackground(STONE_LIGHT);
		button.setFocusPainted(false);
		button.setBorder(BorderFactory.createLineBorder(new Color(112, 88, 49), 1));
		button.setPreferredSize(new Dimension(34, 28));
		return button;
	}

	private static Font runescapeFont(int size)
	{
		return FontManager.getRunescapeFont().deriveFont((float) size);
	}

	private static void set(JSlider slider, int value)
	{
		if (slider != null) slider.setValue(value);
	}
}
