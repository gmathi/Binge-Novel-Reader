package io.github.gmathi.novellibrary.source

import com.google.gson.JsonParser
import io.github.gmathi.novellibrary.model.database.Chapter
import io.github.gmathi.novellibrary.model.database.Novel
import io.github.gmathi.novellibrary.model.other.NovelsPage
import io.github.gmathi.novellibrary.model.source.filter.FilterList
import io.github.gmathi.novellibrary.model.source.online.HttpSource
import io.github.gmathi.novellibrary.network.GET
import io.github.gmathi.novellibrary.network.HostNames
import io.github.gmathi.novellibrary.util.Constants.NEOVEL_API_URL
import io.github.gmathi.novellibrary.util.Exceptions.MISSING_IMPLEMENTATION
import io.github.gmathi.novellibrary.util.Exceptions.NETWORK_ERROR
import io.github.gmathi.novellibrary.util.lang.asJsonNullFreeString
import io.github.gmathi.novellibrary.util.lang.covertJsonNull
import io.github.gmathi.novellibrary.util.system.encodeBase64ToString
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response


class NeovelSource : HttpSource() {


    override val baseUrl: String
        get() = NEOVEL_API_URL
    override val lang: String
        get() = "en"
    override val supportsLatest: Boolean
        get() = true
    override val name: String
        get() = "Neovel"

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", baseUrl)

    //region Search Novel
    override fun searchNovelsRequest(page: Int, query: String, filters: FilterList): Request {
        val url =
            baseUrl + "V2/books/search?language=ALL&filter=0&name=${query.encodeBase64ToString()}&sort=6&page=0&onlyOffline=true&genreIds=0&genreCombining=0&tagIds=0&tagCombining=0&minChapterCount=0&maxChapterCount=4000"
        return GET(url, headers = headers)
    }

    override fun searchNovelsParse(response: Response): NovelsPage {
        val searchResults: ArrayList<Novel> = ArrayList()
        val jsonString = response.body?.string() ?: throw Exception(NETWORK_ERROR)
        val jsonArray = JsonParser.parseString(jsonString).asJsonArray

        jsonArray.forEach { result ->
            val resultObject = result.asJsonObject
            val id = resultObject["id"].asInt.toString()
            val novelUrl = baseUrl + "V1/book/details?bookId=$id&language=EN"
            val novel = Novel(novelUrl)
            resultObject["name"].asJsonNullFreeString?.let { novel.name = it }
            novel.imageUrl = baseUrl + "V2/book/image?bookId=$id&oldApp=false"
            novel.metadata["id"] = id
            novel.externalNovelId = id
            novel.rating = resultObject["rating"].asFloat.toString()
            searchResults.add(novel)
        }

        return NovelsPage(searchResults, false)
    }

    //endregion

    //region Novel Details
    override fun novelDetailsParse(novel: Novel, response: Response): Novel {
        val jsonString = response.body?.string() ?: return novel
        val rootJsonObject = JsonParser.parseString(jsonString)?.asJsonObject ?: return novel
        val id = rootJsonObject["id"].asInt.toString()

        novel.imageUrl = "https://${HostNames.NEOVEL}/V2/book/image?bookId=$id&oldApp=false"
        novel.longDescription = rootJsonObject.get("bookDescription")?.asString
        novel.rating = rootJsonObject["rating"].asFloat.toString()
        novel.chaptersCount = rootJsonObject["nbrReleases"].asLong

        //If local fetch copy is empty, then get it from network
        if (neovelGenres == null) {
            getNeovelGenres()
        }
        neovelGenres?.let { map ->
            novel.genres = rootJsonObject.getAsJsonArray("genreIds")?.filter { map[it.asInt] != null }?.map { map[it.asInt]!! }
            novel.metadata["Genre(s)"] = rootJsonObject.getAsJsonArray("tagIds")?.filter { map[it.asInt] != null }?.joinToString(", ") { map[it.asInt]!! }
        }

        //If local fetch copy is empty, then get it from network
        if (neovelTags == null) {
            getNeovelTags()
        }
        neovelTags?.let { map ->
            novel.metadata["Tag(s)"] = rootJsonObject.getAsJsonArray("tagIds")?.filter { map[it.asInt] != null }?.joinToString(", ") { map[it.asInt]!! }
        }

        novel.metadata["Author(s)"] = rootJsonObject.getAsJsonArray("authors")?.joinToString(", ") {
            it.asJsonNullFreeString ?: ""
        }
        novel.metadata["Status"] = rootJsonObject["bookState"]?.asJsonNullFreeString ?: "N/A"
        novel.metadata["Release Frequency"] = rootJsonObject["releaseFrequency"]?.asJsonNullFreeString ?: "N/A"
        novel.metadata["Chapter Read Count"] = rootJsonObject["chapterReadCount"]?.asInt.toString()
        novel.metadata["Followers"] = rootJsonObject["followers"]?.asJsonNullFreeString ?: "N/A"
        novel.metadata["Initial publish date"] = rootJsonObject["votes"]?.asJsonNullFreeString ?: "N/A"
        novel.metadata["Votes"] = rootJsonObject["origin_loc"]?.asJsonNullFreeString ?: "N/A"
        return novel
    }
    //endregion

