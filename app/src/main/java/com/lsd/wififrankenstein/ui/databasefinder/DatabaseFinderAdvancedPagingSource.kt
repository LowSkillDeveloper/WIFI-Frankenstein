package com.lsd.wififrankenstein.ui.databasefinder

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.util.Log

class DatabaseFinderAdvancedPagingSource(
    private val context: Context,
    private val advancedQuery: AdvancedSearchQuery,
    private val dbList: List<DbItem>,
    private val selectedSources: Set<String>
) : PagingSource<Int, SearchResult>() {

    companion object {
        private const val TAG = "DatabaseFinderAdvanced"
    }

    private val pageSize = 10
    private val paginationHelper by lazy {
        AdvancedPaginationHelper(context, advancedQuery, dbList, selectedSources)
    }

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

            Log.d(TAG, "Loaded ${results.size} results in ${timeTaken}ms")

            return LoadResult.Page(
                data = results,
                prevKey = if (position > 0) position - 1 else null,
                nextKey = if (results.size >= pageSize) position + 1 else null
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