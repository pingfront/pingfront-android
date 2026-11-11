package dev.pingfront.ui

import android.icu.text.PluralRules
import android.icu.util.ULocale
import android.text.TextUtils
import android.view.View
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * The chat's strings in its 50 languages: every name, English's
 * placeholders, every plural category the language has, no English left untranslated, the push channel
 * in every language, and the right-to-left languages detected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationCatalogTest {
    private val res = File("src/main/res")
    private val pushRes = File("../pingfront-push/src/main/res")

    internal companion object {
        /** Each shipped language and its resource folder. */
        val folders = mapOf(
            "ar" to "values-ar", "bg" to "values-bg", "bn" to "values-bn", "ca" to "values-ca", "cs" to "values-cs",
            "da" to "values-da", "de" to "values-de", "el" to "values-el", "es" to "values-es", "es-419" to "values-b+es+419",
            "fa" to "values-fa", "fi" to "values-fi", "fr" to "values-fr", "fr-CA" to "values-fr-rCA", "gu" to "values-gu",
            "he" to "values-iw", "hi" to "values-hi", "kn" to "values-kn", "ml" to "values-ml", "mr" to "values-mr",
            "or" to "values-or", "pa" to "values-pa", "te" to "values-te", "zh-HK" to "values-zh-rHK",
            "hr" to "values-hr", "hu" to "values-hu", "id" to "values-in", "it" to "values-it", "ja" to "values-ja",
            "ko" to "values-ko", "ms" to "values-ms", "nb" to "values-nb", "nl" to "values-nl", "pl" to "values-pl",
            "pt-BR" to "values-pt-rBR", "pt-PT" to "values-pt-rPT", "ro" to "values-ro", "ru" to "values-ru", "sk" to "values-sk",
            "sl" to "values-sl", "sv" to "values-sv", "ta" to "values-ta", "th" to "values-th", "tr" to "values-tr",
            "uk" to "values-uk", "ur" to "values-ur", "vi" to "values-vi", "zh-Hans" to "values-b+zh+Hans", "zh-Hant" to "values-b+zh+Hant",
        )
    }

    /** Strings whose English text is also right in other languages: placeholders with punctuation, and the AI label. */
    private val sameAsEnglish = setOf("pf_ai", "pf_character_count", "pf_message_from")

    /** Words spelled as in English that are the language's own (checked one by one). */
    private val homographs = mapOf(
        "da" to setOf("pf_send", "pf_topic_feedback"),
        "nb" to setOf("pf_send"),
        "de" to setOf("pf_screenshot"),
        "it" to setOf("pf_screenshot"),
        "nl" to setOf("pf_screenshot", "pf_duration_week"),
        "fr" to setOf("pf_message", "pf_photos", "pf_photo", "pf_assistant", "pf_assistant_mark"),
        "fr-CA" to setOf("pf_message", "pf_photos", "pf_photo", "pf_assistant", "pf_assistant_mark"),
    )

    /** name -> quantity ("" for a plain string) -> text. */
    private fun read(file: File): Map<String, Map<String, String>> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = mutableMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val name = el.getAttribute("name")
            result[name] = when (el.tagName) {
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    (0 until items.length).associate { (items.item(it) as Element).let { item -> item.getAttribute("quantity") to item.textContent } }
                }
                else -> mapOf("" to el.textContent)
            }
        }
        return result
    }

    private fun read(folder: String) = read(File(res, "$folder/pf_strings.xml"))

    private val placeholder = Regex("""%(?:\d+\$)?([dsf])""")

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.groupValues[1] }.sorted().toList()

    @Test fun everyShippedLanguageIsListed() {
        val shipped = res.listFiles().orEmpty().filter { File(it, "pf_strings.xml").exists() && it.name != "values" }.map { it.name }.toSet()
        assertEquals(folders.values.toSet(), shipped)
    }

    @Test fun everyNameIsTranslatedWithEnglishPlaceholders() {
        val english = read("values")
        val problems = mutableListOf<String>()
        for ((language, folder) in folders) {
            val strings = read(folder)
            for ((name, forms) in english) {
                val translated = strings[name] ?: run {
                    problems += "$language missing $name"
                    null
                } ?: continue
                val reference = placeholders(forms["other"] ?: forms.getValue(""))
                for ((quantity, text) in translated) {
                    val exact = quantity == "zero" || (language == "ar" && quantity in setOf("one", "two"))
                    val found = placeholders(text)
                    if (found != reference && !(exact && found.isEmpty())) problems += "$language[$quantity] $name: $text"
                }
            }
            (strings.keys - english.keys).forEach { problems += "$language has $it, which English doesn't" }
        }
        assertTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }

    @Test fun pluralsHaveEveryCategoryOfTheLanguage() {
        val english = read("values").filterValues { "other" in it }.keys
        val problems = mutableListOf<String>()
        for ((language, folder) in folders) {
            val want = PluralRules.forLocale(ULocale.forLanguageTag(language)).keywords
            for ((name, forms) in read(folder).filterKeys { it in english }) {
                if (forms.keys != want) problems += "$language $name: ${forms.keys.sorted()}, want ${want.sorted()}"
            }
        }
        assertTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }

    @Test fun noEnglishIsLeftUntranslated() {
        val english = read("values")
        val problems = mutableListOf<String>()
        for ((language, folder) in folders) {
            for ((name, forms) in read(folder)) {
                if (name in sameAsEnglish || name in homographs[language].orEmpty()) continue
                val reference = english[name]?.values.orEmpty().toSet()
                forms.values.filter { it in reference }.forEach { problems += "$language $name is English: $it" }
                forms.values.filter { Regex("(?i)pingfront").containsMatchIn(it) && !it.contains("pingfront") }
                    .forEach { problems += "$language $name capitalizes pingfront: $it" }
            }
        }
        assertTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }

    /**
     * The documented address form of each language: words of the other form that must not appear. Whole
     * words, case-sensitive; raw entries are patterns (a capitalized formal pronoun mid-sentence, suffixes).
     * The web widget's locales.test.ts checks its packs with the same table.
     */
    private val addressWords = mapOf(
        "tr" to listOf("siz", "size", "sizin", "sizi", "sizden", "açın", "edin", "girin", "çizin", "düzeltin", "yönetin", "değiştirin", "gelin"),
        "fr" to listOf("tu", "toi", "ton", "ta", "tes", "appli", "applis"),
        "fr-CA" to listOf("tu", "toi", "ton", "ta", "tes", "appli", "applis"),
        "es" to listOf("usted", "ustedes"),
        "es-419" to listOf("usted", "ustedes", "vos"),
        "pt-BR" to listOf("tu", "teu", "tua", "teus", "tuas"),
        "pt-PT" to listOf("você", "vocês", "tu", "teu", "tua", "teus", "tuas"),
        "nl" to listOf("u", "uw"),
        "ca" to listOf("vostè", "vostès", "vós"),
        "cs" to listOf("ty", "tvůj", "tvoje", "tvá", "tvé", "můžeš", "máš"),
        "sk" to listOf("ty", "tvoj", "tvoja", "tvoje", "môžeš", "máš"),
        "hr" to listOf("ti", "tvoj", "tvoja", "tvoje", "tebi", "tebe", "možeš"),
        "sl" to listOf("ti", "tvoj", "tvoja", "tvoje", "tebi", "tebe"),
        "bg" to listOf("ти", "твоя", "твоят", "твоето", "твоите", "теб", "можеш"),
        "el" to listOf("εσύ", "σου", "σένα"),
        "ro" to listOf("tu", "tău", "tale", "tine"),
        "ru" to listOf("ты", "твой", "твоя", "твоё", "твои", "тебе", "тебя"),
        "uk" to listOf("ти", "твій", "твоя", "твоє", "твої", "тобі", "тебе"),
        "pl" to listOf("Pan", "Pani", "Pana", "Panu", "Państwo", "Państwa"),
        "hu" to listOf("Ön", "Önnek", "Önt", "Öné", "Önök"),
        "fi" to listOf("te", "teidän", "teille", "teitä"),
        "id" to listOf("kamu", "kau", "engkau"),
        "ms" to listOf("kamu", "kau", "engkau", "awak"),
        "hi" to listOf("तुम", "तू", "तुम्हारा", "तुम्हारी"),
        "bn" to listOf("তুমি", "তোমার", "তোমাকে"),
        "ko" to listOf("당신"),
        "mr" to listOf("तू", "तुझा", "तुझी", "तुझे", "तुला"),
        "gu" to listOf("તું", "તારું", "તારી", "તારો", "તને"),
        "pa" to listOf("ਤੂੰ", "ਤੇਰਾ", "ਤੇਰੀ", "ਤੇਰੇ", "ਤੈਨੂੰ"),
        "kn" to listOf("ನೀನು", "ನಿನ್ನ", "ನಿನಗೆ"),
        "te" to listOf("నువ్వు", "నీకు", "నీ", "నిన్ను"),
        "ml" to listOf("നീ", "നിന്റെ", "നിനക്ക്", "നിന്നെ"),
        "or" to listOf("ତୁ", "ତୁମେ", "ତୁମର", "ତୋର", "ତୁମକୁ"),
    )
    private val addressPatterns = mapOf(
        "tr" to listOf("[ıiuü]n[ıiuü]z(?!\\p{L})", "y[ıiuü]n(?!\\p{L})"),
        "zh-Hans" to listOf("您"),
        "zh-Hant" to listOf("您"),
        "zh-HK" to listOf("您"),
        "de" to listOf("[a-zäöüß,] (?:Sie|Ihr|Ihre|Ihren|Ihrem|Ihrer|Ihnen)(?!\\p{L})"),
        "it" to listOf("[a-zàèéìòù,] (?:Lei|Suo|Sua|Suoi)(?!\\p{L})"),
        "hu" to listOf("a\\(z\\)"),
        "da" to listOf("[a-zæøå,] (?:De|Dem|Deres)(?!\\p{L})"),
        "nb" to listOf("[a-zæøå,] (?:De|Dem|Deres)(?!\\p{L})"),
        "sv" to listOf("[a-zåäö,] (?:Ni|Er|Ert|Era)(?!\\p{L})"),
        "vi" to listOf("quý khách", "quý vị"),
        "ta" to listOf("உன்", "உனக்கு"),
        "th" to listOf("ครับ", "ค่ะ"),
    )

    @Test fun everyLanguageUsesItsDocumentedAddressForm() {
        val problems = mutableListOf<String>()
        for ((language, folder) in folders) {
            val patterns = addressWords[language].orEmpty().map { Regex("(?<![\\p{L}\\p{M}])$it(?![\\p{L}\\p{M}])") } +
                addressPatterns[language].orEmpty().map { Regex(it) }
            val strings = read(folder) + read(File(pushRes, "$folder/pf_push_strings.xml"))
            for ((name, forms) in strings) {
                for (text in forms.values) {
                    patterns.filter { it.containsMatchIn(text) }.forEach { problems += "$language $name: $text (${it.pattern})" }
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.take(20).joinToString("\n"))
    }

    @Test fun thePushChannelIsNamedInEveryLanguage() {
        val english = read(File(pushRes, "values/pf_push_strings.xml")).getValue("pf_push_channel").getValue("")
        for ((language, folder) in folders) {
            val file = File(pushRes, "$folder/pf_push_strings.xml")
            assertTrue(file.exists(), "$language has no pf_push_strings.xml")
            val name = read(file)["pf_push_channel"]?.get("").orEmpty()
            assertTrue(name.isNotBlank() && name != english, "$language push channel: $name")
        }
    }

    @Test fun rightToLeftLanguagesAreDetected() {
        val rtl = folders.keys.filter { TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(it)) == View.LAYOUT_DIRECTION_RTL }
        assertEquals(setOf("ar", "fa", "he", "ur"), rtl.toSet())
    }
}
