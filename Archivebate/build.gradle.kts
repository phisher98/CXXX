version = 5

cloudstream {
    language    = "en"
    description = "Archived cam shows from Archivebate (archivebate.com)"

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
    **/
    status  = 1 // will be 3 if unspecified
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=archivebate.com&sz=%size%"
}
