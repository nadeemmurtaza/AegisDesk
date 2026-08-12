package com.newax.aegis.engine.ai

object PromptBuilder {

    data class Prompt(
        val system: String,
        val user: String,
        val full: String = "$system\n\nUSER: $user\nASSISTANT:"
    )

    fun factExtraction(text: String, subject: String = ""): Prompt {
        val sys = "Extract factual claims from the following text. " +
            "Return one fact per line, each starting with '• '. " +
            "Only include verifiable, concrete facts. " +
            (if (subject.isNotBlank()) "Focus on facts about '$subject'. " else "") +
            "Do not infer. Do not add opinions."
        return Prompt(sys, text)
    }

    fun tripleExtraction(text: String): Prompt {
        val sys = "Extract subject-predicate-object triples from the text. " +
            "Format: SUBJECT | PREDICATE | OBJECT, one per line. " +
            "Use simple predicates: is, works_at, lives_in, knows, has, likes, owns. " +
            "Only extract explicit facts, not implied ones."
        return Prompt(sys, text)
    }

    fun entityExtraction(text: String): Prompt {
        val sys = "Extract named entities from the text. " +
            "Format: ENTITY_TYPE: entity_name, one per line. " +
            "Types: PERSON, ORGANIZATION, LOCATION, PRODUCT, DATE, PHONE, EMAIL, URL. " +
            "Only extract clearly named entities."
        return Prompt(sys, text)
    }

    fun summarize(text: String, maxSentences: Int = 3): Prompt {
        val sys = "Summarize the following in $maxSentences sentences or fewer. " +
            "Be concise and factual. Preserve all key information."
        return Prompt(sys, text)
    }

    fun qa(question: String, context: String): Prompt {
        val sys = "Answer the question using only the provided context. " +
            "If the answer is not in the context, say 'I don't have that information.' " +
            "Be direct and concise."
        return Prompt(sys, "CONTEXT:\n$context\n\nQUESTION: $question")
    }

    fun classify(text: String, categories: List<String>): Prompt {
        val cats = categories.joinToString(", ")
        val sys = "Classify the following into exactly one of these categories: $cats. " +
            "Reply with only the category name, nothing else."
        return Prompt(sys, text)
    }

    fun sentiment(text: String): Prompt {
        val sys = "Analyze the sentiment of the following text. " +
            "Reply with: POSITIVE, NEGATIVE, NEUTRAL, or MIXED. " +
            "Then on a new line, give a brief one-sentence reason."
        return Prompt(sys, text)
    }

    fun intentDetection(query: String, intents: List<String>): Prompt {
        val intentList = intents.joinToString(", ")
        val sys = "Identify the user's intent from the following query. " +
            "Possible intents: $intentList. " +
            "Reply with: INTENT: <intent_name>. If none match, reply INTENT: unknown."
        return Prompt(sys, query)
    }

    fun procedureGeneration(goal: String, context: String = ""): Prompt {
        val sys = "Generate a step-by-step procedure for the following goal on an Android device. " +
            "Format each step as: STEP N: <action>. " +
            "Actions must be: TAP, TYPE, SWIPE, WAIT, OPEN_APP, or ASSERT. " +
            "Be specific about UI elements."
        val user = if (context.isNotBlank()) "CONTEXT: $context\n\nGOAL: $goal" else "GOAL: $goal"
        return Prompt(sys, user)
    }

    fun conflictResolution(fact1: String, fact2: String, subject: String): Prompt {
        val sys = "Two conflicting facts exist about '$subject'. " +
            "Determine which is more likely to be current and correct. " +
            "Reply: KEEP: <fact1 or fact2>, REASON: <brief reason>."
        return Prompt(sys, "FACT 1: $fact1\nFACT 2: $fact2")
    }

    fun personProfile(name: String, facts: List<String>): Prompt {
        val sys = "Generate a brief, factual profile summary for '$name' based on these facts. " +
            "Write 2-3 sentences max. Only use the provided facts."
        return Prompt(sys, facts.joinToString("\n") { "• $it" })
    }

    fun goalDecomposition(goal: String, availableSkills: List<String>): Prompt {
        val skillList = availableSkills.joinToString(", ")
        val sys = "Decompose the following goal into a sequence of concrete steps. " +
            "Available skills: $skillList. " +
            "Format: STEP N: <skill_name> — <brief description>. " +
            "Keep steps minimal and achievable."
        return Prompt(sys, "GOAL: $goal")
    }

    fun customSystem(systemPrompt: String, userQuery: String): Prompt =
        Prompt(systemPrompt, userQuery)
}
