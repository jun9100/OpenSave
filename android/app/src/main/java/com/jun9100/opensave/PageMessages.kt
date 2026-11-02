package com.jun9100.opensave

/** A result line and an error line owned by the page whose control started the operation. */
data class PageMessage(
    val action: String? = null,
    val error: String? = null,
)

/**
 * Status and errors are kept per page, so a settings result such as
 * "SaveVault 云端连接成功" never appears in the game library and vice versa.
 */
data class PageMessages(private val byPage: Map<OpenSavePage, PageMessage> = emptyMap()) {
    operator fun get(page: OpenSavePage): PageMessage = byPage[page] ?: PageMessage()

    /** Replaces both lines of [page]; other pages keep theirs. */
    fun with(page: OpenSavePage, action: String? = null, error: String? = null): PageMessages =
        PageMessages(byPage + (page to PageMessage(action, error)))

    /** Sets the error line of [page] and keeps its last action line. */
    fun withError(page: OpenSavePage, error: String?): PageMessages =
        PageMessages(byPage + (page to get(page).copy(error = error)))
}
