package com.newax.aegis.engine.ai

import com.newax.aegis.engine.intelligence.SkillCategory
import com.newax.aegis.engine.intelligence.SkillDefinition
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.registry.IntentRegistry

data class ToolSelection(
    val skillId: String,
    val skillName: String,
    val confidence: Float,
    val requiredInputs: Map<String, String>,
    val reason: String
)

object ToolSelector {

    fun select(query: String, availableSkillIds: List<String>? = null): List<ToolSelection> {
        val intentMatches = IntentRegistry.classify(query)
        val skills = if (availableSkillIds != null)
            availableSkillIds.mapNotNull { SkillRegistry.get(it) }
        else
            SkillRegistry.allSkills()

        val selections = mutableListOf<ToolSelection>()

        for (intent in intentMatches.take(3)) {
            val matchingSkills = skills.filter { skill ->
                skill.id == intent.intent.actionId ||
                skill.tags.any { t -> intent.intent.id.contains(t) || t.contains(intent.intent.id) } ||
                skill.name.lowercase().contains(intent.intent.name.lowercase())
            }
            for (skill in matchingSkills) {
                selections.add(ToolSelection(
                    skillId = skill.id,
                    skillName = skill.name,
                    confidence = intent.confidence * skill.successRate,
                    requiredInputs = buildInputRequirements(skill, intent.extractedEntities),
                    reason = "Matched intent '${intent.intent.name}' with ${(intent.confidence * 100).toInt()}% confidence"
                ))
            }
        }

        if (selections.isEmpty()) {
            val lower = query.lowercase()
            val keywordMatches = skills
                .filter { skill ->
                    skill.name.lowercase().split(" ").any { lower.contains(it) } ||
                    skill.description.lowercase().contains(lower.take(20))
                }
                .take(3)
            keywordMatches.forEach { skill ->
                selections.add(ToolSelection(
                    skillId = skill.id,
                    skillName = skill.name,
                    confidence = 0.5f,
                    requiredInputs = buildInputRequirements(skill, emptyMap()),
                    reason = "Keyword match"
                ))
            }
        }

        return selections.distinctBy { it.skillId }.sortedByDescending { it.confidence }
    }

    fun selectBest(query: String): ToolSelection? = select(query).firstOrNull()

    fun selectForCategory(category: SkillCategory): List<SkillDefinition> =
        SkillRegistry.findByCategory(category)

    fun canHandle(query: String): Boolean =
        select(query).any { it.confidence >= 0.5f }

    private fun buildInputRequirements(
        skill: SkillDefinition,
        extractedEntities: Map<String, String>
    ): Map<String, String> {
        val inputs = mutableMapOf<String, String>()
        skill.inputSchema.forEach { (key, type) ->
            val extracted = extractedEntities[key]
            if (extracted != null) inputs[key] = extracted
            else inputs[key] = "REQUIRED:$type"
        }
        return inputs
    }
}
