package dev.pingfront.ui

import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import dev.pingfront.conversation.ConversationState
import dev.pingfront.look.ChatTopic
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.LocalizedText
import dev.pingfront.model.TopicSymbol
import java.util.Locale

/**
 * Applies `PingFront.language` to the SDK's screens: the locale of resources and formatting, and the layout
 * direction (right to left for Arabic, Hebrew, Persian and Urdu). Without an override the app's configuration stays.
 *
 * The host chose the language in its own picker, so it owns the bundle configuration: an app that
 * splits its bundle by language must keep the chosen languages installed (README, "Unread count and events").
 */
@android.annotation.SuppressLint("AppBundleLocaleChanges")
@Composable
internal fun PingFrontLanguage(content: @Composable () -> Unit) {
    val tag by dev.pingfront.PingFront.instance.language.collectAsState()
    val override = tag ?: return content()
    val base = LocalConfiguration.current
    val context = LocalContext.current
    val locale = remember(override) { Locale.forLanguageTag(override) }
    val configuration = remember(base, locale) {
        Configuration(base).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }
    // A wrapper, not the configuration context itself: code that looks for the Activity still finds it.
    val localized = remember(context, configuration) {
        val resources = context.createConfigurationContext(configuration).resources
        object : ContextWrapper(context) {
            override fun getResources(): Resources = resources
        }
    }
    val direction = if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localized,
        LocalResources provides localized.resources,
        LocalLayoutDirection provides direction,
        content = content,
    )
}

/** The person's language tag for customer-written [LocalizedText] (follows the composition's locale). */
@Composable
internal fun languageTag(): String = LocalConfiguration.current.locales[0]?.toLanguageTag() ?: "en"

@Composable
internal fun LocalizedText?.text(): String? = this?.resolve(languageTag())

/**
 * The reply expectation: the thread's, else the look's, else "Usually replies within a day", as on iOS and the web
 * widget. Null only when the app turns the line off (`showReplyExpectation` false).
 */
@Composable
internal fun replyExpectation(look: ResolvedLook, state: ConversationState): String? = if (look.showReplyExpectation) {
    state.replyExpectation ?: look.replyExpectation.text() ?: stringResource(R.string.pf_reply_expectation)
} else {
    null
}

/** A topic's label: the customer's text, or the built-in translation for presets. */
@Composable
internal fun ChatTopic.displayLabel(): String = label.text() ?: stringResource(
    when (id) {
        "billing" -> R.string.pf_topic_billing
        "bug" -> R.string.pf_topic_bug
        "idea" -> R.string.pf_topic_idea
        "question" -> R.string.pf_topic_question
        "account" -> R.string.pf_topic_account
        "feedback" -> R.string.pf_topic_feedback
        "restore" -> R.string.pf_topic_restore
        "privacy" -> R.string.pf_topic_privacy
        "gift" -> R.string.pf_topic_gift
        else -> R.string.pf_topic_other
    },
)

/**
 * What a topic chip puts into the composer, as on iOS: the customer's prefill wins; presets put their sentence start;
 * any other topic puts its label ("Shipping: "). `null` for "Report a bug", which opens the report instead.
 */
@Composable
internal fun ChatTopic.starter(): String? {
    prefill.text()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (id == ChatTopic.Bug.id) return null
    val preset = when (id) {
        "billing" -> R.string.pf_start_billing
        "idea" -> R.string.pf_start_idea
        "question" -> R.string.pf_start_question
        "account" -> R.string.pf_start_account
        "feedback" -> R.string.pf_start_feedback
        "restore" -> R.string.pf_start_restore
        "privacy" -> R.string.pf_start_privacy
        "gift" -> R.string.pf_start_gift
        else -> null
    }
    return if (preset != null) stringResource(preset) else "${displayLabel()}: "
}

internal val TopicSymbol.icon: Int
    get() = when (this) {
        TopicSymbol.Billing -> R.drawable.pf_ic_credit_card
        TopicSymbol.Bug -> R.drawable.pf_ic_bug_report
        TopicSymbol.Idea -> R.drawable.pf_ic_lightbulb
        TopicSymbol.Question -> R.drawable.pf_ic_help
        TopicSymbol.Account -> R.drawable.pf_ic_person
        TopicSymbol.Feedback -> R.drawable.pf_ic_feedback
        TopicSymbol.Gift -> R.drawable.pf_ic_redeem
        TopicSymbol.Restore -> R.drawable.pf_ic_history
        TopicSymbol.Privacy -> R.drawable.pf_ic_shield
        TopicSymbol.Other -> R.drawable.pf_ic_more_horiz
    }

/** The assistant's name: the configured one, or the author's name, or "Assistant". */
@Composable
internal fun aiDisplayName(look: ResolvedLook, author: String?): String =
    if (look.aiName != DEFAULT_AI_NAME) look.aiName else author ?: stringResource(R.string.pf_assistant)

private const val DEFAULT_AI_NAME = "Assistant"
