version = 6

cloudstream {
    authors     = listOf("HindiProvider, megix")
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
    iconUrl = "https://raw.githubusercontent.com/phisher98/TVVVV/main/eporner.ico"
}
