version = 31

cloudstream {
    description = "Moviesmod.farm - Movies, Web Series, TV Shows & Anime"
    authors = listOf("Moviesmod")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime"
    )

    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/drawable/cloud_bolt.png"
}
