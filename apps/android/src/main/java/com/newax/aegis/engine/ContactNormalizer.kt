package com.newax.aegis.engine

/**
 * Normalizes contact display names:
 *  1. Transliterates Urdu/Arabic script to Latin (character map + known-name dictionary)
 *  2. Corrects common spelling variants of Islamic/Pakistani names
 *  3. Expands single-letter initials in Pakistani/South-Asian naming convention
 *  4. Applies proper title-case
 *  5. Strips extra whitespace, numbers embedded in names, junk characters
 */
object ContactNormalizer {

    data class NormalizeResult(
        val name: String,
        val changed: Boolean,
        val reason: String        // human-readable explanation of what was changed
    )

    // ── Urdu/Arabic character-to-Latin transliteration ──────────────────────────

    private val CHAR_MAP: Map<Char, String> = mapOf(
        'ا' to "a",  'أ' to "a",  'إ' to "i",  'آ' to "aa", 'ء' to "",
        'ب' to "b",  'پ' to "p",  'ت' to "t",  'ٹ' to "t",  'ث' to "s",
        'ج' to "j",  'چ' to "ch", 'ح' to "h",  'خ' to "kh",
        'د' to "d",  'ڈ' to "d",  'ذ' to "z",
        'ر' to "r",  'ڑ' to "r",  'ز' to "z",  'ژ' to "zh",
        'س' to "s",  'ش' to "sh", 'ص' to "s",  'ض' to "z",
        'ط' to "t",  'ظ' to "z",  'ع' to "",   'غ' to "gh",
        'ف' to "f",  'ق' to "q",  'ک' to "k",  'گ' to "g",
        'ل' to "l",  'م' to "m",  'ن' to "n",  'ں' to "n",
        'و' to "w",  'ہ' to "h",  'ھ' to "h",  'ة' to "a",
        'ی' to "y",  'ے' to "e",  'ئ' to "y",  'ي' to "i",
        'ى' to "a",  'ؤ' to "w",
        // Vowel diacritics (strip — they don't translate directly to standalone chars)
        'َ' to "", 'ُ' to "", 'ِ' to "", 'ّ' to "",
        'ْ' to "", 'ٓ' to "", 'ٔ' to "", 'ٰ' to "a"
    )

    // Well-known names: exact Urdu → English (preferred over character-map for quality)
    private val KNOWN_NAMES: Map<String, String> = mapOf(
        "محمد" to "Muhammad",      "مُحمد" to "Muhammad",   "احمد" to "Ahmed",
        "عمر" to "Umar",           "عثمان" to "Usman",      "علی" to "Ali",
        "ابراہیم" to "Ibrahim",    "اسماعیل" to "Ismail",  "یوسف" to "Yusuf",
        "داود" to "Dawood",        "سلیمان" to "Suleman",  "موسیٰ" to "Musa",
        "عیسیٰ" to "Isa",          "آدم" to "Adam",         "نوح" to "Nuh",
        "ادریس" to "Idrees",       "الیاس" to "Ilyas",     "یونس" to "Younus",
        "حمزہ" to "Hamza",         "طلحہ" to "Talha",      "زبیر" to "Zubair",
        "سعد" to "Saad",           "حسن" to "Hassan",       "حسین" to "Hussain",
        "عباس" to "Abbas",         "جعفر" to "Jafar",      "تیمور" to "Taimur",
        "ریاض" to "Riaz",          "طارق" to "Tariq",      "ظفر" to "Zafar",
        "نجم" to "Najam",          "سلیم" to "Saleem",     "ناصر" to "Nasir",
        "رضا" to "Raza",           "بلال" to "Bilal",      "کامران" to "Kamran",
        "فیصل" to "Faisal",        "عمران" to "Imran",     "وقار" to "Waqar",
        "ندیم" to "Nadeem",        "اسلم" to "Aslam",      "اکرم" to "Akram",
        "شاہد" to "Shahid",        "سرفراز" to "Sarfaraz", "شاہین" to "Shaheen",
        // Female names
        "عائشہ" to "Ayesha",       "فاطمہ" to "Fatima",    "زینب" to "Zainab",
        "مریم" to "Maryam",        "خدیجہ" to "Khadija",   "سمیہ" to "Sumaiya",
        "ثمینہ" to "Sameena",      "نادیہ" to "Nadia",     "سارہ" to "Sara",
        "صائمہ" to "Saima",        "شمیم" to "Shamim",     "نرگس" to "Nargis",
        "روبینہ" to "Rubina",      "شبنم" to "Shabnam",    "ثناء" to "Sana",
        "عمارہ" to "Amara",        "حنا" to "Hina",        "نیلم" to "Nilam",
        "رضیہ" to "Razia",         "حمیرا" to "Humaira",  "منیرہ" to "Munira",
        // Compound titles
        "عبدالله" to "Abdullah",   "عبدالرحمن" to "Abdur Rahman",
        "عبدالرزاق" to "Abdul Razzaq","عبدالعزیز" to "Abdul Aziz",
        "عبدالکریم" to "Abdul Karim","عبدالرحیم" to "Abdul Rahim",
        "عبدالواحد" to "Abdul Wahid","عبدالرب" to "Abdul Rabb",
        "سید" to "Syed",           "شیخ" to "Sheikh",
        // Common last names
        "چودھری" to "Chaudhry",    "قریشی" to "Qureshi",  "گیلانی" to "Gilani",
        "بھٹو" to "Bhutto",        "شریف" to "Sharif",    "نواز" to "Nawaz",
        "خان" to "Khan",            "ملک" to "Malik",       "راجہ" to "Raja",
        "میر" to "Mir",             "بیگ" to "Baig",        "ظفر" to "Zafar"
    )

