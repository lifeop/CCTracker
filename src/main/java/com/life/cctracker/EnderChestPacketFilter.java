package com.life.cctracker;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.init.Blocks;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

public class EnderChestPacketFilter {

    private static final String HANDLER_NAME = "cctracker_ender_chest_filter";
    private static final String[] PIPELINE_NAMES = new String[] { "packet_handler", "FML|PacketHandler" };

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) {
            return;
        }
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.worldObj == null || player.worldObj.isRemote) {
            return;
        }
        NetHandlerPlayServer netHandler = player.playerNetServerHandler;
        if (netHandler == null) {
            return;
        }
        NetworkManager manager = netHandler.getNetworkManager();
        if (manager == null || manager.channel() == null) {
            return;
        }
        try {
            final NetworkManager mgr = manager;
            ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (!(msg instanceof C07PacketPlayerDigging)) {
                        ctx.fireChannelRead(msg);
                        return;
                    }
                    final C07PacketPlayerDigging pkt = (C07PacketPlayerDigging) msg;
                    final ChannelHandlerContext fCtx = ctx;
                    final Object fMsg = msg;
                    MinecraftServer server = MinecraftServer.getServer();
                    if (server == null) {
                        ctx.fireChannelRead(msg);
                        return;
                    }
                    server.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                INetHandler net = mgr.getNetHandler();
                                if (!(net instanceof NetHandlerPlayServer)) {
                                    fireRead(fCtx, fMsg);
                                    return;
                                }
                                EntityPlayerMP p = ((NetHandlerPlayServer) net).playerEntity;
                                if (p == null || p.worldObj == null) {
                                    fireRead(fCtx, fMsg);
                                    return;
                                }
                                World world = p.worldObj;
                                BlockPos pos = pkt.getPosition();
                                if (pos != null && world.getBlockState(pos).getBlock() == Blocks.ender_chest) {
                                    return;
                                }
                                fireRead(fCtx, fMsg);
                            } catch (Exception e) {
                                fireRead(fCtx, fMsg);
                            }
                        }
                    });
                }
            };
            ChannelPipeline pipeline = manager.channel().pipeline();
            String uniqueName = HANDLER_NAME + "_" + player.getEntityId();
            if (pipeline.get(uniqueName) != null) {
                return;
            }
            for (int i = 0; i < PIPELINE_NAMES.length; i++) {
                String name = PIPELINE_NAMES[i];
                if (pipeline.get(name) != null) {
                    pipeline.addBefore(name, uniqueName, handler);
                    return;
                }
            }
            try {
                if (pipeline.get("decoder") != null) {
                    pipeline.addAfter("decoder", uniqueName, handler);
                }
            } catch (Throwable t) {
            }
        } catch (Exception e) {
        }
    }

    private static void fireRead(final ChannelHandlerContext ctx, final Object msg) {
        ctx.channel().eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                ctx.fireChannelRead(msg);
            }
        });
    }
}
