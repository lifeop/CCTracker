package com.life.cctracker;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class ClientEnderChestPacketBlocker {

    private static final String HANDLER_NAME = "cctracker_client_ender_blocker";
    private static boolean handlerAdded = false;

    public static void ensureHandlerAdded(NetworkManager manager) {
        if (manager == null || manager.channel() == null || handlerAdded) {
            return;
        }
        try {
            ChannelPipeline pipeline = manager.channel().pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                handlerAdded = true;
                return;
            }
            pipeline.addLast(HANDLER_NAME, createHandler());
            handlerAdded = true;
        } catch (Throwable t) {
        }
    }

    private static ChannelOutboundHandlerAdapter createHandler() {
        return new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (!(msg instanceof C07PacketPlayerDigging)) {
                    ctx.write(msg, promise);
                    return;
                }
                C07PacketPlayerDigging pkt = (C07PacketPlayerDigging) msg;
                BlockPos pos = pkt.getPosition();
                boolean drop = false;
                if (pkt.getStatus() != C07PacketPlayerDigging.Action.START_DESTROY_BLOCK) {
                    ctx.write(msg, promise);
                    return;
                }
                EnderChestTracker tracker = EnderChestTracker.getClientTracker();
                if (tracker != null && !tracker.isHighlightEnabled()) {
                    ctx.write(msg, promise);
                    return;
                }
                if (EnderChestTracker.dropNextDigPacket && pos != null && EnderChestTracker.dropNextDigPacketPos != null
                        && pos.getX() == EnderChestTracker.dropNextDigPacketPos.getX()
                        && pos.getY() == EnderChestTracker.dropNextDigPacketPos.getY()
                        && pos.getZ() == EnderChestTracker.dropNextDigPacketPos.getZ()) {
                    drop = true;
                    EnderChestTracker.dropNextDigPacket = false;
                    EnderChestTracker.dropNextDigPacketPos = null;
                }
                if (!drop && pos != null) {
                    try {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc != null && mc.theWorld != null) {
                            if (mc.theWorld.getBlockState(pos).getBlock() == Blocks.ender_chest) {
                                drop = true;
                            }
                        }
                    } catch (Throwable ex) {
                    }
                }
                if (drop) {
                    final BlockPos posForRead = pos;
                    try {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc != null) {
                            mc.addScheduledTask(new Runnable() {
                                @Override
                                public void run() {
                                    EnderChestTracker t = EnderChestTracker.getClientTracker();
                                    if (t != null && posForRead != null) {
                                        t.startSilentRead(posForRead);
                                    }
                                }
                            });
                        }
                    } catch (Throwable ex2) {
                    }
                    promise.setSuccess();
                    return;
                }
                ctx.write(msg, promise);
            }
        };
    }

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        try {
            if (event.manager == null || event.manager.channel() == null) {
                return;
            }
            ChannelPipeline pipeline = event.manager.channel().pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                handlerAdded = true;
                return;
            }
            String[] tryBefore = new String[] { "packet_handler", "FML|PacketHandler", "encoder" };
            for (int i = 0; i < tryBefore.length; i++) {
                if (pipeline.get(tryBefore[i]) != null) {
                    pipeline.addBefore(tryBefore[i], HANDLER_NAME, createHandler());
                    handlerAdded = true;
                    return;
                }
            }
            pipeline.addLast(HANDLER_NAME, createHandler());
            handlerAdded = true;
        } catch (Throwable t) {
        }
    }
}
