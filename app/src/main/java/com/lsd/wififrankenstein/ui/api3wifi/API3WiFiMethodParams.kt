package com.lsd.wififrankenstein.ui.api3wifi

import android.content.Context
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R

sealed class API3WiFiMethodParams {
    abstract fun createView(context: Context, container: ViewGroup): View
    abstract fun getRequest(key: String): API3WiFiRequest?
    abstract fun isValid(): Boolean
    abstract fun validate()
    abstract fun clear()

    class ApiQuery : API3WiFiMethodParams() {
        private lateinit var queryTypeTabLayout: TabLayout
        private lateinit var bssidInput: TextInputEditText
        private lateinit var bssidInputLayout: TextInputLayout
        private lateinit var essidInput: TextInputEditText
        private lateinit var essidInputLayout: TextInputLayout
        private lateinit var pairsList: RecyclerView
        private lateinit var caseSensitiveSwitch: SwitchMaterial
        private lateinit var addButton: MaterialButton

        val bssidList: List<String> get() = _bssidList
        val essidList: List<String> get() = _essidList
        val exactPairs: List<Pair<String, String>> get() = _exactPairs

        private val _bssidList = mutableListOf<String>()
        private val _essidList = mutableListOf<String>()
        private val _exactPairs = mutableListOf<Pair<String, String>>()


        enum class QueryType {
            BSSID_ONLY, ESSID_ONLY, EXACT_MATCH
        }

        private var _currentQueryType = QueryType.BSSID_ONLY
        val currentQueryType: QueryType get() = _currentQueryType
        private var onDataChangedListener: (() -> Unit)? = null

        fun setOnDataChangedListener(listener: () -> Unit) {
            onDataChangedListener = listener
        }

        override fun createView(context: Context, container: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(
                R.layout.layout_api_query_params,
                container,
                false
            )

            initViews(view)
            setupQueryTypeTabs()
            setupInputFilters()
            setupAddButton()
            setupPairsList()
            updateViewsVisibility()

            return view
        }

        private fun initViews(view: View) {
            queryTypeTabLayout = view.findViewById(R.id.queryTypeTabLayout)
            bssidInput = view.findViewById(R.id.bssidInput)
            bssidInputLayout = view.findViewById(R.id.bssidInputLayout)
            essidInput = view.findViewById(R.id.essidInput)
            essidInputLayout = view.findViewById(R.id.essidInputLayout)
            pairsList = view.findViewById(R.id.pairsList)
            caseSensitiveSwitch = view.findViewById(R.id.caseSensitiveSwitch)
            addButton = view.findViewById(R.id.addButton)
        }

