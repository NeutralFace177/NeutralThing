package io.bluestaggo.voxelthing;

import io.bluestaggo.pds.CompoundItem;
import io.bluestaggo.voxelthing.assets.Texture;
import io.bluestaggo.voxelthing.gui.screen.*;
import io.bluestaggo.voxelthing.renderer.MainRenderer;
import io.bluestaggo.voxelthing.renderer.draw.Quad;
import io.bluestaggo.voxelthing.util.OperatingSystem;
import io.bluestaggo.voxelthing.window.ClientPlayerController;
import io.bluestaggo.voxelthing.window.Window;
import io.bluestaggo.voxelthing.world.BlockRaycast;
import io.bluestaggo.voxelthing.world.ClientWorld;
import io.bluestaggo.voxelthing.world.World;
import io.bluestaggo.voxelthing.world.WorldInfo;
import io.bluestaggo.voxelthing.world.block.Block;
import io.bluestaggo.voxelthing.world.entity.IPlayerController;
import io.bluestaggo.voxelthing.world.entity.Player;
import io.bluestaggo.voxelthing.world.generation.Structures;
import io.bluestaggo.voxelthing.world.storage.FolderSaveHandler;
import io.bluestaggo.voxelthing.world.storage.ISaveHandler;

import javax.swing.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.glClearColor;
import static org.lwjgl.opengl.GL33C.glClearColor;

public class Game {
	public static final String VERSION;
	public static final int TICKS_PER_SECOND = 20;
	public static final float TICK_RATE = 1.0f / TICKS_PER_SECOND;

	public boolean structureMode = false;
	public boolean structurePaintMode = false;
	public Structures structureToPaint = Structures.House;

