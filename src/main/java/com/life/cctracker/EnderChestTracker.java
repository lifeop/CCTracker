package com.life.cctracker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.client.FMLClientHandler;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MovingObjectPosition.MovingObjectType;
import org.lwjgl.opengl.GL11;

public class EnderChestTracker {
    private static final Pattern COUNT_PATTERN = Pattern.compile("\\[([\\d.,]+[KMkmB]?)\\s*/\\s*([\\d.,]+[KMkmB]?)\\]", Pattern.CASE_INSENSITIVE);
    private static final int DISPLAY_DURATION_TICKS = 100;
    private static final double HIGHLIGHT_MAX_DISTANCE = 64.0;
    private GuiChest currentGui = null;
    private BlockPos currentChestPos = null;
    private boolean shouldProcessNextTick = false;
    private int retryCount = 0;
    private static final int MAX_RETRIES = 5;

    private String displayText = null;
    private long displayStartTick = 0;
    private boolean silentReadMode = false;
    private BlockPos clickedChestPos = null;
    private boolean rightClickSentForSilentRead = false;
    private boolean closeSilentReadGuiNextTick = false;

    private final Map<BlockPos, TitleInfo> storedChestData = new HashMap<BlockPos, TitleInfo>();
    private BlockPos currentlyLookingAt = null;

    private boolean highlightEnabled = true;

    public static volatile boolean dropNextDigPacket = false;
    public static volatile BlockPos dropNextDigPacketPos = null;

    private static volatile EnderChestTracker clientTrackerInstance = null;
    public static void setClientTracker(EnderChestTracker tracker) { clientTrackerInstance = tracker; }
    public static EnderChestTracker getClientTracker() { return clientTrackerInstance; }

    public void startSilentRead(BlockPos pos) {
        if (pos == null) return;
        try {
            Minecraft mc = getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
            if (mc.theWorld.getBlockState(pos).getBlock() != Blocks.ender_chest) return;
            clickedChestPos = pos;
            silentReadMode = true;
            shouldProcessNextTick = true;
            rightClickSentForSilentRead = false;
            retryCount = 0;
            dropNextDigPacket = false;
            dropNextDigPacketPos = null;
        } catch (Throwable t) { }
    }

    private int lastLoadedDimension = Integer.MIN_VALUE;
    private boolean dataDirty = false;
    private int saveTicksRemaining = 0;
    private static final int SAVE_DEBOUNCE_TICKS = 100;

    public boolean isHighlightEnabled() {
        return highlightEnabled;
    }

    public void setHighlightEnabled(boolean enabled) {
        this.highlightEnabled = enabled;
    }

    public void clearAllData() {
        storedChestData.clear();
        displayText = null;
        currentlyLookingAt = null;
        dataDirty = true;
        Minecraft mc = getMinecraft();
        if (mc != null && mc.theWorld != null && mc.theWorld.isRemote) {
            saveData(mc.theWorld);
        }
    }

    private void markDataDirty() {
        this.dataDirty = true;
    }

