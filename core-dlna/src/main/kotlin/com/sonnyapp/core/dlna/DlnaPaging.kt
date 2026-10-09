package com.sonnyapp.core.dlna

/**
 * Browse 翻页。
 *
 * 索尼相机的媒体服务器**把单次 Browse 的返回量硬性截断在 50 项**：
 * 请求 500 项也只回 50 项，但 TotalMatches 报的是真实总数。
 * 因此一个日期文件夹只要超过 50 张，不翻页就会静默丢图 ——
 * 界面上只会显示前 50 张，且没有任何错误提示。
 *
 * 本类只做「把一个容器取全」这一件事，与网络层解耦：
 * 调用方传入 fetch 回调，因此可以用假服务器做单元测试。
 */
object DlnaPaging {

    /**
     * 单次请求的量。
     *
     * 这只是「希望要多少」：服务器可以少给。实测索尼一律回 50，
     * 所以真正决定翻页次数的是服务器返回的 NumberReturned，而不是这个值。
     */
    const val REQUEST_COUNT = 200

    /** 单个容器的翻页上限，防止服务器异常时死循环。 */
    const val MAX_PAGES = 500

    /**
     * 反复 Browse 直到取全。
     *
     * 推进游标时以服务器自报的 NumberReturned 为准（它是权威）；
     * 该字段缺失时才退回实际解析出的条目数。
     *
     * @param fetch (startingIndex, requestedCount) -> 单页结果
     */
    fun browseAll(
        requestCount: Int = REQUEST_COUNT,
        maxPages: Int = MAX_PAGES,
        fetch: (Int, Int) -> DidlResult,
    ): DidlResult {
        val items = ArrayList<DlnaItem>()
        var startIndex = 0
        var totalMatches = 0
        var pages = 0
        while (pages < maxPages) {
            val page = fetch(startIndex, requestCount)
            pages++
            if (page.totalMatches > 0) totalMatches = page.totalMatches
            items.addAll(page.items)

            val advance = if (page.numberReturned > 0) page.numberReturned else page.items.size
            if (advance <= 0) break
            startIndex += advance
            // 只有服务器报了总数才据此收尾；报 0 时靠「下一页为空」结束
            if (totalMatches > 0 && startIndex >= totalMatches) break
        }
        return DidlResult(
            items = items,
            numberReturned = items.size,
            totalMatches = if (totalMatches > 0) totalMatches else items.size,
        )
    }
}
