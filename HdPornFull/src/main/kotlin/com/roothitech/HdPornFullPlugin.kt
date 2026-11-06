package roothitech

/**
 * The object the host registers. Thin glue — delegates to extractor.
 */
object ProviderPlugin : Provider {

    override val id: String = "roothitech.hdpornfull"
    override val name: String = "HdPornFull"
    override val version: String = "0.8.0"

    private lateinit var cfg: ProviderConfig
    private lateinit var extractor: ProviderExtractor
    private var ready = false

    override fun init(config: ProviderConfig) {
        cfg = config
        extractor = ProviderExtractor(config)
        ready = true
    }

    override suspend fun home(page: Int): ProviderResult {
        check(ready) { "ProviderPlugin.init() not called" }
        return extractor.list(cfg.baseUrl.trimEnd('/') + "/page/$page/")
    }

    override suspend fun category(slug: String, page: Int): ProviderResult {
        check(ready) { "ProviderPlugin.init() not called" }
        return extractor.list(cfg.baseUrl.trimEnd('/') + "/category/$slug/page/$page/")
    }

    override suspend fun search(query: String, page: Int): ProviderResult {
        check(ready) { "ProviderPlugin.init() not called" }
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return extractor.list(cfg.baseUrl.trimEnd('/') + "/search/$q/?page=$page")
    }

    override suspend fun resolve(videoPageUrl: String): StreamInfo {
        check(ready) { "ProviderPlugin.init() not called" }
        return extractor.stream(videoPageUrl)
    }

    override fun release() {
        ready = false
    }
}