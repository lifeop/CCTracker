package com.life.cctracker;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;

public class ToggleCCCommand extends CommandBase {

    private final EnderChestTracker tracker;

    public ToggleCCCommand(EnderChestTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public String getCommandName() {
        return "togglecc";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/togglecc - Toggle all CCTracker features.";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) throws CommandException {
        boolean now = !tracker.isHighlightEnabled();
        tracker.setHighlightEnabled(now);
        String msg = now
            ? "\u00A7a\u00A7lSaiCo\u00A7d\u00A7lPvP \u00A7f\u00A7lCCTracker\u00A7f - \u00A7aEnabled"
            : "\u00A7a\u00A7lSaiCo\u00A7d\u00A7lPvP \u00A7f\u00A7lCCTracker\u00A7f - \u00A7cDisabled";
        sender.addChatMessage(new ChatComponentText(msg));
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    public static void register(EnderChestTracker tracker) {
        ClientCommandHandler.instance.registerCommand(new ToggleCCCommand(tracker));
    }
}