	static {
		String version = "???";

		try (InputStream stream = Game.class.getResourceAsStream("/version.txt")) {
			if (stream == null) {
				version = "dev " + new SimpleDateFormat("yyyyMMdd").format(new Date()) + "?";
			} else {
				var reader = new BufferedReader(new InputStreamReader(stream));
				version = reader.readLine();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		VERSION = version;
	}

	private static final String[] SKINS = {
			"joel",
			"staggo",
			"floof",
			"talon"
	};
	private int currentSkin = VERSION.contains("dev") || VERSION.equals("???") ? 1 : 0;
	private boolean thirdPerson;
	private boolean debugMenu = true;
	private boolean drawGui = true;
	private boolean viewBobbing = true;

	private static Game instance;

	public final Path saveDir;
	public final Path worldDir;

	public final Window window;
	public final MainRenderer renderer;

	public World world;
	public Player player;
	public IPlayerController playerController;

	public int heldItem;

	private final GuiScreen debugGui;
	private final GuiScreen inGameGui;
	private GuiScreen currentGui;

	private BlockRaycast blockRaycast;

	public float mouseSensitivity = 0.25f;

	private double tickTime;
	private double partialTick;


	public Game() {
		instance = this;

		saveDir = getSaveDir();
		worldDir = saveDir.resolve("worlds");

		window = new Window();
		window.grabCursor();

		renderer = new MainRenderer(this);

		debugGui = new DebugGui(this);
		inGameGui = new IngameGui(this);

		glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

		openGui(new MainMenu(this));
	}

	public static Game getInstance() {
		return instance;
	}

	private static Path getSaveDir() {
		String home = System.getProperty("user.home", ".");
		Path dir = switch (OperatingSystem.get()) {
			case WINDOWS -> Path.of(Optional.ofNullable(System.getenv("APPDATA")).orElse(home), "VoxelThing");
			case MACOS -> Path.of(home, "Library", "Application Support", "Voxel Thing");
			default -> Path.of(home, ".voxelthing");
		};

		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create Voxel Thing data directory!", e);
		}

		return dir;
	}

	public void run() {
		try {
			while (!window.shouldClose()) {
				update(window.getDeltaTime());
				draw();
				window.update();
			}
		} catch (Throwable e) {
			var stackTrace = new StringWriter();
			var stackTracePrinter = new PrintWriter(stackTrace);
			stackTracePrinter.println("An exception has occured and the program needs to quit!");
			e.printStackTrace(stackTracePrinter);
			JOptionPane.showMessageDialog(null, stackTrace, "Voxel Thing: Exception", JOptionPane.ERROR_MESSAGE);
			e.printStackTrace();
		}

		close();
	}

	private void close() {
		exitWorld();
		renderer.unload();
		window.destroy();
	}

	public void startWorld() {
		startWorld((ISaveHandler) null, null);
	}

	public void startWorld(ISaveHandler saveHandler) {
		startWorld(saveHandler, null);
	}

	public void startWorld(String name, WorldInfo worldInfo) {
		ISaveHandler saveHandler = null;
		if (name != null) {
			try {
				saveHandler = new FolderSaveHandler(worldDir.resolve(name));
			} catch (IOException e) {
				System.out.println("Cannot save world \"" + name + "\"! Playing without saving.");
				e.printStackTrace();
			}
		}

		startWorld(saveHandler, worldInfo);
	}

	public void startWorld(ISaveHandler saveHandler, WorldInfo worldInfo) {
		exitWorld();

		world = new ClientWorld(this, saveHandler, worldInfo);
		saveHandler = world.saveHandler;
		playerController = new ClientPlayerController(this);
		player = new Player(world, playerController);

		CompoundItem playerData = saveHandler.loadData("player");
		playerData.setDouble("posX", 1000);
		if (playerData != null) {
			player.deserialize(playerData);
		}

		currentGui = null;
		window.grabCursor();
	}

	public void exitWorld() {
		if (world != null) {
			world.saveHandler.saveData("player", player.serialize());
			world.close();
			player = null;
			world = null;
		}
	}

	public boolean isInWorld() {
		return world != null && player != null;
	}

	private void update(double delta) {
		tickTime += delta;

		GuiScreen gui = currentGui != null ? currentGui : isInWorld() ? inGameGui : null;
		if (gui == null) {
			gui = currentGui = new MainMenu(this);
			window.ungrabCursor();
		}

		if (gui == inGameGui) {
			doControls();
		}

		if (gui != null) {
			gui.handleInput();
		}

		if (isInWorld()) {
			player.onGameUpdate();
			player.noClip = window.isKeyDown(GLFW_KEY_Q);
			player.hotbar.tick();
			if (!player.alive) {
				openGui(new RespawnMenu(instance));
			}
		}

		if (tickTime >= TICK_RATE) {
			tickTime %= TICK_RATE;
			if (currentGui != null) {
				currentGui.tick();
			}

			if (isInWorld()) {
				inGameGui.tick();
				player.tick();
			}
		}

		partialTick = tickTime / TICK_RATE;

		if (isInWorld()) {
			world.partialTick = partialTick;

			float px = (float) player.getPartialX();
			float py = (float) player.getPartialY();
			float pz = (float) player.getPartialZ();
			float yaw = (float) player.rotYaw;
			float pitch = (float) player.rotPitch;

			py += player.height - 0.3;
			if (viewBobbing) {
				if (thirdPerson) {
					py -= player.getPartialVelY() * 0.2;
				}
				pitch += player.getFallAmount() * 5.0;
			}

			renderer.camera.setPosition(px, py, pz);
			renderer.camera.setRotation(yaw, pitch);

			if (world != null) {
				float length = structurePaintMode == true ? 512 : 10;
				blockRaycast = renderer.camera.getRaycast(length);
				world.doRaycast(blockRaycast);
			}

			if (thirdPerson) {
				renderer.camera.moveForward(-4.0f);
			} else if (viewBobbing) {
				py += Math.abs(player.getRenderWalk()) * 0.2f;
				renderer.camera.setPosition(px, py, pz);
				renderer.camera.moveRight((float) player.getRenderWalk() * 0.1f);
			}
		}
	}

	private void doControls() {
		if (window.isKeyJustPressed(GLFW_KEY_F)) {
			int dist = renderer.worldRenderer.renderDistance;
			if (window.isKeyDown(GLFW_KEY_LEFT_SHIFT)) {
				if (dist < 16) {
					dist <<= 1;
				}
			} else if (dist > 1) {
				dist >>= 1;
			}

			renderer.worldRenderer.renderDistance = dist;
			renderer.worldRenderer.loadRenderers();
		}

		if (window.isKeyJustPressed(GLFW_KEY_R) && !player.survival) {
		//	player.posX = world.random.nextDouble(-1000.0, 1000.0);
		//	player.posY = 64.0;
		//	player.posZ = world.random.nextDouble(-1000.0, 1000.0);

			//conversion factor for deg to rad
			double cf = Math.PI/180; 
			double speed = 25;
			player.velX = Math.cos(cf * player.rotYaw) * Math.cos(cf * player.rotPitch) * speed;
			player.velZ = Math.sin(cf * player.rotYaw) * Math.cos(cf * player.rotPitch) * speed;
			player.velY = Math.sin(cf * player.rotPitch) * speed/10;
		}

		if (window.isKeyJustPressed(GLFW_KEY_LEFT_BRACKET) && renderer.screen.scale > 0.0f) {
			renderer.screen.scale -= 0.5f;
		}

		if (window.isKeyJustPressed(GLFW_KEY_RIGHT_BRACKET)) {
			renderer.screen.scale += 0.5f;
		}

		if (window.isKeyJustPressed(GLFW_KEY_F1)) {
			drawGui = !drawGui;
		}

		if (window.isKeyJustPressed(GLFW_KEY_F3)) {
			debugMenu = !debugMenu;
		}

		if (window.isKeyJustPressed(GLFW_KEY_F4)) {
			viewBobbing = !viewBobbing;
		}

		if (window.isKeyJustPressed(GLFW_KEY_F5)) {
			thirdPerson = !thirdPerson;
		}

		if (window.isKeyJustPressed(GLFW_KEY_F6)) {
			if (++currentSkin >= SKINS.length) currentSkin = 0;
		}

		if (window.isKeyJustPressed(GLFW_KEY_ESCAPE)) {
			openGui(new PauseMenu(this));
		}

		if (window.isKeyJustPressed(GLFW_KEY_E)) {
			openGui(new BlockInventory(this));
		}
		if (window.isKeyJustPressed(GLFW_KEY_T)) {
			openGui(new Chat(this));
		}
		if (window.isKeyJustPressed(GLFW_KEY_EQUAL)) {
			player.health++;
		}
		if (window.isKeyJustPressed(GLFW_KEY_MINUS)) {
			player.health--;
		}
	}

	private void draw() {
		renderer.draw();

		if (drawGui) {
			if (isInWorld()) {
				if (debugMenu) {
					debugGui.draw();
				}
				inGameGui.draw();
			} else {
				Texture bgTex = renderer.textures.getTexture("/assets/gui/background.png");

				float width = renderer.screen.getWidth();
				float height = renderer.screen.getHeight();

				renderer.draw2D.drawQuad(Quad.shared()
						.size(width, height)
						.withTexture(bgTex)
						.withUV(0.0f, 0.0f, width / bgTex.width, height / bgTex.height)
				);
			}

			if (currentGui != null) {
				currentGui.draw();
			}
		}
	}

	public void openGui(GuiScreen gui) {
		if (gui == null) {
			if (currentGui.parent != null) {
				gui = currentGui.parent;
			} else if (!isInWorld()) {
				gui = new MainMenu(this);
			}
		}

		currentGui = gui;
		if (gui == null) {
			window.grabCursor();
		} else {
			window.ungrabCursor();
		}
	}

	public void closeGui() {
		openGui(null);
	}

	public boolean isGuiOpen() {
		return currentGui != null;
	}

	public String getSkin() {
		return "/assets/entities/" + SKINS[currentSkin] + ".png";
	}

	public boolean showThirdPerson() {
		return thirdPerson;
	}

	public double getPartialTick() {
		return partialTick;
	}

	public BlockRaycast getBlockRaycast() {
		return blockRaycast;
	}

	public boolean viewBobbingEnabled() {
		return viewBobbing;
	}

	public static void main(String[] args) {
		new Game().run();
	}
}
