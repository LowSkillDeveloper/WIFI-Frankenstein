package com.lsd.wififrankenstein.ui.databasefinder

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lsd.wififrankenstein.util.Log

class DatabaseFinderAdvancedPagingSource(
    private val advancedQuery: AdvancedSearchQuery,
    private val paginationHelper: AdvancedPaginationHelper,
) : PagingSource<Int, SearchResult>() {

    companion object {
        private const val TAG = "DatabaseFinderAdvanced"
    }

    private val pageSize = 10

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SearchResult> {
        val startTime = System.currentTimeMillis()
        val position = params.key ?: 0
        val offset = position * pageSize

        Log.d(TAG, "Loading page: position=$position, offset=$offset, pageSize=$pageSize")

        try {
            if (!advancedQuery.hasContent()) {
                Log.d(TAG, "Empty advanced query, returning empty results")
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null
                )
            }

            val results = paginationHelper.loadPage(offset, pageSize)
            val timeTaken = System.currentTimeMillis() - startTime

            Log.d(TAG, "Loaded ${results.items.size} results in ${timeTaken}ms")

            results.items.take(5).forEachIndexed { index, result ->
                Log.d(
                    TAG,
                    "Result ${index + 1}: ssid=${result.ssid}, bssid=${result.getFormattedBssid()}, password=${result.password}, wpsPin=${result.wpsPin}, source=${result.source}, lat=${result.latitude}, lon=${result.longitude}"
                )
            }

            return LoadResult.Page(
                data = results.items,
                prevKey = if (position > 0) position - 1 else null,
                nextKey = if (results.hasMore) position + 1 else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in load(): ${e.message}", e)
            return LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, SearchResult>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}