    // ── Spelling corrections (Latin names commonly misspelled) ──────────────────

    private val SPELLING_FIXES: Map<String, String> = mapOf(
        "muhammed" to "Muhammad",   "mohammed" to "Muhammad",   "mohamad" to "Muhammad",
        "mohd" to "Muhammad",       "muhmmad" to "Muhammad",    "muhamad" to "Muhammad",
        "ahmead" to "Ahmed",        "ahmd" to "Ahmed",          "ahamed" to "Ahmed",
        "ahamed" to "Ahmed",        "muhammmed" to "Muhammad",
        "usman" to "Usman",         "osman" to "Usman",
        "umer" to "Umar",           "omer" to "Umar",
        "bilall" to "Bilal",        "billal" to "Bilal",
        "husain" to "Hussain",      "hussian" to "Hussain",    "hussein" to "Hussain",
        "hasan" to "Hassan",        "hasaan" to "Hassan",
        "fatma" to "Fatima",        "fatimah" to "Fatima",
        "aisha" to "Ayesha",        "aesha" to "Ayesha",       "aeesha" to "Ayesha",
        "khadijah" to "Khadija",
        "zainab" to "Zainab",       "zaynab" to "Zainab",
        "maryam" to "Maryam",       "mariam" to "Maryam",
        "ibraheem" to "Ibrahim",    "ibrahm" to "Ibrahim",
        "yousaf" to "Yusuf",        "yousuf" to "Yusuf",       "yousif" to "Yusuf",
        "ismaeel" to "Ismail",      "ismaeil" to "Ismail",
        "suleman" to "Suleman",     "suliman" to "Suleman",    "sulaiman" to "Suleman",
        "talha" to "Talha",         "talhah" to "Talha",
        "hamzah" to "Hamza",        "hamzaa" to "Hamza",
        "saad" to "Saad",           "sadd" to "Saad",
        "imraan" to "Imran",        "imraan" to "Imran",
        "faizel" to "Faisal",       "faizal" to "Faisal",
        "waqaar" to "Waqar",        "waqer" to "Waqar",
        "kamraan" to "Kamran",      "qamran" to "Kamran",
        "shahed" to "Shahid",       "shaheed" to "Shahid",
        "sarfraz" to "Sarfaraz",
        "abdulla" to "Abdullah",    "abdullha" to "Abdullah",
        "chaudhary" to "Chaudhry", "choudry" to "Chaudhry",   "choudhry" to "Chaudhry",
        "quereshi" to "Qureshi",   "qureshi" to "Qureshi",    "quraishi" to "Qureshi",
        "muhammadali" to "Muhammad Ali",
        "saira" to "Saira",         "sayra" to "Saira",
        "nadia" to "Nadia",         "nadiya" to "Nadia",
        "sana" to "Sana",           "sanaa" to "Sana",
        "hina" to "Hina",           "heena" to "Hina",
        "rubina" to "Rubina",       "ruba" to "Rubina"
    )

    // ── Abbreviation expansion (South-Asian convention) ──────────────────────────

    // Title abbreviations kept as-is (Dr., Prof., etc.)
    private val TITLE_ABBREVIATIONS = setOf("dr", "prof", "eng", "mr", "mrs", "ms", "gen", "col", "maj", "capt", "lt", "sgt")

    // First-name single-letter initials common in Pakistan
    private val INITIAL_TO_FIRST_NAME: Map<String, String> = mapOf(
        "m" to "Muhammad", "a" to "Abdul", "s" to "Syed", "k" to "Khalid",
        "z" to "Zahid",    "n" to "Nasir", "r" to "Rahim", "h" to "Hassan",
        "f" to "Farooq",   "i" to "Imran", "b" to "Bilal", "w" to "Wasim",
        "t" to "Tariq",    "u" to "Usman", "e" to "Ejaz",  "q" to "Qaisar",
        "j" to "Javed",    "o" to "Owais", "y" to "Yasir", "g" to "Ghulam",
        "l" to "Liaquat",  "c" to "Ch.",   "d" to "Dawood"
    )

    // ── Public API ───────────────────────────────────────────────────────────────

