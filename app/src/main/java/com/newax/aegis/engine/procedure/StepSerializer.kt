package com.newax.aegis.engine.procedure

import org.json.JSONArray
import org.json.JSONObject

object StepSerializer {

    private fun serializeCondition(c: StepCondition): JSONObject =
        JSONObject().put("target", c.target).put("abort", c.abortIfFailed)

    private fun deserializeCondition(o: JSONObject): StepCondition =
        StepCondition(o.getString("target"), o.optBoolean("abort", true))

    fun serialize(steps: List<ProcedureStep>): String {
        val arr = JSONArray()
        steps.forEach { step ->
            val o = JSONObject()
            when (step) {
                is ProcedureStep.Tap -> {
                    o.put("t","TAP").put("target",step.target).put("fx",step.fallbackX).put("fy",step.fallbackY)
                    step.pre?.let  { o.put("pre",  serializeCondition(it)) }
                    step.post?.let { o.put("post", serializeCondition(it)) }
                }
                is ProcedureStep.TypeText -> {
                    o.put("t","TYPE").put("target",step.target).put("text",step.text).put("clear",step.clearFirst)
                    step.pre?.let  { o.put("pre",  serializeCondition(it)) }
                    step.post?.let { o.put("post", serializeCondition(it)) }
                }
                is ProcedureStep.WaitFor     -> o.put("t","WAIT").put("target",step.target).put("ms",step.timeoutMs)
                is ProcedureStep.Verify      -> o.put("t","VERIFY").put("target",step.target).put("abort",step.abortIfMissing)
                is ProcedureStep.ScrollDown  -> o.put("t","SCROLL_DOWN").put("max",step.maxSwipes)
                is ProcedureStep.ScrollUp    -> o.put("t","SCROLL_UP").put("max",step.maxSwipes)
                is ProcedureStep.Back        -> o.put("t","BACK").put("n",step.count)
                is ProcedureStep.Home        -> o.put("t","HOME")
                is ProcedureStep.Sleep       -> o.put("t","SLEEP").put("ms",step.ms)
                is ProcedureStep.LaunchApp -> {
                    o.put("t","LAUNCH").put("pkg",step.packageName)
                    step.post?.let { o.put("post", serializeCondition(it)) }
                }
                is ProcedureStep.SelectItem  -> o.put("t","SELECT").put("list",step.listTarget).put("item",step.itemText)
                is ProcedureStep.TapCoord    -> o.put("t","TAP_COORD").put("x",step.x).put("y",step.y)
                is ProcedureStep.DismissDialog -> {
                    val ba = JSONArray(); step.buttons.forEach { ba.put(it) }
                    o.put("t","DISMISS").put("btns",ba)
                }
                is ProcedureStep.AssertPackage -> o.put("t","ASSERT_PKG").put("pkg",step.packageName)
                is ProcedureStep.ShareFile   -> o.put("t","SHARE_FILE").put("uri",step.uriString).put("mime",step.mimeType)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun deserialize(json: String): List<ProcedureStep> {
        if (json.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val steps = mutableListOf<ProcedureStep>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val step: ProcedureStep = when (o.getString("t")) {
                "TAP" -> ProcedureStep.Tap(
                    target    = o.getString("target"),
                    fallbackX = o.optDouble("fx",-1.0).toFloat(),
                    fallbackY = o.optDouble("fy",-1.0).toFloat(),
                    pre  = o.optJSONObject("pre")?.let  { deserializeCondition(it) },
                    post = o.optJSONObject("post")?.let { deserializeCondition(it) }
                )
                "TYPE" -> ProcedureStep.TypeText(
                    target     = o.getString("target"),
                    text       = o.getString("text"),
                    clearFirst = o.optBoolean("clear", true),
                    pre  = o.optJSONObject("pre")?.let  { deserializeCondition(it) },
                    post = o.optJSONObject("post")?.let { deserializeCondition(it) }
                )
                "WAIT"        -> ProcedureStep.WaitFor(o.getString("target"), o.optLong("ms",4000L))
                "VERIFY"      -> ProcedureStep.Verify(o.getString("target"), o.optBoolean("abort",true))
                "SCROLL_DOWN" -> ProcedureStep.ScrollDown(o.optInt("max",3))
                "SCROLL_UP"   -> ProcedureStep.ScrollUp(o.optInt("max",3))
                "BACK"        -> ProcedureStep.Back(o.optInt("n",1))
                "HOME"        -> ProcedureStep.Home()
                "SLEEP"       -> ProcedureStep.Sleep(o.getLong("ms"))
                "LAUNCH" -> ProcedureStep.LaunchApp(
                    packageName = o.getString("pkg"),
                    post = o.optJSONObject("post")?.let { deserializeCondition(it) }
                )
                "SELECT"      -> ProcedureStep.SelectItem(o.getString("list"), o.getString("item"))
                "TAP_COORD"   -> ProcedureStep.TapCoord(o.getDouble("x").toFloat(), o.getDouble("y").toFloat())
                "DISMISS"     -> {
                    val ba = o.optJSONArray("btns")
                    val btns = mutableListOf<String>()
                    if (ba != null) for (j in 0 until ba.length()) btns += ba.getString(j)
                    ProcedureStep.DismissDialog(btns)
                }
                "ASSERT_PKG"  -> ProcedureStep.AssertPackage(o.getString("pkg"))
                "SHARE_FILE"  -> ProcedureStep.ShareFile(o.getString("uri"), o.optString("mime","*/*"))
                else -> continue
            }
            steps += step
        }
        return steps
    }
}
