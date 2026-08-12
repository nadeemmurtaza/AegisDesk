package com.newax.aegis.engine

/** Classifies text/images/documents by type and sensitivity without ML. */
object DocumentClassifier {

    enum class DocType(val label: String) {
        PERSONAL_MEDICAL("Personal – Medical"),
        PERSONAL_FINANCIAL("Personal – Financial"),
        PERSONAL_LEGAL("Personal – Legal"),
        PERSONAL_ID("Personal – Identity Document"),
        BUSINESS_INVOICE("Business – Invoice/Bill"),
        BUSINESS_CONTRACT("Business – Contract"),
        BUSINESS_REPORT("Business – Report"),
        BUSINESS_COMMUNICATION("Business – Communication"),
        EDUCATIONAL("Educational"),
        COMMUNICATION_EMAIL("Communication – Email"),
        COMMUNICATION_CHAT("Communication – Chat/SMS"),
        GOVERNMENT("Government Document"),
        FINANCIAL_CRYPTO("Crypto/Blockchain"),
        NEWS_ARTICLE("News/Article"),
        SOCIAL_MEDIA("Social Media"),
        RECEIPT("Receipt/Order Confirmation"),
        OTP_MESSAGE("OTP/Verification Message"),
        UNKNOWN("Unknown")
    }

    enum class SensitivityLevel(val rank: Int) {
        PUBLIC(0),
        INTERNAL(1),
        CONFIDENTIAL(2),
        SECRET(3)
    }

    data class ClassificationResult(
        val type: DocType,
        val sensitivity: SensitivityLevel,
        val confidence: Float,           // 0.0–1.0
        val flags: List<String>,         // e.g. "Contains OTP", "Has Account Numbers"
        val summary: String
    )

    private data class Rule(
        val type: DocType,
        val sensitivity: SensitivityLevel,
        val keywords: List<String>,
        val phrases: List<String> = emptyList(),
        val weight: Float = 1.0f
    )

    private val RULES = listOf(
        Rule(DocType.OTP_MESSAGE, SensitivityLevel.SECRET,
            keywords = listOf("otp", "verification code", "one time", "passcode"),
            phrases = listOf("your otp is", "verification code", "use this code", "do not share"),
            weight = 1.0f),
        Rule(DocType.PERSONAL_ID, SensitivityLevel.SECRET,
            keywords = listOf("passport", "national id", "cnic", "aadhaar", "ssn", "social security",
                "driving license", "id card", "identity card", "date of birth", "dob"),
            phrases = listOf("identity proof", "government issued"),
            weight = 0.95f),
        Rule(DocType.FINANCIAL_CRYPTO, SensitivityLevel.SECRET,
            keywords = listOf("bitcoin", "ethereum", "crypto", "wallet", "seed phrase", "private key",
                "public key", "blockchain", "defi", "nft", "metamask", "binance", "exchange"),
            weight = 0.9f),
        Rule(DocType.PERSONAL_FINANCIAL, SensitivityLevel.SECRET,
            keywords = listOf("bank account", "account number", "ifsc", "swift", "routing number",
                "credit card", "debit card", "cvv", "pin", "balance", "statement", "transaction"),
            phrases = listOf("bank statement", "account statement", "transaction history"),
            weight = 0.9f),
        Rule(DocType.PERSONAL_MEDICAL, SensitivityLevel.CONFIDENTIAL,
            keywords = listOf("diagnosis", "prescription", "medicine", "medication", "dosage",
                "treatment", "surgery", "patient", "hospital", "clinic", "doctor", "physician",
                "blood pressure", "glucose", "symptoms", "disease", "report", "lab result"),
            phrases = listOf("medical report", "test results", "patient name"),
            weight = 0.9f),
        Rule(DocType.PERSONAL_LEGAL, SensitivityLevel.CONFIDENTIAL,
            keywords = listOf("court", "lawsuit", "attorney", "legal notice", "judgment", "summons",
                "petition", "affidavit", "warrant", "agreement", "clause", "whereas", "plaintiff",
                "defendant", "jurisdiction"),
            phrases = listOf("legal notice", "court order", "power of attorney"),
            weight = 0.85f),
        Rule(DocType.BUSINESS_CONTRACT, SensitivityLevel.CONFIDENTIAL,
            keywords = listOf("parties", "hereby agrees", "obligations", "indemnify", "liability",
                "termination", "intellectual property", "non-disclosure", "nda", "confidential",
                "breach", "dispute resolution", "arbitration"),
            phrases = listOf("terms and conditions", "in witness whereof", "effective date"),
            weight = 0.85f),
        Rule(DocType.GOVERNMENT, SensitivityLevel.CONFIDENTIAL,
            keywords = listOf("ministry", "department", "government", "official", "federal",
                "state", "municipality", "tax", "revenue", "fir", "police", "authority", "registration"),
            phrases = listOf("government of", "official use only", "classified"),
            weight = 0.8f),
        Rule(DocType.BUSINESS_INVOICE, SensitivityLevel.INTERNAL,
            keywords = listOf("invoice", "bill", "total amount", "due date", "gst", "vat",
                "subtotal", "tax invoice", "payment due", "quantity", "unit price"),
            phrases = listOf("please pay", "amount due", "invoice number"),
            weight = 0.8f),
        Rule(DocType.RECEIPT, SensitivityLevel.INTERNAL,
            keywords = listOf("receipt", "order confirmation", "order id", "tracking", "shipped",
                "delivered", "purchase", "item", "qty", "total"),
            phrases = listOf("thank you for your order", "your order has", "estimated delivery"),
            weight = 0.75f),
        Rule(DocType.BUSINESS_REPORT, SensitivityLevel.INTERNAL,
            keywords = listOf("quarterly", "annual", "revenue", "profit", "loss", "kpi",
                "performance", "metrics", "analysis", "forecast", "outlook", "growth"),
            phrases = listOf("executive summary", "key findings", "year over year"),
            weight = 0.75f),
        Rule(DocType.COMMUNICATION_EMAIL, SensitivityLevel.INTERNAL,
            keywords = listOf("from:", "to:", "subject:", "cc:", "bcc:", "dear", "regards",
                "sincerely", "best regards", "attached", "enclosure"),
            weight = 0.7f),
        Rule(DocType.EDUCATIONAL, SensitivityLevel.INTERNAL,
            keywords = listOf("assignment", "homework", "lecture", "study", "curriculum",
                "grade", "exam", "student", "professor", "university", "college", "course", "chapter"),
            weight = 0.7f),
        Rule(DocType.BUSINESS_COMMUNICATION, SensitivityLevel.INTERNAL,
            keywords = listOf("meeting", "agenda", "minutes", "action items", "follow up",
                "proposal", "presentation", "project", "team", "client"),
            weight = 0.65f),
        Rule(DocType.COMMUNICATION_CHAT, SensitivityLevel.PUBLIC,
            keywords = listOf("hey", "ok", "lol", "yeah", "thanks", "ok cool", "see you"),
            weight = 0.5f),
        Rule(DocType.SOCIAL_MEDIA, SensitivityLevel.PUBLIC,
            keywords = listOf("like", "share", "comment", "follow", "retweet", "trending",
                "hashtag", "story", "reel", "post"),
            weight = 0.5f),
        Rule(DocType.NEWS_ARTICLE, SensitivityLevel.PUBLIC,
            keywords = listOf("according to", "reported", "source", "journalist", "editor",
                "published", "press release", "breaking", "update"),
            weight = 0.5f)
    )

