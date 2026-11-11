package dev.pingfront.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The rating card's star label agrees with the number its noun counts in each language: the total in English
 * ("1 of 5 stars"), the rating in French ("1 étoile sur 5"). iOS and the web say the same (scripts/locales-check.mjs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StarsLabelTest {
    private fun labels(qualifier: String, total: Int = 5, ratings: List<Int> = listOf(1, 2, 5)): List<String> {
        RuntimeEnvironment.setQualifiers("+$qualifier")
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        val on = res.getInteger(R.integer.pf_stars_plural_on)
        return ratings.map { res.getQuantityString(R.plurals.pf_stars, starsQuantity(on, it, total), it, total) }
    }

    @Test fun englishAgreesWithTheTotal() {
        assertEquals(listOf("1 of 5 stars", "2 of 5 stars", "5 of 5 stars"), labels("en"))
        assertEquals(listOf("1 of 1 star"), labels("en", total = 1, ratings = listOf(1)))
    }

    @Test fun frenchAgreesWithTheRating() {
        assertEquals(listOf("1 étoile sur 5", "2 étoiles sur 5", "5 étoiles sur 5"), labels("fr"))
        assertEquals(listOf("1 étoile sur 5", "2 étoiles sur 5", "5 étoiles sur 5"), labels("fr-rCA"))
    }

    @Test fun arabicAgreesWithTheTotal() {
        assertEquals(listOf("1 من 5 نجوم", "2 من 5 نجوم", "5 من 5 نجوم"), labels("ar"))
        assertEquals(listOf("1 من 2 نجمتين"), labels("ar", total = 2, ratings = listOf(1)))
    }

    @Test fun russianAndPolishReadAsAScore() {
        assertEquals(listOf("Оценка: 1 из 5", "Оценка: 2 из 5", "Оценка: 5 из 5"), labels("ru"))
        assertEquals(listOf("Ocena: 1 z 5", "Ocena: 2 z 5", "Ocena: 5 z 5"), labels("pl"))
    }
}