    //region Chapters

    override fun chapterListRequest(novel: Novel): Request {
        val novelId = novel.externalNovelId ?: novel.metadata["id"]
        val url = baseUrl + "V5/chapters?bookId=$novelId&language=EN"
        return GET(url, headers)
    }

    override fun chapterListParse(novel: Novel, response: Response): List<Chapter> {
        val jsonString = response.body?.string() ?: throw Exception(NETWORK_ERROR)
        val rootJsonObject = JsonParser.parseString(jsonString)?.asJsonObject?.getAsJsonObject("data")
            ?: throw Exception(NETWORK_ERROR)
        val releasesArray = rootJsonObject.getAsJsonArray("releases")

        var orderId = 0L
        val chapters = ArrayList<Chapter>()
        releasesArray.reversed().asSequence().forEach { release ->
            val releaseObject = release.asJsonObject
            val chapterNumber = releaseObject["chapter"].covertJsonNull?.asInt?.toString() ?: ""
            val fragment = releaseObject["fragment"].covertJsonNull?.asInt?.toString() ?: ""
            val postFix = releaseObject["postfix"].asJsonNullFreeString ?: ""
            val url = releaseObject["srcurl"].asJsonNullFreeString
            val sourceName = releaseObject["tlgroup"].covertJsonNull?.asJsonObject?.get("name")?.asJsonNullFreeString

            url?.let {
                val chapterName = arrayListOf(chapterNumber, fragment, postFix).filter { name -> name.isNotBlank() }.joinToString(" - ")
                val chapter = Chapter(it, chapterName)
                chapter.orderId = orderId++
                chapter.novelId = novel.id
                chapter.translatorSourceName = sourceName
                chapters.add(chapter)
            }
        }
        return chapters
    }
    //endregion

    //region Genres & Tags
    private fun getNeovelGenres() {
        try {

            val request = GET(baseUrl + "V1/genres", headers)
            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: return

            val jsonArray = JsonParser.parseString(jsonString)?.asJsonArray
            neovelGenres = HashMap()
            jsonArray?.forEach {
                val genreObject = it.asJsonObject
                neovelGenres!![genreObject["id"].asInt] = genreObject["en"].asString
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getNeovelTags() {
        try {

            val request = GET(baseUrl + "V1/tags", headers)
            val response = client.newCall(request).execute()
            val jsonString = response.body?.string() ?: return

            val jsonArray = JsonParser.parseString(jsonString)?.asJsonArray
            neovelTags = HashMap()
            jsonArray?.forEach {
                val tagObject = it.asJsonObject
                neovelTags!![tagObject["id"].asInt] = tagObject["en"].asString
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    //endregion

    //region stubs
    override fun popularNovelsRequest(page: Int): Request = throw Exception(MISSING_IMPLEMENTATION)
    override fun popularNovelsParse(response: Response): NovelsPage = throw Exception(MISSING_IMPLEMENTATION)
    override fun latestUpdatesRequest(page: Int): Request = throw Exception(MISSING_IMPLEMENTATION)
    override fun latestUpdatesParse(response: Response): NovelsPage = throw Exception(MISSING_IMPLEMENTATION)
    //endregion

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.193 Safari/537.36"

        // Below is to cache Neovel Genres & Tags
        private var neovelGenres: HashMap<Int, String>? = null
        private var neovelTags: HashMap<Int, String>? = null
    }
}