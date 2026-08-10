package com.lsd.wififrankenstein.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import java.io.File

data class SqliteFileInfo(
    val file: File,
    val dbType: DbType,
    val name: String,
    val tableNames: List<String> = emptyList()
)

object DbSelectionDialogHelper {

    fun detectDbType(file: File): DbType {
        return try {
            val db =
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val tables = db.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'",
                    null
                )
                val tableNames = mutableListOf<String>()
                while (tables.moveToNext()) {
                    tableNames.add(tables.getString(0))
                }
                tables.close()

                val is3WiFi = tableNames.any { name ->
                    db.rawQuery("PRAGMA table_info('$name')", null).use { cols ->
                        val columns = mutableListOf<String>()
                        while (cols.moveToNext()) columns.add(cols.getString(1))
                        columns.any { it.equals("BSSID", ignoreCase = true) } &&
                                columns.any { it.equals("ESSID", ignoreCase = true) } &&
                                columns.any { it.equals("WiFiKey", ignoreCase = true) }
                    }
                }

                if (is3WiFi) DbType.SQLITE_FILE_P3WIFI else DbType.SQLITE_FILE_CUSTOM
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            DbType.SQLITE_FILE_CUSTOM
        }
    }

    fun showDbSelectionDialog(
        context: Context,
        files: List<SqliteFileInfo>,
        onSelected: (SqliteFileInfo) -> Unit,
        onCancel: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val primaryColor = resolveColor(context, android.R.attr.colorPrimary)
        val onSurfaceColor =
            resolveColor(context, com.google.android.material.R.attr.colorOnSurface)

        val rootLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(context, 12)
                bottomMargin = dp(context, 12)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.text_secondary))
            rootLayout.addView(this)
        }

        val cardView = CardView(context).apply {
            radius = dp(context, 16).toFloat()
            cardElevation = dp(context, 8).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(context, 16), 0, dp(context, 16), dp(context, 16))
            layoutParams = lp
        }

        val container = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 8), 0, dp(context, 4))
        }

        container.addView(TextView(context).apply {
            text = context.getString(R.string.select_database)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onSurfaceColor)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        })

        View(context).apply {
            layoutParams =
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    setMargins(dp(context, 16), 0, dp(context, 16), 0)
                }
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
            container.addView(this)
        }

        val radioButtons = mutableListOf<MaterialRadioButton>()

        files.forEachIndexed { index, info ->
            val radioButton = MaterialRadioButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dp(context, 12), 0)
                }
                isChecked = index == 0
            }
            radioButtons.add(radioButton)

            val textLayout = LinearLayout(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            textLayout.addView(TextView(context).apply {
                text = info.name
                textSize = 14f
                setTextColor(onSurfaceColor)
            })

            val typeLabel = if (info.dbType == DbType.SQLITE_FILE_P3WIFI) "3WiFi" else "Custom"
            textLayout.addView(TextView(context).apply {
                text = typeLabel
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })

            val row = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 48)
                setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
                val typedValue = TypedValue()
                context.theme.resolveAttribute(
                    android.R.attr.selectableItemBackground,
                    typedValue,
                    true
                )
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                addView(radioButton)
                addView(textLayout)
                setOnClickListener {
                    radioButtons.forEach { it.isChecked = false }
                    radioButton.isChecked = true
                }
            }
            container.addView(row)

            if (index < files.size - 1) {
                View(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            setMargins(dp(context, 16), 0, dp(context, 16), 0)
                        }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider_color))
                    container.addView(this)
                }
            }
        }

        val buttonRow = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 8))
        }

        buttonRow.addView(MaterialButton(context).apply {
            text = context.getString(R.string.cancel)
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(context, 4), 0)
                }
            isAllCaps = false
            setOnClickListener { dialog.dismiss(); onCancel() }
        })

        buttonRow.addView(MaterialButton(context).apply {
            text = context.getString(R.string.add)
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(context, 4), 0, 0, 0)
                }
            isAllCaps = false
            setOnClickListener {
                val selected = radioButtons.indexOfFirst { it.isChecked }
                if (selected >= 0) {
                    dialog.dismiss()
                    onSelected(files[selected])
                }
            }
        })

        container.addView(buttonRow)
        cardView.addView(container)
        rootLayout.addView(cardView)
        dialog.setContentView(rootLayout)
        dialog.show()
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
