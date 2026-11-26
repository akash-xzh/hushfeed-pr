package app.morphe.patches.tiktok.misc.followbridge

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.launcherOnCreates
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions

internal object DropBackgroundRequestsFingerprint : Fingerprint(
    strings = listOf("Drop background requests when went background for 2 minutes"),
)

@Suppress("unused")
val followBridgePatch = bytecodePatch(
    name = "Follow bridge (localhost)",
    description = "Starts a local HTTP bridge (48912 TikTok / 48914 TikTok Lite) and allows background API requests.",
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
        DropBackgroundRequestsFingerprint.methodOrNull?.let { m ->
            when (m.returnType) {
                "V" -> m.addInstruction(0, "return-void")
                "Z" -> m.addInstructions(0, "const/4 v0, 0x0\nreturn v0")
                else -> m.addInstructions(0, "const/4 v0, 0x0\nreturn-object v0")
            }
        }
    }
}