    fun normalize(rawName: String): NormalizeResult {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return NormalizeResult(rawName, false, "empty")

        val reasons = mutableListOf<String>()
        var name = trimmed

        // 1. Transliterate non-Latin script
        if (hasNonLatinScript(name)) {
            val transliterated = transliterate(name)
            if (transliterated != name) { name = transliterated; reasons += "transliterated" }
        }

        // 2. Remove junk (embedded digits, multiple spaces, special chars except hyphen/apostrophe)
        val cleaned = name.replace(Regex("[0-9]+"), "")
            .replace(Regex("[^a-zA-Z\\s'\\-.]"), "")
            .replace(Regex("\\s{2,}"), " ").trim()
        if (cleaned != name) { name = cleaned; reasons += "cleaned junk" }

        // 3. Spelling correction (on lowercased whole name or individual words)
        val spellFixed = correctSpellings(name)
        if (spellFixed != name) { name = spellFixed; reasons += "spelling corrected" }

        // 4. Abbreviation expansion
        val expanded = expandAbbreviations(name)
        if (expanded != name) { name = expanded; reasons += "abbreviations expanded" }

        // 5. Proper capitalization
        val capped = properCapitalize(name)
        if (capped != name) { name = capped; reasons += "capitalization fixed" }

        val changed = name != trimmed
        return NormalizeResult(name, changed, if (reasons.isEmpty()) "no change" else reasons.joinToString(", "))
    }

    /** Detect if name contains Urdu/Arabic/Devanagari/other non-Latin script. */
    fun hasNonLatinScript(name: String): Boolean = name.any { c ->
        val cp = c.code
        cp in 0x0600..0x06FF ||   // Arabic/Urdu
        cp in 0x0750..0x077F ||   // Arabic Supplement
        cp in 0x0900..0x097F ||   // Devanagari (Hindi)
        cp in 0x0980..0x09FF ||   // Bengali
        cp in 0x0A00..0x0A7F ||   // Gurmukhi (Punjabi)
        cp in 0x0400..0x04FF ||   // Cyrillic
        cp in 0x4E00..0x9FFF ||   // CJK
        cp in 0xFB50..0xFDFF ||   // Arabic Presentation Forms-A
        cp in 0xFE70..0xFEFF      // Arabic Presentation Forms-B
    }

    // ── Transliteration ──────────────────────────────────────────────────────────

    private fun transliterate(name: String): String {
        // First try known-name dictionary (multi-word lookup)
        val trimmedLower = name.trim()
        // Check whole name
        KNOWN_NAMES[trimmedLower]?.let { return it }

        // Check word by word
        val words = trimmedLower.split(Regex("\\s+"))
        val translated = words.map { word ->
            KNOWN_NAMES[word] ?: transliterateWord(word)
        }
        val result = translated.joinToString(" ").trim()
        return result.ifBlank { name }
    }

    private fun transliterateWord(word: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < word.length) {
            val c = word[i]
            // Try two-char sequence first (e.g., 'ش' is single char but maps to "sh")
            CHAR_MAP[c]?.let { sb.append(it); i++; return@let }
                ?: run { sb.append(if (c.code < 128) c else ""); i++ }
        }
        val result = sb.toString().trim()
        return result.ifBlank { word }
    }

    // ── Spelling correction ───────────────────────────────────────────────────────

    private fun correctSpellings(name: String): String {
        // Try full name first
        SPELLING_FIXES[name.lowercase()]?.let { return it }

        // Word by word
        val words = name.split(Regex("\\s+"))
        return words.joinToString(" ") { word ->
            SPELLING_FIXES[word.lowercase()] ?: word
        }
    }

    // ── Abbreviation expansion ────────────────────────────────────────────────────

    private fun expandAbbreviations(name: String): String {
        val parts = name.split(Regex("\\s+")).toMutableList()
        for (i in parts.indices) {
            val w = parts[i]
            val lower = w.trimEnd('.').lowercase()
            // Single letter or single letter + dot
            if ((w.length == 1 || (w.length == 2 && w.endsWith('.'))) && i == 0) {
                if (lower !in TITLE_ABBREVIATIONS) {
                    INITIAL_TO_FIRST_NAME[lower]?.let { parts[i] = it }
                }
            }
        }
        return parts.joinToString(" ")
    }

    // ── Capitalization ────────────────────────────────────────────────────────────

    private fun properCapitalize(name: String): String {
        val lowercase_words = setOf("bin", "bint", "ul", "al", "el", "ibn", "de", "van", "von")
        return name.split(Regex("\\s+")).mapIndexed { idx, word ->
            if (idx > 0 && word.lowercase() in lowercase_words) word.lowercase()
            else word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }.joinToString(" ")
    }

    // ── Phone number normalization ───────────────────────────────────────────────

    /**
     * Normalizes a phone number to a canonical form for deduplication.
     * Pakistan: converts 03xx → +923xx, strips separators.
     */
    fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() || it == '+' }
        return when {
            digits.startsWith("+") -> digits
            digits.startsWith("0092") -> "+92${digits.drop(4)}"
            digits.startsWith("92") && digits.length == 12 -> "+$digits"
            digits.startsWith("0") && digits.length == 11 -> "+92${digits.drop(1)}"
            else -> digits
        }
    }

    /** Returns true if two phone strings resolve to the same number. */
    fun phonesAreEqual(a: String, b: String): Boolean =
        normalizePhone(a) == normalizePhone(b) ||
        a.filter { it.isDigit() }.takeLast(8) == b.filter { it.isDigit() }.takeLast(8)
}
