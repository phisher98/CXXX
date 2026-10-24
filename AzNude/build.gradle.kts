// use an integer for version numbers
version = 3
cloudstream {
    description = "AZNude - Nude Celebrities from A to Z"
    authors = listOf("Phisher98")
    requiresResources = false

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "NSFW"
    )
    language = "en"

    iconUrl = "https://cdn.aznude.com/images/logo/v5/main-logo.svg"
}
