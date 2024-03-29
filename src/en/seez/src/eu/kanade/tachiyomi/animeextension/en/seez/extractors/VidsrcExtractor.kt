package eu.kanade.tachiyomi.animeextension.en.seez.extractors

import eu.kanade.tachiyomi.animeextension.en.seez.VrfHelper
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
// Stolen from fmovies
class VidsrcExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()
    private val vrfHelper by lazy { VrfHelper(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val httpUrl = url.toHttpUrl()
        val host = httpUrl.host
        val referer = "https://$host/"

        val query = buildString {
            append(httpUrl.pathSegments.last())
            append("?")
            append(
                httpUrl.queryParameterNames.joinToString("&") {
                    "$it=${httpUrl.queryParameter(it)}"
                },
            )
        }

        val rawUrl = vrfHelper.getVidSrc(query, host).addAutoStart()

        val refererHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", host)
            add("Referer", url.addAutoStart())
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        val infoJson = client.newCall(
            GET(rawUrl, headers = refererHeaders),
        ).execute().parseAs<VidsrcResponse>()

        val subtitleList = httpUrl.queryParameter("sub.info")?.let {
            val subtitlesHeaders = headers.newBuilder().apply {
                add("Accept", "application/json, text/javascript, */*; q=0.01")
                add("Host", it.toHttpUrl().host)
                add("Origin", "https://$host")
                add("Referer", referer)
            }.build()

            client.newCall(
                GET(it, headers = subtitlesHeaders),
            ).execute().parseAs<List<FMoviesSubs>>().map {
                Track(it.file, it.label)
            }
        } ?: emptyList()

        return infoJson.result.sources.distinctBy { it.file }.flatMap {
            val url = it.file
                .toHttpUrl()
                .newBuilder()
                .fragment(null)
                .build()
                .toString()

            playlistUtils.extractFromHls(url, subtitleList = subtitleList, referer = referer, videoNameGen = { q -> "$name - $q" })
        }
    }

    private fun String.addAutoStart(): String {
        return this.toHttpUrl().newBuilder().setQueryParameter("autostart", "true").build().toString()
    }

    @Serializable
    data class VidsrcResponse(
        val result: ResultObject,
    ) {
        @Serializable
        data class ResultObject(
            val sources: List<SourceObject>,
        ) {
            @Serializable
            data class SourceObject(
                val file: String,
            )
        }
    }

    @Serializable
    data class FMoviesSubs(
        val file: String,
        val label: String,
    )
}
