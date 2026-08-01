package com.tjg.twidget.followers

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tjg.twidget.R
import com.tjg.twidget.data.TwidgetStore
import com.tjg.twidget.ui.FoldablePopOverActivity
import com.tjg.twidget.ui.ProfileImageLoader
import com.tjg.twidget.ui.TwidgetFonts
import dev.oneuiproject.oneui.layout.ToolbarLayout
import dev.oneuiproject.oneui.R as OneUiIconR

class TopFollowersBrowseActivity : FoldablePopOverActivity() {
    private lateinit var username: String
    private var allFollowers = emptyList<TopFollower>()
    private var query = ""
    private lateinit var adapter: FollowerAdapter
    private lateinit var emptyView: TextView
    private lateinit var listView: RecyclerView
    private lateinit var toolbarLayout: ToolbarLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_top_followers_browse)
        username = intent.getStringExtra(EXTRA_USERNAME).orEmpty().trim().trimStart('@')
        if (username.isBlank()) {
            finish()
            return
        }

        toolbarLayout = findViewById(R.id.top_followers_browse_root)
        toolbarLayout.setNavigationButtonOnClickListener { onBackPressedDispatcher.onBackPressed() }
        applyEdgeToEdgeInsets(toolbarLayout)

        emptyView = findViewById(R.id.top_followers_browse_empty)
        listView = findViewById(R.id.top_followers_browse_list)
        adapter = FollowerAdapter { openProfile(it.username) }
        listView.layoutManager = LinearLayoutManager(this).apply {
            initialPrefetchItemCount = 0
        }
        listView.adapter = adapter
        listView.background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(getColor(R.color.oneui_card_bg))
        }
        listView.clipToOutline = true

        allFollowers = TopFollowersArchiveStore.readAll(this, username)
        render()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu
            .add(Menu.NONE, View.generateViewId(), Menu.NONE, R.string.top_followers_browser_search)
            .apply {
                setIcon(OneUiIconR.drawable.ic_oui_search)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                setOnMenuItemClickListener {
                    toolbarLayout.startSearchMode(
                        object : ToolbarLayout.SearchModeListener {
                            override fun onSearchModeToggle(searchView: SearchView, isActive: Boolean) {
                                if (isActive) {
                                    searchView.queryHint = getString(R.string.top_followers_browser_search_hint)
                                } else {
                                    query = ""
                                    render()
                                }
                            }

                            override fun onQueryTextSubmit(submittedQuery: String?): Boolean {
                                updateQuery(submittedQuery)
                                return true
                            }

                            override fun onQueryTextChange(newText: String?): Boolean {
                                updateQuery(newText)
                                return true
                            }
                        },
                        ToolbarLayout.SearchModeOnBackBehavior.CLEAR_DISMISS,
                        true,
                    )
                    true
                }
            }
        return true
    }

    private fun render() {
        val visible = TopFollowersBrowserPolicy.apply(allFollowers, query)
        adapter.submitList(visible)
        val empty = visible.isEmpty()
        emptyView.setText(
            if (query.isBlank()) {
                R.string.top_followers_browser_empty
            } else {
                R.string.top_followers_browser_no_results
            },
        )
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        listView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun updateQuery(value: String?) {
        val nextQuery = value.orEmpty()
        if (query == nextQuery) return
        query = nextQuery
        render()
    }

    private fun openProfile(handle: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/${Uri.encode(handle)}")))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class FollowerAdapter(
        private val onClick: (TopFollower) -> Unit,
    ) : ListAdapter<RankedTopFollower, FollowerAdapter.Holder>(DIFF) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_top_follower_row, parent, false)
            return Holder(view, onClick)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), showDivider = position < itemCount - 1)
        }

        override fun onViewAttachedToWindow(holder: Holder) {
            super.onViewAttachedToWindow(holder)
            holder.loadAvatarIfVisible()
        }

        override fun onViewDetachedFromWindow(holder: Holder) {
            holder.clearAvatar()
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
            super.onViewRecycled(holder)
        }

        class Holder(
            itemView: View,
            private val onClick: (TopFollower) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {
            private val rank = itemView.findViewById<TextView>(R.id.top_follower_row_rank)
            private val avatar = itemView.findViewById<ImageView>(R.id.top_follower_row_avatar)
            private val name = itemView.findViewById<TextView>(R.id.top_follower_row_name)
            private val handle = itemView.findViewById<TextView>(R.id.top_follower_row_username)
            private val count = itemView.findViewById<TextView>(R.id.top_follower_row_count)
            private val divider = itemView.findViewById<View>(R.id.top_follower_row_divider)
            private var boundFollower: TopFollower? = null
            private var requestedIdentity: String? = null

            init {
                // RecyclerView rows are inflated after the activity's initial font pass.
                // Apply the app typeface before first draw so recycled/new rows never
                // fall back to Roboto while waiting for another global layout.
                rank.typeface = TwidgetFonts.oneUiSans(itemView.context, 200)
                name.typeface = TwidgetFonts.oneUiSans(itemView.context, 700)
                handle.typeface = TwidgetFonts.oneUiSans(itemView.context, 400)
                count.typeface = TwidgetFonts.oneUiSans(itemView.context, 400)
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    rank,
                    12,
                    30,
                    1,
                    TypedValue.COMPLEX_UNIT_SP,
                )
            }

            fun bind(ranked: RankedTopFollower, showDivider: Boolean) {
                clearAvatar()
                val follower = ranked.follower
                boundFollower = follower
                rank.text = ranked.rank.toString()
                name.text = follower.name
                handle.text = "@${follower.username}"
                count.text = TwidgetStore.compactNumber(follower.followers)
                divider.visibility = if (showDivider) View.VISIBLE else View.INVISIBLE
                itemView.setOnClickListener { onClick(follower) }
                loadAvatarIfVisible()
            }

            fun loadAvatarIfVisible() {
                val follower = boundFollower ?: return
                val identity = follower.identity()
                if (!TopFollowerAvatarLoadPolicy.shouldLoad(
                        itemView.isAttachedToWindow,
                        identity,
                        requestedIdentity,
                    )
                ) return
                requestedIdentity = identity
                ProfileImageLoader.loadInto(itemView.context, avatar, follower.avatarUrl)
            }

            fun clearAvatar() {
                requestedIdentity = null
                avatar.setTag(R.id.profile_image_request, null)
                ProfileImageLoader.applyCircleClip(avatar)
                avatar.setPadding(0, 0, 0, 0)
                avatar.imageTintList = null
                avatar.setImageDrawable(null)
                avatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(itemView.context.getColor(R.color.top_followers_skeleton))
                }
            }

            fun recycle() {
                clearAvatar()
                boundFollower = null
                itemView.setOnClickListener(null)
            }

            private fun TopFollower.identity(): String =
                id.ifBlank { username }.lowercase()
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<RankedTopFollower>() {
                override fun areItemsTheSame(
                    oldItem: RankedTopFollower,
                    newItem: RankedTopFollower,
                ): Boolean =
                    oldItem.follower.id == newItem.follower.id &&
                        oldItem.follower.username == newItem.follower.username

                override fun areContentsTheSame(
                    oldItem: RankedTopFollower,
                    newItem: RankedTopFollower,
                ): Boolean =
                    oldItem == newItem
            }
        }
    }

    companion object {
        const val EXTRA_USERNAME = "username"
    }
}