    private static File getDataFile() {
        File dir = new File(getMinecraft().mcDataDir, "config/cctracker");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "chest_data.json");
    }

    private static int getDimensionId(World world) {
        if (world == null || world.provider == null) {
            return 0;
        }
        try {
            return world.provider.getDimensionId();
        } catch (Exception e) {
            try {
                Field f = world.provider.getClass().getSuperclass().getDeclaredField("dimensionId");
                f.setAccessible(true);
                return f.getInt(world.provider);
            } catch (Exception e2) {
                return 0;
            }
        }
    }

    private void loadData(World world) {
        if (world == null || !world.isRemote) {
            return;
        }
        try {
            File file = getDataFile();
            if (!file.exists()) {
                return;
            }
            Gson gson = new Gson();
            java.lang.reflect.Type listType = new TypeToken<List<ChestDataEntry>>(){}.getType();
            List<ChestDataEntry> list = null;
            FileReader reader = null;
            try {
                reader = new FileReader(file);
                list = gson.fromJson(reader, listType);
            } finally {
                if (reader != null) {
                    try { reader.close(); } catch (IOException ignored) {}
                }
            }
            if (list == null) {
                return;
            }
            int dim = getDimensionId(world);
            storedChestData.clear();
            for (ChestDataEntry e : list) {
                if (e.dim == dim) {
                    storedChestData.put(e.toBlockPos(), e.toTitleInfo());
                }
            }
        } catch (Exception e) {
        }
    }

    private void saveData(World world) {
        if (world == null || !world.isRemote) {
            return;
        }
        try {
            File file = getDataFile();
            Gson gson = new Gson();
            java.lang.reflect.Type listType = new TypeToken<List<ChestDataEntry>>(){}.getType();
            List<ChestDataEntry> existing = new ArrayList<ChestDataEntry>();
            if (file.exists()) {
                FileReader reader = null;
                try {
                    reader = new FileReader(file);
                    List<ChestDataEntry> read = gson.fromJson(reader, listType);
                    if (read != null) {
                        existing = read;
                    }
                } finally {
                    if (reader != null) {
                        try { reader.close(); } catch (IOException ignored) {}
                    }
                }
            }
            int dim = getDimensionId(world);
            List<ChestDataEntry> out = new ArrayList<ChestDataEntry>();
            for (ChestDataEntry e : existing) {
                if (e.dim != dim) {
                    out.add(e);
                }
            }
            for (Map.Entry<BlockPos, TitleInfo> entry : storedChestData.entrySet()) {
                out.add(new ChestDataEntry(dim, entry.getKey(), entry.getValue()));
            }
            FileWriter writer = null;
            try {
                writer = new FileWriter(file);
                gson.toJson(out, writer);
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (IOException ignored) {}
                }
            }
            dataDirty = false;
        } catch (Exception e) {
        }
    }

    private static Minecraft getMinecraft() {
        return FMLClientHandler.instance().getClient();
    }

    private void sendCloseWindowToServer(Minecraft mc) {
        try {
            if (mc == null || mc.thePlayer == null) return;
            if (mc.thePlayer.openContainer != null && mc.thePlayer.openContainer != mc.thePlayer.inventoryContainer) {
                mc.thePlayer.closeScreen();
            }
        } catch (Throwable t) { }
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        try {
            if (!highlightEnabled) return;
            if (event.state != null && event.state.getBlock() == Blocks.ender_chest) {
                if (event.isCancelable()) {
                    event.setCanceled(true);
                }
                event.newSpeed = 0.0f;
            }
        } catch (Throwable t) {
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK) {
            return;
        }
        if (!highlightEnabled) return;

        BlockPos pos = event.pos;
        if (pos == null || event.world.getBlockState(pos).getBlock() != Blocks.ender_chest) {
            return;
        }

        if (!event.world.isRemote) {
            event.setCanceled(true);
            return;
        }

        if (!(event.entityPlayer instanceof EntityPlayerSP)) {
            return;
        }

        try {
            Minecraft mc = getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }

            if (pos != null && mc.theWorld.getBlockState(pos).getBlock() == Blocks.ender_chest) {
                event.setCanceled(true);
                dropNextDigPacket = true;
                dropNextDigPacketPos = pos;
                clickedChestPos = pos;
                silentReadMode = true;

                try {
                    net.minecraft.inventory.IInventory enderInventory = mc.thePlayer.getInventoryEnderChest();
                    if (enderInventory != null) {
                        String name = enderInventory.getName();
                        if (name != null && !name.isEmpty() && !name.equals("container.enderchest")) {
                            TitleInfo titleInfo = parseTitle(name);
                            if (!titleInfo.countText.isEmpty()) {
                                storedChestData.put(pos, titleInfo);
                                markDataDirty();
                                createDisplayText(mc.theWorld, titleInfo);
                                silentReadMode = false;
                                clickedChestPos = null;
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                }

                shouldProcessNextTick = true;
                retryCount = 0;
                rightClickSentForSilentRead = false;
            }
        } catch (Exception e) {
            silentReadMode = false;
            clickedChestPos = null;
            rightClickSentForSilentRead = false;
        }
    }

    @SubscribeEvent(priority = net.minecraftforge.fml.common.eventhandler.EventPriority.HIGHEST)
    public void onGuiOpen(GuiOpenEvent event) {
        try {
            if (event.gui != null && event.gui instanceof GuiChest) {
                GuiChest guiChest = (GuiChest) event.gui;
                if (silentReadMode && clickedChestPos != null) {
                    Minecraft mc = getMinecraft();
                    if (mc != null && mc.theWorld != null) {
                        currentGui = guiChest;
                        String title = getGuiTitle(guiChest);
                        TitleInfo titleInfo = parseTitle(title);
                        if (!titleInfo.countText.isEmpty()) {
                            storedChestData.put(clickedChestPos, titleInfo);
                            markDataDirty();
                            createDisplayText(mc.theWorld, titleInfo);
                            closeSilentReadGuiNextTick = true;
                            silentReadMode = false;
                            clickedChestPos = null;
                            currentGui = null;
                            shouldProcessNextTick = false;
                            retryCount = 0;
                            rightClickSentForSilentRead = false;
                        } else {
                            shouldProcessNextTick = true;
                            retryCount = 0;
                        }
                    }
                } else {
                    currentGui = guiChest;
                    Minecraft mc = getMinecraft();
                    if (mc != null && mc.theWorld != null) {
                        BlockPos lookingAt = getBlockPlayerIsLookingAt(mc);
                        if (lookingAt != null && mc.theWorld.getBlockState(lookingAt).getBlock() == Blocks.ender_chest) {
                            currentChestPos = lookingAt;
                        }
                    }
                    shouldProcessNextTick = true;
                    retryCount = 0;
                }
            } else {
                currentGui = null;
                currentChestPos = null;
                shouldProcessNextTick = false;
                retryCount = 0;
                if (!silentReadMode) {
                    silentReadMode = false;
                    clickedChestPos = null;
                }
                rightClickSentForSilentRead = false;
            }
        } catch (Exception e) {
            currentGui = null;
            currentChestPos = null;
            shouldProcessNextTick = false;
            silentReadMode = false;
            clickedChestPos = null;
            rightClickSentForSilentRead = false;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        try {
            if (event.phase == TickEvent.Phase.END) {
                Minecraft mc = getMinecraft();
                if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                    return;
                }
                World world = mc.theWorld;
                if (!world.isRemote) {
                    return;
                }
                if (closeSilentReadGuiNextTick) {
                    closeSilentReadGuiNextTick = false;
                    try {
                        sendCloseWindowToServer(mc);
                        mc.displayGuiScreen(null);
                    } catch (Throwable t) { }
                }
                int dim = getDimensionId(world);
                if (dim != lastLoadedDimension) {
                    if (dataDirty && lastLoadedDimension != Integer.MIN_VALUE) {
                        saveData(world);
                    }
                    storedChestData.clear();
                    loadData(world);
                    lastLoadedDimension = dim;
                }
                if (dataDirty) {
                    if (saveTicksRemaining <= 0) {
                        saveTicksRemaining = SAVE_DEBOUNCE_TICKS;
                    }
                    saveTicksRemaining--;
                    if (saveTicksRemaining <= 0) {
                        saveData(world);
                        saveTicksRemaining = 0;
                    }
                }

                if (silentReadMode && clickedChestPos != null && currentGui == null && retryCount < 25 && !rightClickSentForSilentRead) {
                    try {
                        boolean sent = false;
                        PlayerControllerMP controller = mc.playerController;
                        if (controller != null) {
                            try {
                                Vec3 hitVec = new Vec3(0.5, 0.5, 0.5);
                                java.lang.reflect.Method[] methods = controller.getClass().getMethods();
                                for (java.lang.reflect.Method method : methods) {
                                    if (method.getName().equals("processRightClickBlock")) {
                                        Class<?>[] params = method.getParameterTypes();
                                        if (params.length == 5 && params[2] == BlockPos.class && params[4] == Vec3.class) {
                                            method.invoke(controller, mc.thePlayer, mc.theWorld, clickedChestPos, EnumFacing.UP, hitVec);
                                            sent = true;
                                            break;
                                        } else if (params.length == 6) {
                                            method.invoke(controller, mc.thePlayer, mc.theWorld, clickedChestPos, EnumFacing.UP, hitVec, mc.thePlayer.getHeldItem());
                                            sent = true;
                                            break;
                                        }
                                    }
                                }
                            } catch (Exception e2) {
                            }
                        }
                        if (!sent) {
                            NetHandlerPlayClient netHandler = mc.thePlayer.sendQueue;
                            if (netHandler != null) {
                                try {
                                    C08PacketPlayerBlockPlacement packet = new C08PacketPlayerBlockPlacement(
                                        clickedChestPos, EnumFacing.UP.getIndex(),
                                        mc.thePlayer.getHeldItem(), 0.5f, 0.5f, 0.5f);
                                    netHandler.addToSendQueue(packet);
                                    sent = true;
                                } catch (Exception e1) {
                                }
                            }
                        }
                        rightClickSentForSilentRead = true;
                    } catch (Exception e) {
                        rightClickSentForSilentRead = true;
                    }
                }
                if (silentReadMode && clickedChestPos != null && currentGui == null && retryCount < 25) {
                    retryCount++;
                    if (retryCount >= 25) {
                        silentReadMode = false;
                        clickedChestPos = null;
                        shouldProcessNextTick = false;
                        rightClickSentForSilentRead = false;
                    }
                }

                if (silentReadMode && shouldProcessNextTick && currentGui != null && clickedChestPos != null && retryCount < MAX_RETRIES) {
                    String title = getGuiTitle(currentGui);
                    TitleInfo titleInfo = parseTitle(title);

                    if (!titleInfo.countText.isEmpty()) {
                        storedChestData.put(clickedChestPos, titleInfo);
                        markDataDirty();
                        createDisplayText(mc.theWorld, titleInfo);
                        sendCloseWindowToServer(mc);
                        mc.displayGuiScreen(null);
                        silentReadMode = false;
                        clickedChestPos = null;
                        currentGui = null;
                        shouldProcessNextTick = false;
                        retryCount = 0;
                        rightClickSentForSilentRead = false;
                    } else {
                        try {
                            net.minecraft.inventory.IInventory upperChest = null;
                            try {
                                Field upperChestField = currentGui.inventorySlots.getClass().getDeclaredField("upperChestInventory");
                                upperChestField.setAccessible(true);
                                upperChest = (net.minecraft.inventory.IInventory) upperChestField.get(currentGui.inventorySlots);
                            } catch (Exception e) {
                                Field[] fields = currentGui.inventorySlots.getClass().getDeclaredFields();
                                for (Field field : fields) {
                                    if (net.minecraft.inventory.IInventory.class.isAssignableFrom(field.getType())) {
                                        field.setAccessible(true);
                                        Object obj = field.get(currentGui.inventorySlots);
                                        if (obj instanceof net.minecraft.inventory.IInventory) {
                                            upperChest = (net.minecraft.inventory.IInventory) obj;
                                            break;
                                        }
                                    }
                                }
                            }

                            if (upperChest != null) {
                                String name = upperChest.getName();
                                if (name != null && !name.isEmpty() && !name.equals("container.enderchest")) {
                                    titleInfo = parseTitle(name);
                                    if (!titleInfo.countText.isEmpty()) {
                                        storedChestData.put(clickedChestPos, titleInfo);
                                        markDataDirty();
                                        createDisplayText(mc.theWorld, titleInfo);
                                        sendCloseWindowToServer(mc);
                                        mc.displayGuiScreen(null);
                                        silentReadMode = false;
                                        clickedChestPos = null;
                                        currentGui = null;
                                        shouldProcessNextTick = false;
                                        retryCount = 0;
                                    } else {
                                        retryCount++;
                                    }
                                } else {
                                    retryCount++;
                                }
                            } else {
                                retryCount++;
                            }
                        } catch (Exception e) {
                            retryCount++;
                        }
                    }

                    if (retryCount >= MAX_RETRIES) {
                        sendCloseWindowToServer(mc);
                        mc.displayGuiScreen(null);
                        silentReadMode = false;
                        clickedChestPos = null;
                        currentGui = null;
                        shouldProcessNextTick = false;
                        retryCount = 0;
                        rightClickSentForSilentRead = false;
                    }
                } else if (!silentReadMode && shouldProcessNextTick && currentGui != null && retryCount < MAX_RETRIES) {
                    BlockPos posToStore = currentChestPos;
                    if (posToStore == null) {
                        posToStore = getBlockPlayerIsLookingAt(mc);
                        if (posToStore != null && mc.theWorld.getBlockState(posToStore).getBlock() != Blocks.ender_chest) {
                            posToStore = null;
                        }
                    }
                    if (posToStore != null) {
                        boolean success = processEnderChest(mc.theWorld, posToStore, currentGui);
                        if (success) {
                            shouldProcessNextTick = false;
                            retryCount = 0;
                        } else {
                            retryCount++;
                        }
                    } else {
                        retryCount++;
                    }
                }

                BlockPos lookingAt = getBlockPlayerIsLookingAt(mc);
                if (lookingAt != null) {
                    TitleInfo storedData = storedChestData.get(lookingAt);

                    if (storedData == null) {
                        for (Map.Entry<BlockPos, TitleInfo> entry : storedChestData.entrySet()) {
                            BlockPos storedPos = entry.getKey();
                            if (storedPos.getX() == lookingAt.getX() &&
                                storedPos.getY() == lookingAt.getY() &&
                                storedPos.getZ() == lookingAt.getZ()) {
                                storedData = entry.getValue();
                                break;
                            }
                        }
                    }

                    if (storedData != null && !storedData.countText.isEmpty()) {
                        if (currentlyLookingAt == null ||
                            currentlyLookingAt.getX() != lookingAt.getX() ||
                            currentlyLookingAt.getY() != lookingAt.getY() ||
                            currentlyLookingAt.getZ() != lookingAt.getZ()) {
                            currentlyLookingAt = lookingAt;
                            createDisplayText(mc.theWorld, storedData);
                        }
                    } else {
                        if (currentlyLookingAt != null &&
                            currentlyLookingAt.getX() == lookingAt.getX() &&
                            currentlyLookingAt.getY() == lookingAt.getY() &&
                            currentlyLookingAt.getZ() == lookingAt.getZ()) {
                            currentlyLookingAt = null;
                            displayText = null;
                        } else if (currentlyLookingAt != null) {
                            currentlyLookingAt = null;
                            displayText = null;
                        }
                    }
                } else {
                    if (currentlyLookingAt != null) {
                        currentlyLookingAt = null;
                        displayText = null;
                    }
                }

                if (displayText != null && mc.theWorld != null && currentlyLookingAt == null) {
                    long currentTick = mc.theWorld.getTotalWorldTime();
                    if (currentTick - displayStartTick >= DISPLAY_DURATION_TICKS) {
                        displayText = null;
                    }
                }
            }
        } catch (Exception e) {
            shouldProcessNextTick = false;
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        try {
            if (event.world != null && event.world.isRemote && dataDirty) {
                saveData(event.world);
            }
        } catch (Exception e) {
        }
    }

    private TileEntityEnderChest findNearbyEnderChest(World world, BlockPos center) {
        try {
            for (int x = -3; x <= 3; x++) {
                for (int y = -3; y <= 3; y++) {
                    for (int z = -3; z <= 3; z++) {
                        BlockPos pos = center.add(x, y, z);
                        if (world.getBlockState(pos).getBlock() == Blocks.ender_chest) {
                            TileEntity te = world.getTileEntity(pos);
                            if (te instanceof TileEntityEnderChest) {
                                return (TileEntityEnderChest) te;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean processEnderChest(World world, BlockPos chestPos, GuiChest guiChest) {
        try {
            if (chestPos == null) {
                return false;
            }

            String title = getGuiTitle(guiChest);

            if (title == null || title.isEmpty() || title.equals("Ender Chest")) {
                return false;
            }

            TitleInfo titleInfo = parseTitle(title);

            if (titleInfo.countText.isEmpty() && titleInfo.percentageText.isEmpty()) {
                return false;
            }

            storedChestData.put(chestPos, titleInfo);
            markDataDirty();

            createDisplayText(world, titleInfo);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class TitleInfo {
        String chestType;
        String countText;
        String percentageText;
        double yValue;
        double percentage;

        TitleInfo(String chestType, String countText, String percentageText, double yValue, double percentage) {
            this.chestType = chestType;
            this.countText = countText;
            this.percentageText = percentageText;
            this.yValue = yValue;
            this.percentage = percentage;
        }
    }

    private static class ChestDataEntry {
        int dim;
        int x, y, z;
        String chestType;
        String countText;
        String percentageText;
        double yValue;
        double percentage;

        ChestDataEntry() {}

        ChestDataEntry(int dim, BlockPos pos, TitleInfo info) {
            this.dim = dim;
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
            this.chestType = info.chestType;
            this.countText = info.countText;
            this.percentageText = info.percentageText;
            this.yValue = info.yValue;
            this.percentage = info.percentage;
        }

        TitleInfo toTitleInfo() {
            return new TitleInfo(chestType, countText, percentageText, yValue, percentage);
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }

    private TitleInfo parseTitle(String title) {
        if (title == null || title.isEmpty()) {
            return new TitleInfo("Ender Chest", "", "", 0.0, -1.0);
        }

        String originalTitle = title;

        String titleForParsing = title.replaceAll("\u00A7[0-9a-fk-or]", "");

        Pattern[] patterns = {
            COUNT_PATTERN,
            Pattern.compile("\\[([^\\]]+)/([^\\]]+)\\]"),
            Pattern.compile("\\[(.+?)/(.+?)\\]"),
            Pattern.compile("\\[([\\d.,KMkmB]+)\\s*/\\s*([\\d.,KMkmB]+)\\]", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(titleForParsing);
            if (matcher.find()) {
                try {
                    String xStr = matcher.group(1).trim();
                    String yStr = matcher.group(2).trim();
                    String countText = xStr + "/" + yStr;

                    double x = parseNumber(xStr);
                    double y = parseNumber(yStr);

                    double percentage = y > 0 ? (x * 100.0 / y) : 0.0;
                    String colorCode = getPercentageColor(percentage);
                    String percentageText = colorCode + String.format("%.1f%% filled", percentage);

                    String chestType = originalTitle.substring(0, findBracketStart(originalTitle, matcher.start())).trim();
                    if (chestType.isEmpty()) {
                        chestType = "Ender Chest";
                    }

                    return new TitleInfo(chestType, countText, percentageText, y, percentage);
                } catch (Exception e) {
                    continue;
                }
            }
        }

        int bracketStart = titleForParsing.indexOf('[');
        int bracketEnd = titleForParsing.indexOf(']');
        if (bracketStart >= 0 && bracketEnd > bracketStart) {
            String bracketContent = titleForParsing.substring(bracketStart + 1, bracketEnd);
            int slashIndex = bracketContent.indexOf('/');
            if (slashIndex > 0) {
                String xStr = bracketContent.substring(0, slashIndex).trim();
                String yStr = bracketContent.substring(slashIndex + 1).trim();
                String countText = xStr + "/" + yStr;

                try {
                    double x = parseNumber(xStr);
                    double y = parseNumber(yStr);
                    double percentage = y > 0 ? (x * 100.0 / y) : 0.0;
                    String colorCode = getPercentageColor(percentage);
                    String percentageText = colorCode + String.format("%.1f%% filled", percentage);

                    int actualBracketStart = findBracketStart(originalTitle, bracketStart);
                    String chestType = originalTitle.substring(0, actualBracketStart).trim();
                    if (chestType.isEmpty()) {
                        chestType = "Ender Chest";
                    }

                    return new TitleInfo(chestType, countText, percentageText, y, percentage);
                } catch (Exception e) {
                }
            }
        }

        String chestType = originalTitle.trim();
        if (chestType.isEmpty()) {
            chestType = "Ender Chest";
        }
        return new TitleInfo(chestType, "", "", 0.0, -1.0);
    }

    private String getCountColor(double yValue) {
        if (yValue >= 690000.0 && yValue <= 710000.0) {
            return "\u00A7d";
        } else if (yValue >= 1015000.0 && yValue <= 1035000.0) {
            return "\u00A76";
        } else if (yValue >= 1490000.0 && yValue <= 1510000.0) {
            return "\u00A7b";
        } else if (yValue >= 2990000.0 && yValue <= 3010000.0) {
            return "\u00A7a";
        } else {
            return "";
        }
    }

    private String getPercentageColor(double percentage) {
        if (percentage < 50.0) {
            return "\u00A7c";
        } else if (percentage < 80.0) {
            return "\u00A76";
        } else {
            return "\u00A7a";
        }
    }

    private static float[] getHighlightColor(double percentage) {
        if (percentage < 0) {
            return new float[] { 0.6f, 0.6f, 0.6f };
        }
        if (percentage < 50.0) {
            return new float[] { 1.0f, 0.2f, 0.2f };
        }
        if (percentage < 80.0) {
            return new float[] { 1.0f, 0.55f, 0.0f };
        }
        return new float[] { 0.2f, 1.0f, 0.2f };
    }

    private void createDisplayText(World world, TitleInfo titleInfo) {
        if (titleInfo.countText.isEmpty()) {
            return;
        }
        String countColor = getCountColor(titleInfo.yValue);
        if (!titleInfo.percentageText.isEmpty()) {
            displayText = countColor + titleInfo.countText + " \u00A77- " + titleInfo.percentageText;
        } else {
            displayText = countColor + titleInfo.countText;
        }
        displayStartTick = world.getTotalWorldTime();
    }

    private BlockPos getBlockPlayerIsLookingAt(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        try {
            double reachDistance = 4.5;
            Vec3 eyePos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
            Vec3 lookVec = mc.thePlayer.getLookVec();
            Vec3 endPos = eyePos.addVector(lookVec.xCoord * reachDistance, lookVec.yCoord * reachDistance, lookVec.zCoord * reachDistance);

            MovingObjectPosition result = mc.theWorld.rayTraceBlocks(eyePos, endPos, false, false, false);
            if (result != null && result.typeOfHit == MovingObjectType.BLOCK) {
                BlockPos hitPos = result.getBlockPos();
                if (mc.theWorld.getBlockState(hitPos).getBlock() == Blocks.ender_chest) {
                    return hitPos;
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    private int findBracketStart(String original, int positionInClean) {
        int cleanPos = 0;
        int originalPos = 0;
        while (cleanPos < positionInClean && originalPos < original.length()) {
            char c = original.charAt(originalPos);
            if (c == '\u00A7' && originalPos + 1 < original.length()) {
                originalPos += 2;
            } else {
                cleanPos++;
                originalPos++;
            }
        }
        return originalPos;
    }

    private double parseNumber(String numStr) {
        try {
            if (numStr == null || numStr.isEmpty()) {
                return 0.0;
            }

            numStr = numStr.replace(",", "").toUpperCase().trim();
            double multiplier = 1.0;

            if (numStr.endsWith("B")) {
                multiplier = 1000000000.0;
                numStr = numStr.substring(0, numStr.length() - 1);
            } else if (numStr.endsWith("M")) {
                multiplier = 1000000.0;
                numStr = numStr.substring(0, numStr.length() - 1);
            } else if (numStr.endsWith("K")) {
                multiplier = 1000.0;
                numStr = numStr.substring(0, numStr.length() - 1);
            }

            return Double.parseDouble(numStr) * multiplier;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String getGuiTitle(GuiChest guiChest) {
        if (guiChest == null) {
            return "Ender Chest";
        }

        try {
            Field titleField = GuiChest.class.getDeclaredField("guiTitle");
            titleField.setAccessible(true);
            String title = (String) titleField.get(guiChest);
            if (title != null && !title.isEmpty() && !title.equals("container.chest")) {
                return title;
            }
        } catch (Exception e) {
        }

        try {
            if (guiChest.inventorySlots != null) {
                Field upperChestField = guiChest.inventorySlots.getClass().getDeclaredField("upperChestInventory");
                upperChestField.setAccessible(true);
                Object upperChest = upperChestField.get(guiChest.inventorySlots);
                if (upperChest != null) {
                    Field nameField = upperChest.getClass().getDeclaredField("name");
                    nameField.setAccessible(true);
                    String name = (String) nameField.get(upperChest);
                    if (name != null && !name.isEmpty() && !name.equals("container.chest")) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
        }

        try {
            if (guiChest.inventorySlots != null && guiChest.inventorySlots.getSlot(0) != null) {
                String name = guiChest.inventorySlots.getSlot(0).inventory.getName();
                if (name != null && !name.isEmpty() && !name.equals("container.chest")) {
                    return name;
                }
            }
        } catch (Exception e) {
        }

        return "Ender Chest";
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) {
            return;
        }
        if (!highlightEnabled) return;
        if (displayText == null) {
            return;
        }

        Minecraft mc = getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }

        if (currentlyLookingAt == null) {
            long currentTick = mc.theWorld.getTotalWorldTime();
            if (currentTick - displayStartTick >= DISPLAY_DURATION_TICKS) {
                displayText = null;
                return;
            }
        }

        FontRenderer fontRenderer = mc.fontRendererObj;
        ScaledResolution scaledRes = new ScaledResolution(mc);
        int screenWidth = scaledRes.getScaledWidth();
        int screenHeight = scaledRes.getScaledHeight();

        int textWidth = fontRenderer.getStringWidth(displayText.replaceAll("\u00A7[0-9a-fk-or]", ""));
        int x = (screenWidth - textWidth) / 2;
        int y = screenHeight / 2 - 20;

        int padding = 4;
        int bgColor = 0x80000000;
        Gui.drawRect(x - padding, y - padding, x + textWidth + padding, y + 10 + padding, bgColor);

        fontRenderer.drawStringWithShadow(displayText, x, y, 0xFFFFFF);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        try {
            if (!highlightEnabled || storedChestData.isEmpty()) {
                return;
            }
            Minecraft mc = getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            if (!mc.theWorld.isRemote) {
                return;
            }
            double partialTicks = event.partialTicks;
            EntityPlayerSP player = mc.thePlayer;
            double vx = player.prevPosX + (player.posX - player.prevPosX) * partialTicks;
            double vy = player.prevPosY + (player.posY - player.prevPosY) * partialTicks;
            double vz = player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GL11.glPushMatrix();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(4.0f);
            GL11.glTranslated(-vx, -vy, -vz);

            for (Map.Entry<BlockPos, TitleInfo> entry : storedChestData.entrySet()) {
                BlockPos pos = entry.getKey();
                if (mc.theWorld.getBlockState(pos).getBlock() != Blocks.ender_chest) {
                    continue;
                }
                double dx = pos.getX() - player.posX;
                double dy = pos.getY() - player.posY;
                double dz = pos.getZ() - player.posZ;
                if (dx * dx + dy * dy + dz * dz > HIGHLIGHT_MAX_DISTANCE * HIGHLIGHT_MAX_DISTANCE) {
                    continue;
                }
                float[] rgb = getHighlightColor(entry.getValue().percentage);
                drawBlockOutline(pos.getX(), pos.getY(), pos.getZ(), rgb[0], rgb[1], rgb[2]);
            }

            GL11.glPopMatrix();
            GL11.glPopAttrib();
        } catch (Exception e) {
        }
    }

    private static void drawBlockOutline(double x, double y, double z, float r, float g, float b) {
        GL11.glColor4f(r, g, b, 0.85f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(x, y, z);
        GL11.glVertex3d(x + 1, y, z);
        GL11.glVertex3d(x + 1, y, z);
        GL11.glVertex3d(x + 1, y, z + 1);
        GL11.glVertex3d(x + 1, y, z + 1);
        GL11.glVertex3d(x, y, z + 1);
        GL11.glVertex3d(x, y, z + 1);
        GL11.glVertex3d(x, y, z);
        GL11.glVertex3d(x, y + 1, z);
        GL11.glVertex3d(x + 1, y + 1, z);
        GL11.glVertex3d(x + 1, y + 1, z);
        GL11.glVertex3d(x + 1, y + 1, z + 1);
        GL11.glVertex3d(x + 1, y + 1, z + 1);
        GL11.glVertex3d(x, y + 1, z + 1);
        GL11.glVertex3d(x, y + 1, z + 1);
        GL11.glVertex3d(x, y + 1, z);
        GL11.glVertex3d(x, y, z);
        GL11.glVertex3d(x, y + 1, z);
        GL11.glVertex3d(x + 1, y, z);
        GL11.glVertex3d(x + 1, y + 1, z);
        GL11.glVertex3d(x + 1, y, z + 1);
        GL11.glVertex3d(x + 1, y + 1, z + 1);
        GL11.glVertex3d(x, y, z + 1);
        GL11.glVertex3d(x, y + 1, z + 1);
        GL11.glEnd();
    }
}
