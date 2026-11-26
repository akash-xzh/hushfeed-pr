package app.morphe.patches.tiktok.misc.followbridge

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.util.addInstruction

@Suppress("unused")
val followBridgePatch = bytecodePatch(
    name = "Follow bridge (localhost)",
    description = "Starts a local 127.0.0.1:48912 HTTP bridge inside TikTok for Termux automation.",
) {
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(sharedExtensionPatch)

    execute {
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, " +
                "Lapp/morphe/extension/tiktok/followbridge/FollowBridge;->" +
                "install(Landroid/app/Activity;Landroid/os/Bundle;)V",
        )
    }
}
