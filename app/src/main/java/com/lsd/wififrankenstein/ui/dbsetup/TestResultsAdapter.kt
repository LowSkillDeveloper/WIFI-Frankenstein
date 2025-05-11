package com.lsd.wififrankenstein.ui.dbsetup

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lsd.wififrankenstein.R
import org.json.JSONException
import org.json.JSONObject


class TestResultsAdapter(private val results: List<Pair<String, Pair<Boolean, String>>>) :
    RecyclerView.Adapter<TestResultsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = view.findViewById(R.id.textViewTestTitle)
        val urlTextView: TextView = view.findViewById(R.id.textViewTestUrl)
        val resultTextView: TextView = view.findViewById(R.id.textViewTestResult)
        val additionalInfoTextView: TextView = view.findViewById(R.id.textViewAdditionalInfo)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_test_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (url, result) = results[position]
        val (_, message) = result

        val context = holder.itemView.context
        val testTitle = context.getString(R.string.test_title, url.substringAfterLast("/").substringBefore("?"))
        holder.titleTextView.text = testTitle
        holder.urlTextView.text = url

        val responseCode = message.substringAfter("Response Code: ").substringBefore("\n")
        val response = message.substringAfter("Response: ")
        val elapsedTime = message.substringAfterLast("Time: ").substringBefore(" ms").toLong()

        val resultMessage = SpannableStringBuilder()
        val additionalInfo = SpannableStringBuilder()

        if (responseCode == "200") {
            try {
                val responseJson = JSONObject(response)
                val result = responseJson.getBoolean("result")
                if (result) {
                    resultMessage.append(context.getString(R.string.api_availability_success).substringBefore("Success"))
                    val successSpan = SpannableString("Success")
                    successSpan.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.success_green)),
                        0,
                        successSpan.length,
                        0
                    )
                    resultMessage.append(successSpan)
                    resultMessage.append("\n")

                    resultMessage.append(context.getString(R.string.api_request_test_success).substringBefore("Success"))
                    val successTestSpan = SpannableString("Success")
                    successTestSpan.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.success_green)),
                        0,
                        successTestSpan.length,
                        0
                    )
                    resultMessage.append(successTestSpan)
                    resultMessage.append("\n")

                    resultMessage.append(context.getString(R.string.time, elapsedTime))
                } else {
                    val error = responseJson.optString("error")
                    resultMessage.append(context.getString(R.string.api_availability_success).substringBefore("Success"))
                    val successSpan = SpannableString("Success")
                    successSpan.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.success_green)),
                        0,
                        successSpan.length,
                        0
                    )
                    resultMessage.append(successSpan)
                    resultMessage.append("\n")

                    resultMessage.append(context.getString(R.string.api_request_test_error).substringBefore("Error"))
                    val errorSpan = SpannableString("Error")
                    errorSpan.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.error_red)),
                        0,
                        errorSpan.length,
                        0
                    )
                    resultMessage.append(errorSpan)
                    resultMessage.append("\n")

                    resultMessage.append(context.getString(R.string.time, elapsedTime))

                    val apiError = "API error: $error"
                    additionalInfo.append(apiError)
                    additionalInfo.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(context, R.color.error_red)),
                        0,
                        additionalInfo.length,
                        0
                    )
                }
            } catch (_: JSONException) {
                resultMessage.append("JSON parsing error")
            }
        } else {
            val errorAvailabilityMessage = context.getString(R.string.api_availability_http_error, responseCode)
            val normalPart = errorAvailabilityMessage.substringBefore("HTTP ERROR")
            val errorPart = "HTTP ERROR $responseCode"

            resultMessage.append(normalPart)
            resultMessage.append(errorPart)
            resultMessage.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.error_red)),
                resultMessage.length - errorPart.length,
                resultMessage.length,
                0
            )
            resultMessage.append("\n")
            resultMessage.append(context.getString(R.string.time, elapsedTime))
        }

        holder.resultTextView.text = resultMessage
        holder.additionalInfoTextView.text = additionalInfo

        if (position == itemCount - 1) {
            holder.itemView.setPadding(
                holder.itemView.paddingLeft,
                holder.itemView.paddingTop,
                holder.itemView.paddingRight,
                holder.itemView.paddingBottom + 100
            )
        } else {
            holder.itemView.setPadding(
                holder.itemView.paddingLeft,
                holder.itemView.paddingTop,
                holder.itemView.paddingRight,
                holder.itemView.paddingBottom
            )
        }
    }

    override fun getItemCount() = results.size
}