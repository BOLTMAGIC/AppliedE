package gripe._90.appliede.mixin.tooltip;

import com.google.common.collect.BiMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import appeng.client.gui.me.common.Repo;
import appeng.menu.me.common.GridInventoryEntry;

import gripe._90.appliede.me.reporting.GridInventoryEMCEntry;

@Mixin(value = Repo.class, remap = false)
public abstract class RepoMixin {
    @Shadow
    @Final
    private BiMap<Long, GridInventoryEntry> entries;

    // spotless:off
    @SuppressWarnings("UnreachableCode")
    @Inject(
            method = "handleUpdate(Lappeng/menu/me/common/GridInventoryEntry;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lappeng/menu/me/common/GridInventoryEntry;<init>(JLappeng/api/stacks/AEKey;JJZ)V",
                    shift = At.Shift.AFTER),
            cancellable = true,
            locals = LocalCapture.CAPTURE_FAILHARD)
    // spotless:on
    private void setServerEntryTransmutable(
            GridInventoryEntry serverEntry, CallbackInfo ci, GridInventoryEntry localEntry) {
        ci.cancel();

        var what = localEntry.getWhat() != null ? localEntry.getWhat() : serverEntry.getWhat();
        var entry = new GridInventoryEntry(
                serverEntry.getSerial(),
                what,
                serverEntry.getStoredAmount(),
                serverEntry.getRequestableAmount(),
                serverEntry.isCraftable());
        boolean transmutable = false;
        if (serverEntry instanceof GridInventoryEMCEntry emcEntry) {
            transmutable = emcEntry.appliede$isTransmutable();
        }
        ((GridInventoryEMCEntry) entry).appliede$setTransmutable(transmutable);
        entries.put(serverEntry.getSerial(), entry);
    }
}
