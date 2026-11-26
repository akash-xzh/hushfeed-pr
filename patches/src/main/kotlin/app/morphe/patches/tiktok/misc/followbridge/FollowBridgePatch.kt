package app.morphe.patches.tiktok.misc.followbridge

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.launcherOnCreates
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstruction

@Suppress("unused")
val followBridgePatch = bytecodePatch(
    name = "Follow bridge (localhost)",
    description = "Starts a local HTTP bridge (48912 TikTok / 48914 TikTok Lite) for Termux automation.",
) {
    compatibleWith(*AppCompatibilities.tiktokAndLite())
    dependsOn(sharedExtensionPatch)

    execute {
        for (method in launcherOnCreates(packageMetadata.packageName)) {
            method.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, " +
                    "Lapp/morphe/extension/tiktok/followbridge/FollowBridge;->" +
                    "install(Landroid/app/Activity;Landroid/os/Bundle;)V",
            )
        }
    }
}
