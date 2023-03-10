package amidst.gui.profileselect;

import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import amidst.Application;
import amidst.ResourceLoader;
import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.mojangapi.LauncherProfileRunner;
import amidst.mojangapi.RunningLauncherProfile;
import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.file.UnresolvedLauncherProfile;
import amidst.mojangapi.file.VersionListProvider;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.parsing.FormatException;
import amidst.threading.WorkerExecutor;

@NotThreadSafe
public class LocalProfileComponent extends ProfileComponent {
	private final Application application;
	private final WorkerExecutor workerExecutor;
	private final VersionListProvider versionListProvider;
	private final LauncherProfileRunner launcherProfileRunner;
	private final UnresolvedLauncherProfile unresolvedProfile;
	private final Image scaledProfileIcon;

	private volatile boolean isResolving = false;
	private volatile boolean failedResolving = false;
	private volatile boolean isLoading = false;
	private volatile boolean failedLoading = false;
	private volatile LauncherProfile resolvedProfile;

	@CalledOnlyBy(AmidstThread.EDT)
	public LocalProfileComponent(
			Application application,
			WorkerExecutor workerExecutor,
			VersionListProvider versionListProvider,
			LauncherProfileRunner launcherProfileRunner,
			UnresolvedLauncherProfile unresolvedProfile) {
		this.application = application;
		this.workerExecutor = workerExecutor;
		this.versionListProvider = versionListProvider;
		this.launcherProfileRunner = launcherProfileRunner;
		this.unresolvedProfile = unresolvedProfile;
		this.scaledProfileIcon = createScaledProfileIcon();
		initComponent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public Image createScaledProfileIcon() {
		String icon = unresolvedProfile.getIcon();
		if (icon == null || icon.isEmpty()) {
			return null;
		}

		Image image = null;
		if (icon.startsWith("data:image/png;base64,")) {
			try {
				byte[] decoded = Base64.getDecoder().decode(icon.substring("data:image/png;base64,".length()));
				try (ByteArrayInputStream stream = new ByteArrayInputStream(decoded)) {
					image = ImageIO.read(stream);
				}
			} catch (IOException e) {
				AmidstLogger.warn("Unable to decode base64 icon");
			}
		} else {
			try {
				image = ImageIO.read(ResourceLoader.getResourceURL("/amidst/icon/profileicons/" + icon + ".png"));
			} catch (IOException | IllegalArgumentException e) {
				AmidstLogger.error("Error reading icon: " + icon);
			}
		}

		return image == null ? null : image.getScaledInstance(32, 32, Image.SCALE_SMOOTH);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	public void resolveLater() {
		resolvedProfile = null;
		isResolving = true;
		repaintComponent();
		workerExecutor.run(this::tryResolve, this::resolveFinished);
	}

	@CalledOnlyBy(AmidstThread.WORKER)
	private Optional<LauncherProfile> tryResolve() {
		try {
			return Optional.of(unresolvedProfile.resolveToVanilla(versionListProvider.getRemoteOrElseLocal()));
		} catch (FormatException | IOException e) {
			AmidstLogger.warn(e);
			return Optional.empty();
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void resolveFinished(Optional<LauncherProfile> launcherProfile) {
		isResolving = false;
		failedResolving = !launcherProfile.isPresent();
		resolvedProfile = launcherProfile.orElse(null);
		repaintComponent();
	}

	@Override
	protected Image getScaledProfileIcon() {
		return scaledProfileIcon;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	public void load() {
		isLoading = true;
		repaintComponent();
		displayModdedMinecraftInfoIfNecessary();
		workerExecutor.run(this::tryLoad, this::loadFinished);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void displayModdedMinecraftInfoIfNecessary() {
		if (!resolvedProfile.isVersionListedInProfile()) {
			String message = "Amidst does not support modded Minecraft versions! Using underlying vanilla Minecraft version "
					+ resolvedProfile.getVersionId() + " instead.";
			AmidstLogger.info(message);
			AmidstMessageBox.displayInfo("Info", message);
		}
	}

	@CalledOnlyBy(AmidstThread.WORKER)
	private Optional<RunningLauncherProfile> tryLoad() {
		String profileName = resolvedProfile.getProfileName();
		String versionName = resolvedProfile.getVersionName();
		try {
			AmidstLogger.info(String.format("Using Minecraft launcher profile '%s' with versionId '%s'", profileName,
					versionName));
			return Optional.of(launcherProfileRunner.run(resolvedProfile));
		} catch (MinecraftInterfaceCreationException e) {
			String errorMessage = String.format("Error while using profile '%s' with versionId '%s'", profileName,
					versionName);
			AmidstLogger.error(errorMessage, e);
			AmidstMessageBox.displayError("Error", errorMessage);
			return Optional.empty();
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void loadFinished(Optional<RunningLauncherProfile> runningProfile) {
		isLoading = false;
		failedLoading = runningProfile.isEmpty();
		repaintComponent();
		runningProfile.ifPresent(application::displayMainWindow);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected boolean isResolving() {
		return isResolving;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected boolean failedResolving() {
		return failedResolving;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected boolean isLoading() {
		return isLoading;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected boolean failedLoading() {
		return failedLoading;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected boolean isReadyToLoad() {
		return resolvedProfile != null;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected String getProfileName() {
		return unresolvedProfile.getName();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected String getVersionName() {
		return resolvedProfile != null ? resolvedProfile.getVersionName() : "";
	}
}