        private fun setupQueryTypeTabs() {
            with(queryTypeTabLayout) {
                addTab(newTab().setText(R.string.query_type_bssid_only))
                addTab(newTab().setText(R.string.query_type_essid_only))
                addTab(newTab().setText(R.string.query_type_exact_match))

                addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab?) {
                        _currentQueryType = when(tab?.position) {
                            0 -> QueryType.BSSID_ONLY
                            1 -> QueryType.ESSID_ONLY
                            2 -> QueryType.EXACT_MATCH
                            else -> QueryType.BSSID_ONLY
                        }
                        updateViewsVisibility()
                        clearInputs()
                        validate()
                        onDataChangedListener?.invoke()
                    }

                    override fun onTabUnselected(tab: TabLayout.Tab?) {}
                    override fun onTabReselected(tab: TabLayout.Tab?) {}
                })
            }
        }

        private fun setupInputFilters() {
            bssidInput.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val newText = dest.toString().substring(0, dstart) + source.toString() + dest.toString().substring(dend)
                if (newText.isEmpty()) return@InputFilter source

                if (!newText.matches(Regex("^([0-9A-Fa-f]{2}:){0,5}[0-9A-Fa-f]{0,2}$"))) {
                    return@InputFilter ""
                }

                if (source.isNotEmpty() && (dstart == 2 || dstart == 5 || dstart == 8 || dstart == 11 || dstart == 14)) {
                    return@InputFilter ":$source"
                }

                source
            })
        }

        private fun setupAddButton() {
            addButton.setOnClickListener {
                when (_currentQueryType) {
                    QueryType.BSSID_ONLY -> addBssid()
                    QueryType.ESSID_ONLY -> addEssid()
                    QueryType.EXACT_MATCH -> addExactPair()
                }
            }
        }

        private fun setupPairsList() {
            pairsList.layoutManager = LinearLayoutManager(pairsList.context)
            pairsList.adapter = PairsAdapter()
        }

        private fun updateViewsVisibility() {
            when (_currentQueryType) {
                QueryType.BSSID_ONLY -> {
                    bssidInputLayout.visibility = View.VISIBLE
                    essidInputLayout.visibility = View.GONE
                    addButton.setText(R.string.add_bssid)
                }
                QueryType.ESSID_ONLY -> {
                    bssidInputLayout.visibility = View.GONE
                    essidInputLayout.visibility = View.VISIBLE
                    addButton.setText(R.string.add_essid)
                }
                QueryType.EXACT_MATCH -> {
                    bssidInputLayout.visibility = View.VISIBLE
                    essidInputLayout.visibility = View.VISIBLE
                    addButton.setText(R.string.add_pair)
                }
            }
        }

        private fun addBssid() {
            val bssid = bssidInput.text.toString().uppercase()
            if (bssid.isNotEmpty()) {
                if (!_bssidList.contains(bssid)) {
                    _bssidList.add(bssid)
                    (pairsList.adapter as PairsAdapter).notifyDataSetChanged()
                }
                bssidInput.text?.clear()
                bssidInputLayout.error = null
            }
            validate()
            onDataChangedListener?.invoke()
        }

        private fun addEssid() {
            val essid = essidInput.text.toString()
            if (essid.isNotEmpty()) {
                if (!_essidList.contains(essid)) {
                    _essidList.add(essid)
                    (pairsList.adapter as PairsAdapter).notifyDataSetChanged()
                }
                essidInput.text?.clear()
                essidInputLayout.error = null
            }
            validate()
            onDataChangedListener?.invoke()
        }

        private fun addExactPair() {
            val bssid = bssidInput.text.toString().uppercase()
            val essid = essidInput.text.toString()

            if (bssid.isEmpty()) {
                bssidInputLayout.error = bssidInputLayout.context.getString(R.string.bssid_required)
                return
            }

            if (essid.isEmpty()) {
                essidInputLayout.error = essidInputLayout.context.getString(R.string.essid_required)
                return
            }

            val pair = Pair(bssid, essid)
            if (!_exactPairs.contains(pair)) {
                _exactPairs.add(pair)
                (pairsList.adapter as PairsAdapter).notifyDataSetChanged()
            }

            bssidInput.text?.clear()
            essidInput.text?.clear()
            bssidInputLayout.error = null
            essidInputLayout.error = null
            validate()
            onDataChangedListener?.invoke()
        }

        private fun clearInputs() {
            bssidInput.text?.clear()
            essidInput.text?.clear()
            bssidInputLayout.error = null
            essidInputLayout.error = null
        }

        override fun getRequest(key: String): API3WiFiRequest? {
            return if (isValid()) {
                API3WiFiRequest.ApiQuery(
                    key = key,
                    bssidList = if (_currentQueryType == QueryType.BSSID_ONLY) _bssidList else null,
                    essidList = if (_currentQueryType == QueryType.ESSID_ONLY) _essidList else null,
                    exactPairs = if (_currentQueryType == QueryType.EXACT_MATCH) _exactPairs else null,
                    sens = caseSensitiveSwitch.isChecked
                )
            } else null
        }

        override fun isValid(): Boolean {
            return when (_currentQueryType) {
                QueryType.BSSID_ONLY -> _bssidList.isNotEmpty()
                QueryType.ESSID_ONLY -> _essidList.isNotEmpty()
                QueryType.EXACT_MATCH -> _exactPairs.isNotEmpty()
            }
        }

        override fun validate() {
            when (_currentQueryType) {
                QueryType.BSSID_ONLY -> {
                    if (_bssidList.isEmpty()) {
                        bssidInputLayout.error = bssidInputLayout.context.getString(R.string.at_least_one_bssid)
                    } else {
                        bssidInputLayout.error = null
                    }
                }
                QueryType.ESSID_ONLY -> {
                    if (_essidList.isEmpty()) {
                        essidInputLayout.error = essidInputLayout.context.getString(R.string.at_least_one_essid)
                    } else {
                        essidInputLayout.error = null
                    }
                }
                QueryType.EXACT_MATCH -> {
                    if (_exactPairs.isEmpty()) {
                        bssidInputLayout.error = bssidInputLayout.context.getString(R.string.at_least_one_pair)
                        essidInputLayout.error = bssidInputLayout.context.getString(R.string.at_least_one_pair)
                    } else {
                        bssidInputLayout.error = null
                        essidInputLayout.error = null
                    }
                }
            }
        }

        override fun clear() {
            _bssidList.clear()
            _essidList.clear()
            _exactPairs.clear()
            clearInputs()
            (pairsList.adapter as? PairsAdapter)?.notifyDataSetChanged()
        }

        private inner class PairsAdapter : RecyclerView.Adapter<PairsAdapter.ViewHolder>() {
            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val bssidText: TextView = view.findViewById(R.id.bssidText)
                val essidText: TextView = view.findViewById(R.id.essidText)
                val removeButton: MaterialButton = view.findViewById(R.id.removeButton)
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.layout_api_query_pair_item, parent, false)
                return ViewHolder(view)
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                when (_currentQueryType) {
                    QueryType.BSSID_ONLY -> {
                        holder.bssidText.text = _bssidList[position]
                        holder.essidText.visibility = View.GONE
                        holder.removeButton.setOnClickListener {
                            _bssidList.removeAt(holder.adapterPosition)
                            notifyDataSetChanged()
                            validate()
                            onDataChangedListener?.invoke()
                        }
                    }
                    QueryType.ESSID_ONLY -> {
                        holder.bssidText.visibility = View.GONE
                        holder.essidText.text = _essidList[position]
                        holder.removeButton.setOnClickListener {
                            _essidList.removeAt(holder.adapterPosition)
                            notifyDataSetChanged()
                            validate()
                            onDataChangedListener?.invoke()
                        }
                    }
                    QueryType.EXACT_MATCH -> {
                        val pair = _exactPairs[position]
                        holder.bssidText.text = pair.first
                        holder.essidText.text = pair.second
                        holder.removeButton.setOnClickListener {
                            _exactPairs.removeAt(holder.adapterPosition)
                            notifyDataSetChanged()
                            validate()
                            onDataChangedListener?.invoke()
                        }
                    }
                }
            }

            override fun getItemCount(): Int = when (_currentQueryType) {
                QueryType.BSSID_ONLY -> _bssidList.size
                QueryType.ESSID_ONLY -> _essidList.size
                QueryType.EXACT_MATCH -> _exactPairs.size
            }
        }
    }

    class ApiWps : API3WiFiMethodParams() {
        private lateinit var bssidInput: TextInputEditText
        private lateinit var bssidInputLayout: TextInputLayout
        private lateinit var bssidChipGroup: ChipGroup
        private val bssidList = mutableListOf<String>()

        override fun createView(context: Context, container: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(
                R.layout.layout_api_wps_params,
                container,
                false
            )

            bssidInput = view.findViewById(R.id.bssidInput)
            bssidInputLayout = view.findViewById(R.id.bssidInputLayout)
            bssidChipGroup = view.findViewById(R.id.bssidChipGroup)

            // BSSID фильтр
            bssidInput.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val newText = dest.toString().substring(0, dstart) + source.toString() + dest.toString().substring(dend)
                if (newText.isEmpty()) return@InputFilter source
                if (newText.length > 17) return@InputFilter ""
                if (!newText.matches(Regex("^[0-9A-Fa-f]([0-9A-Fa-f]:){0,4}[0-9A-Fa-f]?$"))) {
                    return@InputFilter ""
                }
                source
            })

            view.findViewById<MaterialButton>(R.id.addBssidButton).setOnClickListener {
                addBssid()
            }

            return view
        }

        private fun addBssid() {
            val bssid = bssidInput.text.toString().uppercase()
            if (bssid.isNotEmpty()) {
                if (!bssidList.contains(bssid)) {
                    bssidList.add(bssid)
                    val chip = Chip(bssidChipGroup.context).apply {
                        text = bssid
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            bssidChipGroup.removeView(this)
                            bssidList.remove(bssid)
                        }
                    }
                    bssidChipGroup.addView(chip)
                }
                bssidInput.text?.clear()
                bssidInputLayout.error = null
            }
        }

        override fun getRequest(key: String): API3WiFiRequest? {
            return if (isValid()) {
                API3WiFiRequest.ApiWps(
                    key = key,
                    bssidList = bssidList
                )
            } else null
        }

        override fun isValid(): Boolean {
            return bssidList.isNotEmpty()
        }

        override fun validate() {
            if (bssidList.isEmpty()) {
                bssidInputLayout.error = "At least one BSSID required"
            } else {
                bssidInputLayout.error = null
            }
        }

        override fun clear() {
            bssidList.clear()
            bssidChipGroup.removeAllViews()
            bssidInput.text?.clear()
            bssidInputLayout.error = null
        }
    }

    class ApiDev : API3WiFiMethodParams() {
        private lateinit var bssidInput: TextInputEditText
        private lateinit var bssidInputLayout: TextInputLayout
        private lateinit var bssidChipGroup: ChipGroup
        private lateinit var excludeClientsSwitch: SwitchMaterial
        private val bssidList = mutableListOf<String>()

        override fun createView(context: Context, container: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(
                R.layout.layout_api_dev_params,
                container,
                false
            )

            bssidInput = view.findViewById(R.id.bssidInput)
            bssidInputLayout = view.findViewById(R.id.bssidInputLayout)
            bssidChipGroup = view.findViewById(R.id.bssidChipGroup)
            excludeClientsSwitch = view.findViewById(R.id.excludeClientsSwitch)

            bssidInput.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
                val newText = dest.toString().substring(0, dstart) + source.toString() + dest.toString().substring(dend)
                if (newText.isEmpty()) return@InputFilter source
                if (newText.length > 17) return@InputFilter ""
                if (!newText.matches(Regex("^[0-9A-Fa-f]([0-9A-Fa-f]:){0,4}[0-9A-Fa-f]?$"))) {
                    return@InputFilter ""
                }
                source
            })

            view.findViewById<MaterialButton>(R.id.addBssidButton).setOnClickListener {
                addBssid()
            }

            return view
        }

        private fun addBssid() {
            val bssid = bssidInput.text.toString().uppercase()
            if (bssid.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$"))) {
                if (!bssidList.contains(bssid)) {
                    bssidList.add(bssid)
                    val chip = Chip(bssidChipGroup.context).apply {
                        text = bssid
                        isCloseIconVisible = true
                        setOnCloseIconClickListener {
                            bssidChipGroup.removeView(this)
                            bssidList.remove(bssid)
                        }
                    }
                    bssidChipGroup.addView(chip)
                }
                bssidInput.text?.clear()
                bssidInputLayout.error = null
            } else {
                bssidInputLayout.error = bssidInputLayout.context.getString(R.string.invalid_bssid_format)
            }
        }

        override fun getRequest(key: String): API3WiFiRequest? {
            return if (isValid()) {
                API3WiFiRequest.ApiDev(
                    key = key,
                    bssidList = bssidList,
                    nocli = excludeClientsSwitch.isChecked
                )
            } else null
        }

        override fun isValid(): Boolean {
            return bssidList.isNotEmpty() &&
                    bssidList.all { it.matches(Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")) }
        }

        override fun validate() {
            if (bssidList.isEmpty()) {
                bssidInputLayout.error = "At least one BSSID required"
            } else {
                bssidInputLayout.error = null
            }
        }

        override fun clear() {
            bssidList.clear()
            bssidChipGroup.removeAllViews()
            bssidInput.text?.clear()
            bssidInputLayout.error = null
            excludeClientsSwitch.isChecked = true
        }
    }

    class ApiRanges : API3WiFiMethodParams() {
        private lateinit var latitudeInput: TextInputEditText
        private lateinit var longitudeInput: TextInputEditText
        private lateinit var radiusInput: TextInputEditText
        private lateinit var latitudeInputLayout: TextInputLayout
        private lateinit var longitudeInputLayout: TextInputLayout
        private lateinit var radiusInputLayout: TextInputLayout

        override fun createView(context: Context, container: ViewGroup): View {
            val view = LayoutInflater.from(context).inflate(
                R.layout.layout_api_ranges_params,
                container,
                false
            )

            latitudeInput = view.findViewById(R.id.latitudeInput)
            longitudeInput = view.findViewById(R.id.longitudeInput)
            radiusInput = view.findViewById(R.id.radiusInput)
            latitudeInputLayout = view.findViewById(R.id.latitudeInputLayout)
            longitudeInputLayout = view.findViewById(R.id.longitudeInputLayout)
            radiusInputLayout = view.findViewById(R.id.radiusInputLayout)

            setupInputFilters()

            return view
        }

        private fun setupInputFilters() {
            // Фильтры для числовых полей
            val latLonFilter = InputFilter { source, start, end, dest, dstart, dend ->
                val newText = dest.toString().substring(0, dstart) + source.toString() + dest.toString().substring(dend)
                if (newText.isEmpty() || newText == "-" || newText == ".") return@InputFilter source
                if (!newText.matches(Regex("^-?\\d*\\.?\\d*$"))) return@InputFilter ""
                source
            }

            val radiusFilter = InputFilter { source, start, end, dest, dstart, dend ->
                val newText = dest.toString().substring(0, dstart) + source.toString() + dest.toString().substring(dend)
                if (newText.isEmpty() || newText == ".") return@InputFilter source
                if (!newText.matches(Regex("^\\d*\\.?\\d*$"))) return@InputFilter ""
                source
            }

            latitudeInput.filters = arrayOf(latLonFilter)
            longitudeInput.filters = arrayOf(latLonFilter)
            radiusInput.filters = arrayOf(radiusFilter)
        }

        override fun getRequest(key: String): API3WiFiRequest? {
            return if (isValid()) {
                API3WiFiRequest.ApiRanges(
                    key = key,
                    lat = latitudeInput.text.toString().toFloat(),
                    lon = longitudeInput.text.toString().toFloat(),
                    rad = radiusInput.text.toString().toFloat()
                )
            } else null
        }

        override fun isValid(): Boolean {
            val lat = latitudeInput.text.toString().toFloatOrNull()
            val lon = longitudeInput.text.toString().toFloatOrNull()
            val rad = radiusInput.text.toString().toFloatOrNull()

            return lat != null && lon != null && rad != null
        }

        override fun validate() {
            val lat = latitudeInput.text.toString().toFloatOrNull()
            val lon = longitudeInput.text.toString().toFloatOrNull()
            val rad = radiusInput.text.toString().toFloatOrNull()

            latitudeInputLayout.error = if (lat == null) "Invalid number format" else null
            longitudeInputLayout.error = if (lon == null) "Invalid number format" else null
            radiusInputLayout.error = if (rad == null) "Invalid number format" else null
        }

        override fun clear() {
            latitudeInput.text?.clear()
            longitudeInput.text?.clear()
            radiusInput.text?.clear()
            latitudeInputLayout.error = null
            longitudeInputLayout.error = null
            radiusInputLayout.error = null
        }
    }

    companion object {
        fun create(methodName: String): API3WiFiMethodParams {
            return when (methodName) {
                "apiquery" -> ApiQuery()
                "apiwps" -> ApiWps()
                "apidev" -> ApiDev()
                "apiranges" -> ApiRanges()
                else -> throw IllegalArgumentException("Unknown method: $methodName")
            }
        }
    }
}