// use an integer for version numbers
version = 1

cloudstream {
    language = "hi"
    description = "Watch Anime & Cartoons in Hindi, Tamil, Telugu, English, Japanese Multi Audio from ToonWorld4All.me"
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
    )

    iconUrl = "https://toonworld4all.me/wp-content/uploads/2023/01/cropped-toonworld4all-1-192x192.png"
}