package com.jun9100.opensave

/** Rules shared by SAF imports and restores so a document name cannot escape its workspace. */
object PathPolicy {
    fun isSafeDocumentName(name: String): Boolean {
        return name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains('\u0000')
    }

    fun requireSafeDocumentName(name: String): String {
        require(isSafeDocumentName(name)) { "Unsafe document name" }
        return name
    }
}
