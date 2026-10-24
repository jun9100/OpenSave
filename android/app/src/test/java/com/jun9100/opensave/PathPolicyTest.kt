package com.jun9100.opensave

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPolicyTest {
    @Test
    fun acceptsNormalUnicodeDocumentNames() {
        assertTrue(PathPolicy.isSafeDocumentName("存档 01.sav"))
        assertTrue(PathPolicy.isSafeDocumentName("profile-1"))
    }

    @Test
    fun rejectsPathTraversalAndSeparators() {
        assertFalse(PathPolicy.isSafeDocumentName(""))
        assertFalse(PathPolicy.isSafeDocumentName("."))
        assertFalse(PathPolicy.isSafeDocumentName(".."))
        assertFalse(PathPolicy.isSafeDocumentName("../outside"))
        assertFalse(PathPolicy.isSafeDocumentName("nested/file"))
        assertFalse(PathPolicy.isSafeDocumentName("nested\\file"))
    }
}