    fun classify(text: String): ClassificationResult {
        if (text.isBlank()) return ClassificationResult(
            DocType.UNKNOWN, SensitivityLevel.PUBLIC, 0f, emptyList(), "Empty content"
        )

        val lower = text.lowercase()
        val scores = mutableMapOf<Rule, Float>()

        for (rule in RULES) {
            var score = 0f
            val kwHits = rule.keywords.count { lower.contains(it) }
            score += kwHits * 0.1f * rule.weight
            val phraseHits = rule.phrases.count { lower.contains(it) }
            score += phraseHits * 0.2f * rule.weight
            if (score > 0f) scores[rule] = score
        }

        val sensitiveAnalysis = SensitiveInfoDetector.analyze(text)
        val flags = mutableListOf<String>()
        if (sensitiveAnalysis.detections.isNotEmpty()) {
            sensitiveAnalysis.detections.map { it.type.label }.toSet()
                .forEach { flags += "Contains $it" }
        }

        if (scores.isEmpty()) {
            return ClassificationResult(
                DocType.UNKNOWN, SensitivityLevel.PUBLIC, 0.1f, flags,
                "Unclassified content${if (flags.isNotEmpty()) " | ${flags.joinToString()}" else ""}"
            )
        }

        val topRule = scores.maxByOrNull { it.value }!!
        val topScore = topRule.value.coerceIn(0f, 1f)

        // Elevate sensitivity if sensitive patterns detected
        val detectedSensitivity = when {
            sensitiveAnalysis.sensitivityScore >= 0.9f -> SensitivityLevel.SECRET
            sensitiveAnalysis.sensitivityScore >= 0.7f -> SensitivityLevel.CONFIDENTIAL
            sensitiveAnalysis.sensitivityScore >= 0.4f -> SensitivityLevel.INTERNAL
            else -> SensitivityLevel.PUBLIC
        }
        val sensitivity = if (detectedSensitivity.rank > topRule.key.sensitivity.rank)
            detectedSensitivity else topRule.key.sensitivity

        val summary = "${topRule.key.type.label} | ${sensitivity.name} | confidence: ${(topScore * 100).toInt()}%" +
            if (flags.isNotEmpty()) " | ${flags.joinToString()}" else ""

        return ClassificationResult(
            type = topRule.key.type,
            sensitivity = sensitivity,
            confidence = topScore,
            flags = flags,
            summary = summary
        )
    }

    /** Quick check if any sensitive/confidential content is present. */
    fun isConfidential(text: String): Boolean =
        classify(text).sensitivity.rank >= SensitivityLevel.CONFIDENTIAL.rank
}
