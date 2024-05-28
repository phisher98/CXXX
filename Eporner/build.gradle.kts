version = 1

cloudstream {
    authors     = listOf("HindiProvider")
    language    = "en"
    description = "Eporner"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://github.com/phisher98/TVVVV/blob/main/eporner.ico"
}
