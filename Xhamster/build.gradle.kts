// use an integer for version numbers
version = 1

cloudstream {
    // All of these properties are optional, you can safely remove any of them.

    language = "vi"
    authors = listOf("SIX")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // Will be 3 if unspecified

    tvTypes = listOf(
        "NSFW"
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=https://vi.xhspot.com&sz=256"

    isCrossPlatform = true
}
