package com.lsd.wififrankenstein.ui.dbsetup

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lsd.wififrankenstein.R

/**
 * Adapter for the "Active downloads" card in the Database Setup screen.
 * Renders the background download queue with per-item status, progress and
 * context actions (retry / resume / setup / cancel).
 */
class PendingDownloadsAdapter(
    private val onRetry: (PendingDownload) -> Unit,
    private val onCancel: (PendingDownload) -> Unit,
    private val onSetup: (PendingDownload) -> Unit
) : RecyclerView.Adapter<PendingDownloadsAdapter.PendingViewHolder>() {

    private val items = mutableListOf<PendingDownload>()

    fun submit(newItems: List<PendingDownload>) {
        if (newItems == items) return
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PendingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_pending_download, parent, false)
        return PendingViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PendingViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class PendingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val nameText = itemView.findViewById<TextView>(R.id.textViewPendingName)
        private val statusText = itemView.findViewById<TextView>(R.id.textViewPendingStatus)
        private val progressBar =
            itemView.findViewById<ProgressBar>(R.id.progressBarPendingDownload)
        private val actionButton = itemView.findViewById<MaterialButton>(R.id.buttonPendingAction)
        private val cancelButton = itemView.findViewById<ImageButton>(R.id.buttonPendingCancel)

        fun bind(item: PendingDownload) {
            val context = itemView.context
            nameText.text = item.name
            nameText.isSelected = true

            when (item.status) {
                DownloadStatus.QUEUED -> {
                    statusText.text = context.getString(R.string.db_dl_status_queued)
                    showProgress(indeterminate = true, percent = 0)
                    actionButton.visibility = View.GONE
                }

                DownloadStatus.RUNNING -> {
                    statusText.text =
                        context.getString(R.string.db_dl_status_downloading, item.progressPercent)
                    showProgress(indeterminate = false, percent = item.progressPercent)
                    actionButton.visibility = View.GONE
                }

                DownloadStatus.EXTRACTING -> {
                    statusText.text =
                        context.getString(R.string.db_dl_status_extracting, item.progressPercent)
                    showProgress(indeterminate = false, percent = item.progressPercent)
                    actionButton.visibility = View.GONE
                }

                DownloadStatus.WAITING_QUOTA -> {
                    statusText.text = context.getString(R.string.db_dl_status_waiting_quota)
                    showProgress(indeterminate = true, percent = 0)
                    setAction(R.string.db_dl_action_resume) { onRetry(item) }
                }

                DownloadStatus.FAILED -> {
                    statusText.text =
                        context.getString(R.string.db_dl_status_failed, item.error ?: "")
                    showProgress(indeterminate = false, percent = 0)
                    setAction(R.string.retry) { onRetry(item) }
                }

                DownloadStatus.NEEDS_SETUP -> {
                    statusText.text = context.getString(R.string.db_dl_status_needs_setup)
                    progressBar.visibility = View.GONE
                    setAction(R.string.db_dl_action_setup) { onSetup(item) }
                }

                DownloadStatus.DONE -> {
                    statusText.text = context.getString(R.string.download_completed)
                    progressBar.visibility = View.GONE
                    actionButton.visibility = View.GONE
                }
            }

            if (item.status == DownloadStatus.NEEDS_SETUP || item.status == DownloadStatus.DONE) {
                cancelButton.visibility = View.INVISIBLE
                cancelButton.isEnabled = false
            } else {
                cancelButton.visibility = View.VISIBLE
                cancelButton.isEnabled = true
                cancelButton.setOnClickListener { onCancel(item) }
            }
        }

        private fun showProgress(indeterminate: Boolean, percent: Int) {
            progressBar.visibility = View.VISIBLE
            if (progressBar.isIndeterminate != indeterminate) {
                progressBar.isIndeterminate = indeterminate
            }
            if (!indeterminate) {
                progressBar.progress = percent.coerceIn(0, 100)
            }
        }

        private fun setAction(textRes: Int, click: () -> Unit) {
            actionButton.visibility = View.VISIBLE
            actionButton.setText(textRes)
            actionButton.setOnClickListener { click() }
        }
    }
}
