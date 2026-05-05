// use an integer for version numbers
version = 2

cloudstream {
    language = "hi"
    description = "Watch Anime, Cartoons & Hindi Dubbed series from RareAnimes (Rare Toons India)"
    authors = listOf("kraptor")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "Cartoon",
        "OVA",
    )

    iconUrl = "https://india.rareanimes.com/wp-content/uploads/2020/05/cropped-Logo-1-192x192.png"
}
