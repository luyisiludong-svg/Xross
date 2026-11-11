package dev.xross.launcher

import dev.xross.launcher.scrape.HtmlText
import org.junit.Assert.assertEquals
import org.junit.Test

/** ScreenScraper sends descriptions with HTML character references; they must not reach the screen. */
class HtmlTextTest {
    @Test fun decodesNamedNumericAndDoubleEncodedReferences() {
        assertEquals("\"1942\" is a shoot-'em-up", HtmlText.decode("&quot;1942&quot; is a shoot-&#039;em-up"))
        assertEquals("Pokémon & Co. — “Gold”", HtmlText.decode("Pok&#xE9;mon &amp; Co. &mdash; &ldquo;Gold&rdquo;"))
        assertEquals("\"Super Ace\"", HtmlText.decode("&amp;quot;Super Ace&amp;quot;"))
    }

    @Test fun leavesPlainAmpersandsAndUnknownReferencesAlone() {
        assertEquals("Tom & Jerry", HtmlText.decode("Tom & Jerry"))
        assertEquals("R&D &unknownref; &#0; &#xD800;", HtmlText.decode("R&D &unknownref; &#0; &#xD800;"))
        assertEquals("no references", HtmlText.decode("no references"))
    }
}
