package com.newax.aegis.engine.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import com.newax.aegis.engine.capability.Capability
import com.newax.aegis.engine.capability.CapabilityRegistry

enum class ExecutionTier(val rank: Int) {
    ANDROID_API(1),
    INTENT(2),
    DEEP_LINK(3),
    STORED_PROCEDURE(4),
    ACCESSIBILITY_SEMANTIC(5),
    SCREEN_GROUNDING(6),
    VISION(7),
    LLM_REASONING(8)
}

data class ExecutionPlan(
    val tier: ExecutionTier,
    val description: String,
    val executor: suspend () -> Boolean
)

object ExecutionRouter {

    fun resolveOpenApp(context: Context, db: AegisDatabase, packageName: String): ExecutionPlan {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            ExecutionPlan(ExecutionTier.ANDROID_API, "Direct launch: $packageName") {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                true
            }
        } else {
            ExecutionPlan(ExecutionTier.INTENT, "Intent fallback: $packageName") {
                runCatching {
                    val i = Intent(Intent.ACTION_MAIN).apply {
                        setPackage(packageName)
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i)
                }.isSuccess
            }
        }
    }

    fun resolveDeepLink(context: Context, uri: String): ExecutionPlan =
        ExecutionPlan(ExecutionTier.DEEP_LINK, "Deep link: $uri") {
            runCatching {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            }.isSuccess
        }

    fun resolveCapability(context: Context, db: AegisDatabase, capability: AppCapability, pkgHint: String? = null): ExecutionTier {
        val cap = when (capability) {
            AppCapability.OPEN_APP         -> Capability.CAN_OPEN_APP
            AppCapability.SEND_MESSAGE     -> Capability.CAN_SEND_MESSAGE
            AppCapability.MAKE_CALL        -> Capability.CAN_CALL
            AppCapability.SHARE_FILE       -> Capability.CAN_SHARE_FILE
            AppCapability.CREATE_EVENT     -> Capability.CAN_CREATE_EVENT
            else                           -> null
        }
        if (cap != null && !CapabilityRegistry.isAvailable(cap)) return ExecutionTier.LLM_REASONING

        val resolution = AppIntelligence.resolve(db, context, capability, pkgHint)
        return when {
            resolution?.intentAction != null   -> ExecutionTier.INTENT
            resolution?.deepLinkPattern != null -> ExecutionTier.DEEP_LINK
            resolution != null                  -> ExecutionTier.STORED_PROCEDURE
            else                                -> ExecutionTier.ACCESSIBILITY_SEMANTIC
        }
    }
}
