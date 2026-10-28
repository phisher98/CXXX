version = 2

cloudstream {
    language    = "hi"
    description = "Indian/desi MMS clips and photo albums"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://cdn.mmsvibex.fit/favicon.png"
}
