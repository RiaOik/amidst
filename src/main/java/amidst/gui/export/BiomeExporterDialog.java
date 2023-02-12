package amidst.gui.export;

import static java.awt.GridBagConstraints.BOTH;
import static java.awt.GridBagConstraints.CENTER;
import static java.awt.GridBagConstraints.EAST;
import static java.awt.GridBagConstraints.HORIZONTAL;
import static java.awt.GridBagConstraints.NONE;
import static java.awt.GridBagConstraints.SOUTH;
import static java.awt.GridBagConstraints.SOUTHEAST;
import static java.awt.GridBagConstraints.SOUTHWEST;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Composite;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.LineBorder;

import amidst.documentation.NotThreadSafe;
import amidst.gui.main.Actions;
import amidst.gui.main.PNGFileFilter;
import amidst.gui.main.menu.AmidstMenu;
import amidst.gui.main.viewer.FragmentGraphToScreenTranslator;
import amidst.gui.main.viewer.widget.ProgressWidget.ProgressEntryType;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.biome.Biome;
import amidst.mojangapi.world.biome.UnknownBiomeIdException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.oracle.BiomeDataOracle;
import amidst.settings.Setting;
import amidst.settings.biomeprofile.BiomeProfileSelection;
import amidst.util.SwingUtils;

@NotThreadSafe
public class BiomeExporterDialog {
	private static final int PREVIEW_SIZE = 100;
	private static final ExecutorService previewUpdater = Executors
			.newSingleThreadExecutor(r -> new Thread(r, "BiomePreviewUpdater"));

	private final Setting<String> lastBiomeExportPath;
	private final BiomeExporter biomeExporter;
	private final Frame parentFrame;
	private final Supplier<AmidstMenu> menuBarSupplier;
	private final BiomeProfileSelection biomeProfileSelection;
	private final GridBagConstraints constraints;
	private final GridBagConstraints labelPaneConstraints;
	private final JSpinner leftSpinner, topSpinner, rightSpinner, bottomSpinner;
	private final JCheckBox fullResCheckBox;
	private final JTextField pathField;
	private final JButton browseButton;
	private final JButton exportButton;
	private final BufferedImage previewImage;
	private final ImageIcon previewIcon;
	private final JLabel previewLabel;
	private final JDialog dialog;

	private WorldOptions worldOptions;
	private BiomeDataOracle biomeDataOracle;
	private Consumer<Entry<ProgressEntryType, Integer>> progressListener;

	public BiomeExporterDialog(BiomeExporter biomeExporter, Frame parentFrame,
			BiomeProfileSelection biomeProfileSelection,
			Supplier<AmidstMenu> menuBarSupplier, Setting<String> lastBiomeExportPath) {
		// @formatter:off
		this.lastBiomeExportPath   = lastBiomeExportPath;
		this.biomeExporter         = biomeExporter;
		this.parentFrame           = parentFrame;
		this.menuBarSupplier       = menuBarSupplier;
		this.biomeProfileSelection = biomeProfileSelection;
		this.constraints           = new GridBagConstraints();
		this.labelPaneConstraints  = new GridBagConstraints();

		this.leftSpinner           = createCoordinateSpinner();
		this.topSpinner            = createCoordinateSpinner();
		this.rightSpinner          = createCoordinateSpinner();
		this.bottomSpinner         = createCoordinateSpinner();
		this.fullResCheckBox       = createFullResCheckbox();
		this.pathField             = createPathField();
		this.browseButton          = createBrowseButton();
		this.exportButton          = createExportButton();
		this.previewImage          = new BufferedImage(PREVIEW_SIZE, PREVIEW_SIZE, BufferedImage.TYPE_INT_ARGB);
		this.previewIcon           = new ImageIcon(new BufferedImage(PREVIEW_SIZE * 2, PREVIEW_SIZE * 2, BufferedImage.TYPE_INT_ARGB));
		this.previewLabel          = createPreviewLabel();
		this.dialog                = createDialog();
		// @formatter:on
	}

