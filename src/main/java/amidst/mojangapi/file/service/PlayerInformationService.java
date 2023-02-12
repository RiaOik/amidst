package amidst.mojangapi.file.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import amidst.documentation.Immutable;
import amidst.documentation.NotNull;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.file.json.player.PlayerJson;
import amidst.mojangapi.file.json.player.PropertyJson;
import amidst.mojangapi.file.json.player.SKINJson;
import amidst.mojangapi.file.json.player.SimplePlayerJson;
import amidst.mojangapi.file.json.player.TexturesJson;
import amidst.mojangapi.file.json.player.TexturesPropertyJson;
import amidst.mojangapi.world.icon.WorldIconImage;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;
import amidst.mojangapi.world.player.PlayerInformation;
import amidst.parsing.FormatException;
import amidst.parsing.json.JsonReader;

@Immutable
public class PlayerInformationService {
	private static final WorldIconImage DEFAULT_HEAD = DefaultWorldIconTypes.PLAYER.getImage();

	private static final String SIMPLE_PLAYER_SKIN_URL = "http://s3.amazonaws.com/MinecraftSkins/";
	private static final String PLAYERNAME_TO_UUID = "https://api.mojang.com/users/profiles/minecraft/";
	private static final String UUID_TO_PROFILE = "https://sessionserver.mojang.com/session/minecraft/profile/";

	@NotNull
	public PlayerInformation fromUUID(String uuid) {
		Optional<PlayerJson> optionalPlayer = tryGetPlayerJsonByUUID(uuid);
		WorldIconImage playerHead = DEFAULT_HEAD;
		if (optionalPlayer.isPresent()) {
			PlayerJson player = optionalPlayer.get();
			Optional<WorldIconImage> head = tryGetSkinUrl(player).flatMap(this::tryGetPlayerHeadBySkinUrl);
			playerHead = head.orElse(DEFAULT_HEAD);
		}
		return new PlayerInformation(uuid, optionalPlayer.map(PlayerJson::getName).orElse(null), playerHead);
	}

	@NotNull
	public PlayerInformation fromName(String name) {
		Optional<PlayerJson> optionalPlayer = tryGetPlayerJsonByName(name);
		Optional<WorldIconImage> head = Optional.empty();
		if (optionalPlayer.isPresent()) {
			PlayerJson player = optionalPlayer.get();
			head = tryGetSkinUrl(player).flatMap(this::tryGetPlayerHeadBySkinUrl);
		}
		if (head.isPresent()) {
			return new PlayerInformation(optionalPlayer.map(PlayerJson::getId).orElse(null), name, head.get());
		} else {
			head = tryGetPlayerHeadByName(name);
			return new PlayerInformation(optionalPlayer.map(PlayerJson::getId).orElse(null), name,
					head.orElse(DEFAULT_HEAD));
		}
	}

	@NotNull
	private Optional<String> tryGetSkinUrl(PlayerJson playerJson) {
		return tryReadTexturesProperty(playerJson)
				.map(TexturesPropertyJson::getTextures)
				.map(TexturesJson::getSKIN)
				.map(SKINJson::getUrl);
	}

	@NotNull
	private Optional<TexturesPropertyJson> tryReadTexturesProperty(PlayerJson playerJson) {
		try {
			for (PropertyJson property : playerJson.getProperties()) {
				if (isTexturesProperty(property)) {
					return Optional.of(JsonReader.readString(getDecodedValue(property), TexturesPropertyJson.class));
				}
			}
			return Optional.empty();
		} catch (FormatException e) {
			return Optional.empty();
		}
	}

	private boolean isTexturesProperty(PropertyJson propertyJson) throws FormatException {
		return "textures".equals(propertyJson.getName());
	}

	@NotNull
	private String getDecodedValue(PropertyJson propertyJson) {
		String value = propertyJson.getValue();
		return value == null ? null
				: new String(Base64.getDecoder().decode(value.getBytes(StandardCharsets.UTF_8)),
						StandardCharsets.UTF_8);
	}

	@NotNull
	private Optional<PlayerJson> tryGetPlayerJsonByName(String name) {
		try {
			return Optional.ofNullable(getPlayerJsonByName(name));
		} catch (IOException | FormatException e) {
			AmidstLogger.warn("unable to load player information by name: {}", name);
			return Optional.empty();
		}
	}

	@NotNull
	private Optional<PlayerJson> tryGetPlayerJsonByUUID(String uuid) {
		try {
			return Optional.of(getPlayerJsonByUUID(uuid));
		} catch (IOException | FormatException | NullPointerException e) {
			AmidstLogger.warn("unable to load player information by uuid: {}", uuid);
			return Optional.empty();
		}
	}

	@NotNull
	private PlayerJson getPlayerJsonByName(String name) throws FormatException, IOException {
		return getPlayerJsonByUUID(getUUIDByName(name).getId());
	}

	@NotNull
	private PlayerJson getPlayerJsonByUUID(String uuid) throws FormatException, IOException {
		return JsonReader.readLocation(UUID_TO_PROFILE + uuid, PlayerJson.class);
	}

	@NotNull
	private SimplePlayerJson getUUIDByName(String name) throws FormatException, IOException {
		return JsonReader.readLocation(PLAYERNAME_TO_UUID + name, SimplePlayerJson.class);
	}

	@NotNull
	private Optional<WorldIconImage> tryGetPlayerHeadByName(String name) {
		try {
			return Optional.of(WorldIconImage.from(getPlayerHeadByName(name)));
		} catch (IOException | NullPointerException e) {
			AmidstLogger.warn("unable to load player head by name: {}", name);
			return Optional.empty();
		}
	}

	@NotNull
	private Optional<WorldIconImage> tryGetPlayerHeadBySkinUrl(String skinUrl) {
		try {
			return Optional.of(WorldIconImage.from(getPlayerHeadBySkinUrl(skinUrl)));
		} catch (IOException | NullPointerException e) {
			AmidstLogger.warn("unable to load player head by skin url: {}", skinUrl);
			return Optional.empty();
		}
	}

	@NotNull
	private BufferedImage getPlayerHeadByName(String name) throws IOException {
		return extractPlayerHead(new URL(SIMPLE_PLAYER_SKIN_URL + name + ".png"));
	}

	@NotNull
	private BufferedImage getPlayerHeadBySkinUrl(String skinUrl) throws IOException {
		return extractPlayerHead(new URL(skinUrl));
	}

	@NotNull
	private BufferedImage extractPlayerHead(URL url) throws IOException {
		return extractPlayerHead(ImageIO.read(url));
	}

	@NotNull
	private BufferedImage extractPlayerHead(BufferedImage skin) {
		BufferedImage head = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = head.createGraphics();
		g2d.setColor(Color.black);
		g2d.fillRect(0, 0, 20, 20);
		g2d.drawImage(skin, 2, 2, 18, 18, 8, 8, 16, 16, null);
		g2d.dispose();
		skin.flush();
		return head;
	}
}