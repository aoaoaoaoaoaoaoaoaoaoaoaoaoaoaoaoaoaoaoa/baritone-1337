package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.event.events.ChatEvent;
import baritone.utils.accessor.IGuiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static baritone.api.command.IBaritoneChatControl.FORCE_COMMAND_PREFIX;

@Mixin(Screen.class)
public abstract class MixinScreen implements IGuiScreen {

  //TODO: switch to enum extention with mixin 9.0 or whenever Mumfrey gets around to it
  @Inject(method = "defaultHandleGameClickEvent", at = @At(value = "HEAD"), cancellable = true)
  private static void handleCustomClickEvent(final ClickEvent clickEvent, final Minecraft minecraft, final Screen screen, final CallbackInfo ci) {
    if (clickEvent == null) {
      return;
    }
    if (!(clickEvent instanceof ClickEvent.RunCommand(String command))) return;
    if (!command.startsWith(FORCE_COMMAND_PREFIX)) {
      return;
    }
    IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
    if (baritone != null) {
      baritone.getGameEventHandler().onSendChatMessage(new ChatEvent(command));
    }
    ci.cancel();
  }
}
