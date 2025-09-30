/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.preferences

import android.annotation.IntDef
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PackageInfoFlags
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SearchView
import android.widget.TextView

import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.android.settings.R

import com.google.android.material.appbar.AppBarLayout

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Fragment] that hosts a [RecyclerView] with a vertical
 * list of application info. Items display an icon, name
 * and package name of the application, along with a [Switch]
 * indicating whether the item is selected or not.
 */
abstract class AppListFragment : Fragment(R.layout.app_list_layout),
    MenuItem.OnActionExpandListener {

    private val mutex = Mutex()

    private lateinit var pm: PackageManager
    private lateinit var adapter: AppListAdapter

    private var appBarLayout: AppBarLayout? = null
    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null

    private var searchText = ""
    private var displayCategory: Int = CATEGORY_USER_ONLY
    private var packageFilter: (PackageInfo) -> Boolean = { true }
    private var packageComparator: (PackageInfo, PackageInfo) -> Int = { first, second ->
        getLabel(first).compareTo(getLabel(second))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
        pm = requireContext().packageManager
    }

    /**
     * Override this function to set the title of this fragment.
     */
    abstract protected fun getTitle(): Int

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val activity = requireActivity()
        activity.setTitle(getTitle())
        appBarLayout = activity.findViewById(com.android.settingslib.collapsingtoolbar.R.id.app_bar)
        progressBar = view.findViewById(R.id.loading_progress)
        adapter = AppListAdapter(getInitialCheckedList(), layoutInflater).apply {
            setOnAppSelectListener { onAppSelected(it) }
            setOnAppDeselectListener { onAppDeselected(it) }
            setOnListUpdateListener { onListUpdate(it) }
        }
        recyclerView = view.findViewById<RecyclerView>(R.id.apps_list)?.also {
            it.layoutManager = LinearLayoutManager(context)
            it.adapter = adapter
        }
        refreshList()
    }

    /**
     * Abstract function for subclasses to override for providing
     * an initial list of packages that should appear as selected.
     */
    abstract protected fun getInitialCheckedList(): List<String>

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.app_list_menu, menu)
        val searchItem = menu.findItem(R.id.search).also {
            if (appBarLayout != null) {
                it.setOnActionExpandListener(this)
            }
        }
        val searchView = searchItem.actionView as SearchView
        searchView.setQueryHint(getString(R.string.search_apps))
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String) = false

            override fun onQueryTextChange(newText: String): Boolean {
                lifecycleScope.launch {
                    mutex.withLock {
                        searchText = newText
                    }
                    refreshListInternal()
                }
                return true
            }
        })
    }

    override fun onMenuItemActionExpand(item: MenuItem): Boolean {
        // To prevent a large space on tool bar.
        appBarLayout?.setExpanded(false /*expanded*/, false /*animate*/)
        // To prevent user expanding the collapsing tool bar view.
        recyclerView?.let { ViewCompat.setNestedScrollingEnabled(it, false) }
        return true
    }

    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
        // We keep the collapsed status after user cancel the search function.
        appBarLayout?.setExpanded(false /*expanded*/, false /*animate*/)
        // Allow user to expande the tool bar view.
        recyclerView?.let { ViewCompat.setNestedScrollingEnabled(it, true) }
        return true
    }

    /**
     * Set the type of apps that should be displayed in the list.
     * Defaults to [CATEGORY_USER_ONLY].
     *
     * @param category one of [CATEGORY_SYSTEM_ONLY],
     *      [CATEGORY_USER_ONLY], [CATEGORY_BOTH]
     */
    fun setDisplayCategory(@Category category: Int) {
        lifecycleScope.launch {
            mutex.withLock {
                displayCategory = category
            }
        }
    }

    /**
     * Set a custom filter to filter out items from the list.
     *
     * @param customFilter a function that takes a [PackageInfo] and
     *      returns a [Boolean] indicating whether to show the item or not.
     */
    fun setCustomFilter(customFilter: (PackageInfo) -> Boolean) {
        lifecycleScope.launch {
            mutex.withLock {
                packageFilter = customFilter
            }
        }
    }

    /**
     * Set a [Comparator] for sorting the elements in the list.
     *
     * @param comparator a function that takes two [PackageInfo]'s and returns
     *      an [Int] representing their relative priority.
     */
    fun setComparator(comparator: (PackageInfo, PackageInfo) -> Int) {
        lifecycleScope.launch {
            mutex.withLock {
                packageComparator = comparator
            }
        }
    }

    /**
     * Called when user selected list is updated.
     *
     * @param list a [List<String>] of selected items.
     */
    open protected fun onListUpdate(list: List<String>) {}
    open protected fun onListUpdate(packageName: String, isChecked: Boolean) {}

    /**
     * Called when user selected an application.
     *
     * @param packageName the package name of the selected app.
     */
    open protected fun onAppSelected(packageName: String) {}

    /**
     * Called when user deselected an application.
     *
     * @param packageName the package name of the deselected app.
     */
    open protected fun onAppDeselected(packageName: String) {}

    fun refreshList() {
        lifecycleScope.launch {
            refreshListInternal()
        }
    }

    private suspend fun refreshListInternal() {
        val list = withContext(Dispatchers.Default) {
            val sortedList = mutex.withLock {
                pm.getInstalledPackages(
                    PackageInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                ).filter {
                    val categoryMatches = when (displayCategory) {
                        CATEGORY_SYSTEM_ONLY -> it.applicationInfo!!.isSystemApp()
                        CATEGORY_USER_ONLY -> !it.applicationInfo!!.isSystemApp()
                        else -> true
                    }
                    categoryMatches && packageFilter(it) &&
                        getLabel(it).contains(searchText, true)
                }.sortedWith(packageComparator)
            }
            sortedList.map {
                AppInfo(
                    it.packageName,
                    getLabel(it),
                    it.applicationInfo!!.loadIcon(pm),
                )
            }
        }
        adapter.submitList(list)
        progressBar?.visibility = View.GONE
    }

    private fun getLabel(packageInfo: PackageInfo) =
        packageInfo.applicationInfo!!.loadLabel(pm).toString()

    private inner class AppListAdapter(
        initialCheckedList: List<String>,
        private val layoutInflater: LayoutInflater
    ) : ListAdapter<AppInfo, AppListViewHolder>(itemCallback) {

        private val checkedList = initialCheckedList.toMutableList()
        private var appSelectListener: (String) -> Unit = {}
        private var appDeselectListener: (String) -> Unit = {}
        private var listUpdateListener: (List<String>) -> Unit = {}

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ) = AppListViewHolder(
                layoutInflater.inflate(
                    R.layout.app_list_item,
                    parent,
                    false /* attachToParent */
                )
            )

        override fun onBindViewHolder(holder: AppListViewHolder, position: Int) {
            getItem(position).let {
                holder.label!!.text = it.label
                holder.packageName!!.text = it.packageName
                holder.icon!!.setImageDrawable(it.icon)
                holder.switch!!.isChecked = checkedList.contains(it.packageName)
                holder.itemView.setOnClickListener {
                    if (checkedList.contains(holder.packageName!!.text.toString())) {
                        checkedList.remove(holder.packageName!!.text.toString())
                        appDeselectListener(holder.packageName!!.text.toString())
                        onListUpdate(holder.packageName!!.text.toString(), false)
                    } else {
                        checkedList.add(holder.packageName!!.text.toString())
                        appSelectListener(holder.packageName!!.text.toString())
                        onListUpdate(holder.packageName!!.text.toString(), true)
                    }
                    notifyItemChanged(position)
                    listUpdateListener(checkedList.toList())
                }
            }
        }

        fun setOnAppSelectListener(listener: (String) -> Unit) {
            appSelectListener = listener
        }

        fun setOnAppDeselectListener(listener: (String) -> Unit) {
            appDeselectListener = listener
        }

        fun setOnListUpdateListener(listener: (List<String>) -> Unit) {
            listUpdateListener = listener
        }
    }

    private class AppListViewHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {

        val icon: ImageView? = itemView.findViewById(R.id.icon)
        val label: TextView? = itemView.findViewById(R.id.label)
        val packageName: TextView? = itemView.findViewById(R.id.package_name)
        val switch: CompoundButton? = itemView.findViewById(R.id.app_list_switch)
    }

    private data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: Drawable,
    )

    companion object {
        const val CATEGORY_SYSTEM_ONLY = 0
        const val CATEGORY_USER_ONLY = 1
        const val CATEGORY_BOTH = 2

        @IntDef(value = intArrayOf(
            CATEGORY_SYSTEM_ONLY,
            CATEGORY_USER_ONLY,
            CATEGORY_BOTH
        ))
        @Retention(AnnotationRetention.SOURCE)
        annotation class Category

        private val itemCallback = object: DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldInfo: AppInfo, newInfo: AppInfo) =
                oldInfo.packageName == newInfo.packageName
            
            override fun areContentsTheSame(oldInfo: AppInfo, newInfo: AppInfo) =
                oldInfo == newInfo
        }
    }
}
