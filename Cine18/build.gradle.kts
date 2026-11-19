version = 1

cloudstream {
    authors     = listOf("Phisher98")
    requiresResources = false
    language    = "hi"
    description = "Cine18 18+ Hot Webseries and Movies"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://cinewebby.com&size=64"
}
