package com.caniko.cenix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SearchControllerTest {
    private fun controller(
        delivered: MutableList<Triple<Int, Int, List<LaunchableApp>>>,
        latch: CountDownLatch = CountDownLatch(0),
    ) = SearchController(
        filterOf = { AppFilter { apps, _, _ -> apps } },
        onEmergency = { },
        onResult = { publication, token, matches ->
            delivered.add(Triple(publication, token, matches))
            latch.countDown()
        },
    )

    @Test
    fun currentSearchDelivers() {
        val delivered = mutableListOf<Triple<Int, Int, List<LaunchableApp>>>()
        val latch = CountDownLatch(1)
        val search = controller(delivered, latch)
        search.submit(emptyList(), "a", emptySet(), delayMs = 1)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, delivered.size)
        search.close()
    }

    @Test
    fun deliveredTokenTracksInvalidation() {
        val delivered = mutableListOf<Triple<Int, Int, List<LaunchableApp>>>()
        val latch = CountDownLatch(1)
        val search = controller(delivered, latch)
        search.submit(emptyList(), "a", emptySet(), delayMs = 1)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val (publication, token, _) = delivered.single()
        assertTrue(search.isCurrent(publication, token))
        search.invalidate()
        assertTrue(!search.isCurrent(publication, token))
        search.close()
    }

    @Test
    fun supersededQueryNeverDeliversAfterNewerSubmit() {
        val delivered = mutableListOf<Triple<Int, Int, List<LaunchableApp>>>()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val search = SearchController(
            filterOf = { AppFilter { apps, _, _ ->
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                apps
            } },
            onEmergency = { },
            onResult = { publication, token, matches -> delivered.add(Triple(publication, token, matches)) },
        )
        search.submit(emptyList(), "a", emptySet(), delayMs = 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // A newer query in the same generation supersedes the in-flight one
        // (first submit carries token 1, second carries token 2).
        search.submit(emptyList(), "ab", emptySet(), delayMs = 1)
        gate.countDown()
        Thread.sleep(600)
        assertTrue(delivered.none { it.second == 1 })
        assertTrue(delivered.size <= 1)
        search.close()
    }

    @Test
    fun invalidatedSearchNeverDelivers() {
        val delivered = mutableListOf<Triple<Int, Int, List<LaunchableApp>>>()
        val search = controller(delivered)
        search.submit(emptyList(), "private", emptySet(), delayMs = 50)
        search.invalidate()
        Thread.sleep(400)
        assertTrue(delivered.isEmpty())
        search.close()
    }

    @Test
    fun invalidatedGenerationDropsAlreadyDispatchedResult() {
        val delivered = mutableListOf<Triple<Int, Int, List<LaunchableApp>>>()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val search = SearchController(
            filterOf = { AppFilter { apps, _, _ ->
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                apps
            } },
            onEmergency = { },
            onResult = { publication, token, matches -> delivered.add(Triple(publication, token, matches)) },
        )
        search.submit(emptyList(), "private", emptySet(), delayMs = 1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // Invalidate while the filter is still running: the stale result must not publish.
        search.invalidate()
        gate.countDown()
        Thread.sleep(400)
        assertTrue(delivered.isEmpty())
        search.close()
    }
}
