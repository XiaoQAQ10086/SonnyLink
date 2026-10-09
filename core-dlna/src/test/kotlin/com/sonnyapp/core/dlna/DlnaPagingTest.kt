package com.sonnyapp.core.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页行为锁定。
 *
 * 背景：索尼相机把单次 Browse 的返回量截断在 50 项（实测 a7R II：
 * 一个日期文件夹 TotalMatches=220，请求 500 却只回 50）。
 * 不翻页的后果是超过 50 张的文件夹静默少图，用户只会看到前 50 张。
 */
class DlnaPagingTest {

    private fun item(i: Int): DlnaItem =
        DlnaItem(id = "p" + i, title = "DSC" + i.toString().padStart(5, '0') + ".JPG")

    /** 造一页：从 all 的第 start 项开始，最多给 cap 项（模拟相机的截断行为）。 */
    private fun page(all: List<DlnaItem>, start: Int, cap: Int): DidlResult {
        val slice = all.drop(start).take(cap)
        return DidlResult(items = slice, numberReturned = slice.size, totalMatches = all.size)
    }

    @Test
    fun readsAllItemsWhenServerCapsEachResponse() {
        val all = (1..220).map { item(it) }
        var calls = 0

        val r = DlnaPaging.browseAll { start, _ ->
            calls++
            page(all, start, cap = 50)
        }

        assertEquals("应取全 220 项", 220, r.items.size)
        assertEquals("TotalMatches 应保留服务器上报值", 220, r.totalMatches)
        assertEquals("50 项一页应为 5 页", 5, calls)
        assertEquals("顺序不能乱", "DSC00001.JPG", r.items[0].title)
        assertEquals("DSC00220.JPG", r.items[219].title)
    }

    @Test
    fun requestedCountIsOnlyAHint() {
        val all = (1..92).map { item(it) }
        val asked = ArrayList<Int>()

        val r = DlnaPaging.browseAll { start, count ->
            asked.add(count)
            page(all, start, cap = 50)
        }

        assertEquals("应取全 92 项", 92, r.items.size)
        assertEquals("即使请求量很大，也要按服务器实际返回量推进", 2, asked.size)
        assertTrue("请求量必须大于等于服务器上限", asked.all { it >= 50 })
    }

    @Test
    fun stopsWhenServerDoesNotReportTotal() {
        val all = (1..70).map { item(it) }
        var calls = 0

        val r = DlnaPaging.browseAll { start, _ ->
            calls++
            val slice = all.drop(start).take(50)
            DidlResult(items = slice, numberReturned = slice.size, totalMatches = 0)
        }

        assertEquals("没有总数时也要取全", 70, r.items.size)
        assertEquals("总数缺失时应回填实际条数", 70, r.totalMatches)
        assertEquals("末页为空即结束，共 3 次（50+20+0）", 3, calls)
    }

    @Test
    fun stopsOnEmptyFirstPage() {
        var calls = 0
        val r = DlnaPaging.browseAll { _, _ ->
            calls++
            DidlResult()
        }
        assertEquals(0, r.items.size)
        assertEquals("空容器只问一次", 1, calls)
    }

    @Test
    fun singlePageNeedsSingleRequest() {
        val all = (1..7).map { item(it) }
        var calls = 0
        val r = DlnaPaging.browseAll { start, _ ->
            calls++
            page(all, start, cap = 50)
        }
        assertEquals(7, r.items.size)
        assertEquals("不足一页时不应多发请求", 1, calls)
    }

    @Test
    fun neverLoopsForeverWhenServerRepeatsItself() {
        var calls = 0
        val r = DlnaPaging.browseAll(maxPages = 8) { _, _ ->
            calls++
            // 服务器永远回同一页且不报总数 —— 最坏情况
            DidlResult(items = listOf(item(1)), numberReturned = 1, totalMatches = 0)
        }
        assertEquals("必须被 MAX_PAGES 兜住", 8, calls)
        assertEquals(8, r.items.size)
    }

    @Test
    fun advancesByServerReportedCountNotParsedCount() {
        // 服务器说给了 50 项，但只有 3 项能解析出来（例如遇到不认识的条目格式）。
        // 游标必须按 NumberReturned 推进，否则会反复读同一段而陷入死循环。
        val all = (1..120).map { item(it) }
        val starts = ArrayList<Int>()
        val r = DlnaPaging.browseAll { start, _ ->
            starts.add(start)
            val slice = all.drop(start).take(50)
            DidlResult(items = slice.take(3), numberReturned = 50, totalMatches = all.size)
        }
        assertEquals("游标应按 NumberReturned 推进", listOf(0, 50, 100), starts)
        assertEquals("解析出的条目照常累计", 9, r.items.size)
    }
}
