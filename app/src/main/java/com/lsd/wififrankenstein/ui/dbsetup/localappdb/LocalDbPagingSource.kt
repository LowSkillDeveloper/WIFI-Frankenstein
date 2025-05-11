package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import androidx.paging.PagingSource
import androidx.paging.PagingState

class LocalDbPagingSource(private val dbHelper: LocalAppDbHelper) : PagingSource<Long, WifiNetwork>() {

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, WifiNetwork> {
        val position = params.key ?: 0
        return try {
            val records = dbHelper.getRecords(position, params.loadSize)
            LoadResult.Page(
                data = records,
                prevKey = if (position == 0L) null else position - params.loadSize,
                nextKey = if (records.isEmpty()) null else records.last().id
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, WifiNetwork>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey ?: anchorPage?.nextKey
        }
    }
}