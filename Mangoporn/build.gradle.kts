version = 3

cloudstream {
    authors     = listOf("HindiProvider")
    language    = "en"
    description = "MangoPorn"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "http://mangoporn.net/wp-content/uploads/2024/07/mangoporn.net_.png"
}