	private JCheckBox createFullResCheckbox() {
		JCheckBox checkBox = new JCheckBox("Full Resolution");
		checkBox.addChangeListener(e -> renderPreview());
		return checkBox;
	}

	private JTextField createPathField() {
		JTextField textField = new JTextField();
		textField.setPreferredSize(new JTextField(String.join("", Collections.nCopies(50, "_"))).getPreferredSize());
		return textField;
	}

	private JSpinner createCoordinateSpinner() {
		JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, -30000000, 30000000, 25));
		spinner.addChangeListener(e -> renderPreview());
		return spinner;
	}

	private JLabel createPreviewLabel() {
		JLabel label = new JLabel();
		label.setIcon(previewIcon);
		label.setBorder(new LineBorder(Color.BLACK, 2));
		return label;
	}

	private JButton createExportButton() {
		return createButtonWithAction("Export", this::exportBiome);
	}

	private void exportBiome() {
		try {
			commitSpinnerEdits();
		} catch (ParseException e) {
			// Resets itself to previous value
		}

		CoordinatesInWorld topLeft = getTopLeftCoordinates();
		CoordinatesInWorld bottomRight = getBottomRightCoordinates();
		if (isValidExportConfiguration(topLeft, bottomRight)) {
			Path path = Paths.get(getPathString());
			updateLastExportPath(path);
			exportBiome(path, topLeft, bottomRight);
			closeDialog();
		}
	}

	private void commitSpinnerEdits() throws ParseException {
		topSpinner.commitEdit();
		leftSpinner.commitEdit();
		bottomSpinner.commitEdit();
		rightSpinner.commitEdit();
	}

	private boolean isValidExportConfiguration(CoordinatesInWorld topLeft, CoordinatesInWorld bottomRight) {
		return verifyImageCoordinates(topLeft, bottomRight) && verifyPathString(getPathString());
	}

	private String getPathString() {
		return pathField.getText();
	}

	private void updateLastExportPath(Path path) {
		lastBiomeExportPath.set(path.toAbsolutePath().getParent().toString());
	}

	private void exportBiome(Path path, CoordinatesInWorld topLeft, CoordinatesInWorld bottomRight) {
		biomeExporter.export(biomeDataOracle, createBiomeExporterConfiguration(path, topLeft, bottomRight),
				progressListener, menuBarSupplier.get());
	}

	private BiomeExporterConfiguration createBiomeExporterConfiguration(Path path, CoordinatesInWorld topLeft,
			CoordinatesInWorld bottomRight) {
		return new BiomeExporterConfiguration(path, isLowResolutionSelected(), topLeft, bottomRight,
				biomeProfileSelection);
	}

	private boolean isLowResolutionSelected() {
		return !fullResCheckBox.isSelected();
	}

	private void closeDialog() {
		dialog.dispose();
	}

	private JButton createButtonWithAction(String label, Runnable action) {
		JButton button = new JButton(label);
		button.addActionListener(e -> action.run());
		return button;
	}

	private boolean verifyPathString(String path) {
		try {
			Path p = Paths.get(path);
			Files.createDirectories(p.getParent());

			if (!Files.isRegularFile(p)) {
				AmidstMessageBox.displayError(dialog, "Error", "Not a file: " + p.toString());
				return false;
			}

			if (!Actions.canWriteToFile(p)) {
				AmidstMessageBox.displayError(dialog, "Error", "No writing permissions for: " + p.toString());
				return false;
			}

			if (Files.exists(p) && !AmidstMessageBox.askToConfirmYesNo(dialog, "Replace file?",
					"File already exists. Do you want to replace it?\n" + p.toString())) {
				return false;
			}

			return true;
		} catch (InvalidPathException e) {
			AmidstMessageBox.displayError(dialog, "Error", "Invalid path: " + path);
		} catch (IOException e) {
			AmidstMessageBox.displayError(dialog, "Error", "Error creating directories for: " + path);
		}
		return false;
	}

	public boolean verifyImageCoordinates(CoordinatesInWorld topLeft, CoordinatesInWorld bottomRight) {
		if (topLeft == null || bottomRight == null)
			return false;

		if (topLeft.getX() >= bottomRight.getX() || topLeft.getY() >= bottomRight.getY()) {
			String message = "Invalid image coordinates detected.";
			AmidstLogger.warn("Unable to create image: " + message);
			AmidstMessageBox.displayError(dialog, "Error", message);
			return false;
		}

		return true;
	}

	private JButton createBrowseButton() {
		JButton newButton = new JButton("Browse...");
		newButton.addActionListener(e -> {
			Path exportPath = getExportPath();
			if (exportPath != null) {
				pathField.setText(exportPath.toAbsolutePath().toString());
			}
		});
		return newButton;
	}

	private Path getExportPath() {
		Path file = null;

		JFileChooser fileChooser = new JFileChooser();
		fileChooser.setFileFilter(new PNGFileFilter());
		fileChooser.setAcceptAllFileFilterUsed(false);
		fileChooser.setSelectedFile(Paths.get(pathField.getText()).toAbsolutePath().toFile());
		if (fileChooser.showDialog(dialog, "Confirm") == JFileChooser.APPROVE_OPTION) {
			file = Actions.appendFileExtensionIfNecessary(fileChooser.getSelectedFile().toPath(), "png");
		}

		return file;
	}

	private String getSuggestedFilename() {
		return "biomes_" + worldOptions.getWorldType().getFilenameText() + "_" + worldOptions.getWorldSeed().getLong()
				+ ".png";
	}

	private JPanel createLabeledPanel(String label, Component component, int fillConst) {
		JPanel newPanel = new JPanel(new GridBagLayout());

		JLabel newLabel = new JLabel(label);
		newLabel.setHorizontalAlignment(SwingConstants.CENTER);
		newLabel.setVerticalAlignment(SwingConstants.BOTTOM);
		setLabelPaneConstraints(0, 0, 0, 0, HORIZONTAL, 0, 0, 1, 1, 1.0, 0.0, SOUTH);
		newPanel.add(newLabel, labelPaneConstraints);

		setLabelPaneConstraints(0, 0, 0, 0, fillConst, 0, 1, 1, 1, 1.0, 0.0, CENTER);
		newPanel.add(component, labelPaneConstraints);

		return newPanel;
	}

	private JDialog createDialog() {
		JPanel panel = new JPanel(new GridBagLayout());

		setConstraints(40, 0, 0, 0, NONE, 1, 1, 1, 1, 0.0, 0.0, SOUTH);
		panel.add(createLabeledPanel("Top:", topSpinner, NONE), constraints);

		setConstraints(20, 20, 0, 0, NONE, 0, 2, 1, 1, 0.0, 0.0, SOUTH);
		panel.add(createLabeledPanel("Left:", leftSpinner, NONE), constraints);

		setConstraints(20, 0, 0, 0, NONE, 1, 3, 1, 1, 0.0, 0.0, SOUTH);
		panel.add(createLabeledPanel("Bottom:", bottomSpinner, NONE), constraints);

		setConstraints(20, 0, 0, 0, NONE, 2, 2, 1, 1, 0.0, 0.0, SOUTH);
		panel.add(createLabeledPanel("Right:", rightSpinner, NONE), constraints);

		setConstraints(10, 20, 0, 0, NONE, 0, 5, 2, 1, 0.0, 0.0, SOUTHWEST);
		panel.add(fullResCheckBox, constraints);

		setConstraints(0, 15, 0, 15, BOTH, 3, 0, 1, 6, 1.0, 0.0, CENTER);
		panel.add(Box.createGlue(), constraints);

		setConstraints(0, 0, 0, 0, BOTH, 0, 4, 4, 1, 0.0, 1.0, CENTER);
		panel.add(Box.createGlue(), constraints);

		JPanel pathPanel = new JPanel(new GridBagLayout());

		setConstraints(0, 0, 0, 0, HORIZONTAL, 0, 0, 1, 1, 0.0, 0.0, SOUTH);
		pathPanel.add(createLabeledPanel("Path:", pathField, HORIZONTAL), constraints);

		setConstraints(0, 10, 0, 0, HORIZONTAL, 1, 0, 1, 1, 0.0, 0.0, SOUTH);
		pathPanel.add(browseButton, constraints);

		setConstraints(10, 20, 20, 10, BOTH, 0, 6, 4, 2, 0.0, 0.0, SOUTHWEST);
		panel.add(pathPanel, constraints);

		setConstraints(15, 10, 10, 20, BOTH, 4, 0, 1, 7, 0.0, 0.0, EAST);
		panel.add(createLabeledPanel("Preview:", previewLabel, NONE), constraints);

		setConstraints(10, 10, 20, 20, NONE, 4, 7, 1, 1, 0.0, 0.0, SOUTHEAST);
		panel.add(exportButton, constraints);

		JDialog newDialog = new JDialog(parentFrame, "Export Biome Image");
		newDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
		newDialog.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				/*
				 * This executes only when it's closed with the x button, alt f4, etc.
				 * When this happens we know that the user did not press the ok button
				 * to continue, so we re enable the export biomes menu button.
				 */
				menuBarSupplier.get()
						.setMenuItemsEnabled(new String[] { "Export Biomes to Image ...", "Biome Profile" }, true);
				newDialog.dispose();
			}
		});
		newDialog.add(panel);
		newDialog.pack();
		newDialog.setResizable(false);
		return newDialog;
	}

	private Future<?> renderTask;

	private void renderPreview() {
		if (renderTask != null && !renderTask.isDone()) {
			renderTask.cancel(true);
		}

		renderTask = previewUpdater.submit(() -> {
			final int quarterResFactor = fullResCheckBox.isSelected() ? 1 : 4;
			try {
				clearImage(previewImage);

				// We use a direct int array because it's much faster than calling setRGB()
				int[] pixels = ((DataBufferInt) previewImage.getRaster().getDataBuffer()).getData();

				CoordinatesInWorld topLeft = getTopLeftCoordinates();
				CoordinatesInWorld bottomRight = getBottomRightCoordinates();

				int worldWidth = (int) (bottomRight.getX() - topLeft.getX());
				int worldHeight = (int) -(topLeft.getY() - bottomRight.getY());

				int worldLongestSide = Math.max(worldWidth, worldHeight);

				double imgToWorldFactor = worldLongestSide / (double) PREVIEW_SIZE;

				int imgXOffset = (int) (((worldLongestSide - worldWidth) / 2) / imgToWorldFactor);
				int imgYOffset = (int) (((worldLongestSide - worldHeight) / 2) / imgToWorldFactor);

				int imgHeightWithoutBorders = previewImage.getHeight() - imgYOffset * 2;
				int imgWidthWithoutBorders = previewImage.getWidth() - imgXOffset * 2;
				for (int y = 0; y < imgHeightWithoutBorders; y++) {
					for (int x = 0; x < imgWidthWithoutBorders; x++) {
						int worldX = (int) ((x * imgToWorldFactor + topLeft.getX()) / quarterResFactor);
						int worldY = (int) ((y * imgToWorldFactor + topLeft.getY()) / quarterResFactor);
						int imgX = x + imgXOffset;
						int imgY = y + imgYOffset;

						// we use imgY instead of (previewImage.getHeight() - imgY - 1) to mirror the y
						// axis
						int imgidx = imgY * previewImage.getWidth() + imgX;
						Biome biome = biomeDataOracle.getBiomeAt(worldX, worldY, !fullResCheckBox.isSelected());
						pixels[imgidx] = biomeProfileSelection.getBiomeColorOrUnknown(biome.getId()).getRGB();
					}
				}

				previewIcon.setImage(previewImage.getScaledInstance(previewIcon.getIconWidth(),
						previewIcon.getIconHeight(), Image.SCALE_FAST));

				SwingUtilities.invokeLater(() -> previewLabel.repaint());
			} catch (MinecraftInterfaceException | UnknownBiomeIdException e) {
				AmidstLogger.error(e);
			}
		});
	}

	private void clearImage(BufferedImage img) {
		Graphics2D graphics = (Graphics2D) img.getGraphics();
		Composite tempComposite = graphics.getComposite();
		graphics.setComposite(AlphaComposite.Clear);
		graphics.fillRect(0, 0, img.getWidth(), img.getHeight());
		graphics.setComposite(tempComposite);
	}

	public void createAndShow(World world, FragmentGraphToScreenTranslator translator,
			Consumer<Entry<ProgressEntryType, Integer>> progressListener) {

		disableMenuItems();

		updateFields(world, translator);

		renderPreview();
		showDialog();
	}

	private void disableMenuItems() {
		menuBarSupplier.get().setMenuItemsEnabled(new String[] { "Export Biomes to Image ...", "Biome Profile" },
				false);
	}

	private void updateFields(World world, FragmentGraphToScreenTranslator translator) {
		setWorldOptionsAndBiomeData(world);
		setCoordinatesInWorld(translator);
		setPath();
	}

	private void setWorldOptionsAndBiomeData(World world) {
		this.worldOptions = world.getWorldOptions();
		this.biomeDataOracle = world.getOverworldBiomeDataOracle();
	}

	private void setCoordinatesInWorld(FragmentGraphToScreenTranslator translator) {
		CoordinatesInWorld defaultTopLeft = translator.screenToWorld(new Point(0, 0));
		CoordinatesInWorld defaultBottomRight = translator
				.screenToWorld(new Point((int) translator.getWidth(), (int) translator.getHeight()));

		leftSpinner.setValue(defaultTopLeft.getX());
		topSpinner.setValue(defaultTopLeft.getY());
		rightSpinner.setValue(defaultBottomRight.getX());
		bottomSpinner.setValue(defaultBottomRight.getY());
	}

	private void setPath() {
		pathField.setText(Paths.get(lastBiomeExportPath.get(), getSuggestedFilename()).toAbsolutePath().toString());
	}

	private void showDialog() {
		dialog.setVisible(true);
	}

	public void dispose() {
		menuBarSupplier.get().setMenuItemsEnabled(new String[] { "Export Biomes to Image ...", "Biome Profile" }, true);
		SwingUtils.destroyComponentTree(dialog);
	}

	public void softDispose() {
		menuBarSupplier.get().setMenuItemsEnabled(new String[] { "Export Biomes to Image ...", "Biome Profile" }, true);
		dialog.dispose();
	}

	private CoordinatesInWorld getTopLeftCoordinates() {
		return new CoordinatesInWorld(((Number) leftSpinner.getValue()).longValue(),
				((Number) topSpinner.getValue()).longValue());
	}

	private CoordinatesInWorld getBottomRightCoordinates() {
		return new CoordinatesInWorld(((Number) rightSpinner.getValue()).longValue(),
				((Number) bottomSpinner.getValue()).longValue());
	}

	private void setConstraints(int iTop, int iLeft, int iBottom, int iRight, int fillConst, int gridx,
			int gridy, int gridw, int gridh, double weightx, double weighty, int anchor) {
		constraints.insets = new Insets(iTop, iLeft, iBottom, iRight);
		constraints.fill = fillConst;
		constraints.gridx = gridx;
		constraints.gridy = gridy;
		constraints.gridwidth = gridw;
		constraints.gridheight = gridh;
		constraints.weightx = weightx;
		constraints.weighty = weighty;
		constraints.anchor = anchor;
	}

	private void setLabelPaneConstraints(int iTop, int iLeft, int iBottom, int iRight, int fillConst, int gridx,
			int gridy, int gridw, int gridh, double weightx, double weighty, int anchor) {
		labelPaneConstraints.insets = new Insets(iTop, iLeft, iBottom, iRight);
		labelPaneConstraints.fill = fillConst;
		labelPaneConstraints.gridx = gridx;
		labelPaneConstraints.gridy = gridy;
		labelPaneConstraints.gridwidth = gridw;
		labelPaneConstraints.gridheight = gridh;
		labelPaneConstraints.weightx = weightx;
		labelPaneConstraints.weighty = weighty;
		labelPaneConstraints.anchor = anchor;
	}

}