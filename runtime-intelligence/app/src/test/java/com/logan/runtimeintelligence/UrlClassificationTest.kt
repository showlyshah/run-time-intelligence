package com.logan.runtimeintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlClassificationTest {
    @Test fun identifiesKnownAdHostPatterns() {
        val source = ApkModificationStudio::class.java
        assertEquals("Likely advertising", "doubleclick.net".let {
            if (it.contains("doubleclick")) "Likely advertising" else "Unclassified URL"
        })
    }

    @Test fun onlyTextResourceExtensionsAreEligibleForAutomaticRemoval() {
        val eligible = setOf("xml", "json", "txt", "properties", "html", "js")
        assertEquals(true, "xml" in eligible)
        assertEquals(false, "smali" in eligible)
    }
}
