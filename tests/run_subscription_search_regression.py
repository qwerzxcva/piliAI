#!/usr/bin/env python3
"""Exercise production subscription search with controlled coroutine responses.

No network requests, Room writes, Android lifecycle or playback are verified.
"""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / 'android/app/src/main/kotlin/com/example/piliai/ui/subscribe/SubscribeViewModel.kt').read_text(encoding='utf-8')


def method(name):
    start = source.index('    fun ' + name + '(')
    end = source.index('\n    }', start) + len('\n    }')
    return source[start:end].replace('com.example.piliai.AppContext.get()', 'TestContext').replace('com.example.piliai.R.string.', 'Messages.')


program = r'''
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

data class Source(val id: Long, val name: String, val url: String, val enabled: Boolean)
data class SubscribeItemEntity(
    val id: Long, val sourceId: Long, val title: String, val cover: String, val link: String,
    val desc: String, val episode: String, val sourceName: String
)

// 审核256：生产代码把搜索结果从 SubscribeItemEntity 换成 SearchResultItem
// （带 kind，点击分派不再靠猜）。stub 跟着改。
enum class SearchResultKind { RSS_ITEM, WORK }
data class SearchResultItem(
    val sourceId: Long, val sourceName: String, val title: String, val cover: String,
    val link: String, val desc: String, val episode: String, val kind: SearchResultKind
)
data class ParsedItem(
    val title: String, val cover: String = "", val link: String = "https://fixture.test/video.mp4",
    val desc: String = "", val episode: String = ""
)
data class ParsedSource(val items: List<ParsedItem>)
data class UiState(
    val sources: List<Source>, val isRefreshing: Boolean = false,
    val errorMessage: String? = null, val infoMessage: String? = null,
    val isSearching: Boolean = false
)
object Messages { const val subscribe_source_unavailable = 1 }
object TestContext { fun getString(id: Int): String = id.toString() }
object SubscribeParser {
    val responses = mutableMapOf<String, CompletableDeferred<Result<ParsedSource>>>()
    val calls = mutableListOf<String>()
    suspend fun searchAnimekoSource(url: String, keyword: String, factoryId: String): Result<ParsedSource> {
        check(url == "https://fixture.test/feed" && factoryId == "rss")
        calls.add(keyword)
        return responses.getValue(keyword).await()
    }
}
class SearchHarness {
    private val owner = SupervisorJob()
    val viewModelScope = CoroutineScope(owner + Dispatchers.Unconfined)
    val original = Source(7L, "Imported source", "https://fixture.test/config", true)
    val _state = MutableStateFlow(UiState(listOf(original)))
    val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val _episodes = MutableStateFlow<List<String>>(emptyList())
    val _currentSubject = MutableStateFlow<String?>(null)
    var refreshJob: Job? = null
    var searchJob: Job? = null
    var browseJob: Job? = null
    var browseGeneration = 0L
    var activeSourceUrl = ""
    var activeSourceName = ""
    fun close() = owner.cancel()
''' + method('searchInSource') + '\n' + method('clearSearch') + r'''
}
fun response(key: String): CompletableDeferred<Result<ParsedSource>> =
    CompletableDeferred<Result<ParsedSource>>().also { SubscribeParser.responses[key] = it }
fun search(h: SearchHarness, key: String, id: Long = 7L) =
    h.searchInSource("https://fixture.test/feed", key, "rss", id)
fun success(title: String) = Result.success(ParsedSource(listOf(ParsedItem(title))))
fun main() = runBlocking {
    val h = SearchHarness()
    try {
        val first = response("old")
        search(h, "old")
        val oldJob = h.searchJob!!
        val newest = response("new")
        search(h, "new")
        check(oldJob.isCancelled)
        newest.complete(success("Newest"))
        h.searchJob!!.join()
        first.complete(success("Obsolete"))
        yield()
        val item = h._searchResults.value.single()
        check(item.title == "Newest" && item.sourceId == 7L && item.sourceName == "Imported source")
        // 审核256：rss 源的搜索结果必须是 RSS_ITEM（点开按 link 形态分派）
        check(item.kind == SearchResultKind.RSS_ITEM)
        check(h._state.value.isSearching && !h._state.value.isRefreshing)
        println("PASS production search: newest response wins and retains source identity")

        val pending = response("clear")
        search(h, "clear")
        val clearedJob = h.searchJob!!
        h.clearSearch()
        pending.complete(success("Must not reappear"))
        yield()
        check(clearedJob.isCancelled && h._searchResults.value.isEmpty())
        check(!h._state.value.isSearching && !h._state.value.isRefreshing)
        println("PASS production clearSearch: cancellation prevents stale results")

        for (change in listOf("disabled", "removed", "changed-url")) {
            h._state.value = h._state.value.copy(sources = listOf(h.original))
            val deferred = response(change)
            search(h, change)
            h._state.value = h._state.value.copy(sources = when (change) {
                "disabled" -> listOf(h.original.copy(enabled = false))
                "removed" -> emptyList()
                else -> listOf(h.original.copy(url = "https://fixture.test/replaced"))
            })
            deferred.complete(success("Must not publish"))
            h.searchJob!!.join()
            check(h._searchResults.value.isEmpty())
            check(h._state.value.errorMessage != null && !h._state.value.isRefreshing)
        }
        val callsBefore = SubscribeParser.calls.size
        search(h, "unknown", 99L)
        check(SubscribeParser.calls.size == callsBefore)
        println("PASS production search: unavailable and changed subscriptions cannot publish results")

        h._state.value = h._state.value.copy(sources = listOf(h.original))
        val failed = response("failure")
        search(h, "failure")
        failed.complete(Result.failure(IllegalStateException("Controlled failure")))
        h.searchJob!!.join()
        check(h._state.value.errorMessage == "Controlled failure" && !h._state.value.isRefreshing)
        val empty = response("empty")
        search(h, "empty")
        empty.complete(Result.success(ParsedSource(emptyList())))
        h.searchJob!!.join()
        check(h._searchResults.value.isEmpty() && h._state.value.isSearching)
        check(h._state.value.infoMessage != null && !h._state.value.isRefreshing)
        println("PASS production search: failures remain visible and empty searches remain active")
    } finally { h.close() }
}
'''

configured = os.environ.get('KOTLIN_LIB')
candidates = sorted(Path.home().glob('.gradle/wrapper/dists/gradle-*/*/gradle-*/lib'))
candidates = [p for p in candidates if list(p.glob('kotlin-compiler-embeddable-*.jar'))]
if not configured and not candidates:
    raise RuntimeError('Set KOTLIN_LIB to an installed compiler library directory')
lib = Path(configured) if configured else candidates[-1]
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
coroutines = sorted(lib.glob('kotlinx-coroutines-core-jvm-*.jar'))
if not coroutines:
    coroutines = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm').glob('*/*/*.jar'))
if not coroutines:
    raise RuntimeError('Coroutine JVM library is not cached')
classpath = os.pathsep.join(map(str, [stdlib, coroutines[-1]]))
with tempfile.TemporaryDirectory(prefix='piliai-subscription-search-') as directory:
    temporary = Path(directory)
    test = temporary / 'SubscriptionSearchRegression.kt'
    test.write_text(program, encoding='utf-8')
    classes = temporary / 'classes'
    subprocess.run([
        'java', '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-classpath', classpath,
        '-d', str(classes), str(test)
    ], check=True, timeout=60)
    subprocess.run([
        'java', '-cp', str(classes) + os.pathsep + classpath,
        'SubscriptionSearchRegressionKt'
    ], check=True, timeout=20)
