package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.type.EventState;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * @author Brady
 * @since 8/6/2018
 */
@Mixin(Connection.class)
public class MixinNetworkManager {

    @Shadow
    private Channel channel;

    @Shadow
    @Final
    private PacketFlow receiving;

    @Inject(
            method = "sendPacket",
            at = @At("HEAD")
    )
    private void preDispatchPacket(final Packet<?> packet, final ChannelFutureListener channelFutureListener, final boolean flush, final CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }

        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onSendPacket(new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
            }
        }
    }

    @Inject(
            method = "sendPacket",
            at = @At("RETURN")
    )
    private void postDispatchPacket(Packet<?> packet, ChannelFutureListener packetSendListener, boolean flush, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }

        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onSendPacket(new PacketEvent((Connection) (Object) this, EventState.POST, packet));
            }
        }
    }

    @Inject(
            method = "channelRead0",
            at = @At(
                    value = "INVOKE",
                    target = "net/minecraft/network/Connection.genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V"
            )
    )
    private void preProcessPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onReceivePacket(new PacketEvent((Connection) (Object) this, EventState.PRE, packet));
            }
        }
    }

    @Inject(
            method = "channelRead0",
            at = @At("RETURN")
    )
    private void postProcessPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (!this.channel.isOpen() || this.receiving != PacketFlow.CLIENTBOUND) {
            return;
        }
        for (IBaritone ibaritone : BaritoneAPI.getProvider().getAllBaritones()) {
            if (ibaritone.getPlayerContext().player() != null && ibaritone.getPlayerContext().player().connection.getConnection() == (Connection) (Object) this) {
                ibaritone.getGameEventHandler().onReceivePacket(new PacketEvent((Connection) (Object) this, EventState.POST, packet));
            }
        }
    }
}
