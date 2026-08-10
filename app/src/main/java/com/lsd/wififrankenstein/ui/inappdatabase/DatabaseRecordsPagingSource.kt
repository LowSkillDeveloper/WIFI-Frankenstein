package com.lsd.wififrankenstein.ui.inappdatabase

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.WifiNetwork

class DatabaseRecordsPagingSource(
    private val dbHelper: LocalAppDbHelper,
    private val searchParams: InAppDatabaseViewModel.SearchParams
) : PagingSource<Long, WifiNetwork>() {

    override val keyReuseSupported: Boolean = true

    override suspend fun load(params: LoadParams<Long>): LoadResult<Long, WifiNetwork> {
        val position = params.key ?: 0L

        return try {
            val records = if (searchParams.query.isNotEmpty()) {
                val searchFields = buildSet {
                    if (searchParams.filterName) add("name")
                    if (searchParams.filterMac) add("mac")
                    if (searchParams.filterPassword) add("password")
                    if (searchParams.filterWps) add("wps")
                }
                dbHelper.searchRecordsWithFiltersPaginated(
                    searchParams.query,
                    searchFields,
                    position.toInt() * params.loadSize,
                    params.loadSize
                )
            } else {
                dbHelper.getRecords(position, params.loadSize)
            }

            val nextKey = if (records.isEmpty()) null else {
                if (searchParams.query.isNotEmpty()) {
                    null
                } else {
                    records.lastOrNull()?.id?.let { it + 1 }
                }
            }

            LoadResult.Page(
                data = records,
                prevKey = if (position == 0L) null else (position - params.loadSize).coerceAtLeast(0),
                nextKey = nextKey
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Long, WifiNetwork>): Long? {
        return state.anchorPosition?.let { anchorPosition ->
            val anchorPage = state.closestPageToPosition(anchorPosition)
            anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
        }
    }
}