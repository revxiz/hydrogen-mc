package dev.hydrogen.mc.mixin;

import com.mojang.brigadier.CommandDispatcher;
import dev.hydrogen.mc.console.ServerConsole;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds {@code hydrogen console} on dedicated servers without any loader's
 * command event, the same way the rest of Hydrogen avoids loader APIs.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {
	@Shadow
	@Final
	private CommandDispatcher<CommandSourceStack> dispatcher;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void hydrogen$registerConsole(CallbackInfo ci) {
		ServerConsole.register(dispatcher);
	}
}
