package com.life.cctracker;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;

public class ClearCCCommand extends CommandBase {

    private final EnderChestTracker tracker;

    public ClearCCCommand(EnderChestTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public String getCommandName() {
        return "clearcc";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/clearcc - Clear all stored chest data and remove highlights";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        tracker.clearAllData();
        sender.addChatMessage(new ChatComponentText("\u00A7a\u00A7lSaiCo\u00A7d\u00A7lPvP \u00A7f\u00A7lCCTracker: \u00A7d\u00A7lData Storage \u00A7f - \u00A7aAll chest data has been cleared."));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    public static void register(EnderChestTracker tracker) {
        ClientCommandHandler.instance.registerCommand(new ClearCCCommand(tracker));
    }
}
