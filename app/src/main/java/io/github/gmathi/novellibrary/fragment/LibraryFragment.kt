package io.github.gmathi.novellibrary.fragment

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.support.v4.content.ContextCompat
import android.support.v4.view.MotionEventCompat
import android.support.v7.widget.helper.ItemTouchHelper
import android.util.Log
import android.view.*
import co.metalab.asyncawait.async
import com.afollestad.materialdialogs.MaterialDialog
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.crashlytics.android.Crashlytics
import com.tapadoo.alerter.Alerter
import io.github.gmathi.novellibrary.R
import io.github.gmathi.novellibrary.activity.NovelDetailsActivity
import io.github.gmathi.novellibrary.activity.startChaptersActivity
import io.github.gmathi.novellibrary.activity.startImportLibraryActivity
import io.github.gmathi.novellibrary.activity.startReaderDBPagerActivity
import io.github.gmathi.novellibrary.adapter.GenericAdapter
import io.github.gmathi.novellibrary.database.*
import io.github.gmathi.novellibrary.dbHelper
import io.github.gmathi.novellibrary.model.Novel
import io.github.gmathi.novellibrary.model.NovelEvent
import io.github.gmathi.novellibrary.network.NovelApi
import io.github.gmathi.novellibrary.network.getChapterCount
import io.github.gmathi.novellibrary.network.getChapterUrls
import io.github.gmathi.novellibrary.util.*
import kotlinx.android.synthetic.main.content_library.*
import kotlinx.android.synthetic.main.listitem_library.view.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode


class LibraryFragment : BaseFragment(), GenericAdapter.Listener<Novel>, SimpleItemTouchListener {

    lateinit var adapter: GenericAdapter<Novel>
    private lateinit var touchHelper: ItemTouchHelper

    private var novelSectionId: Long = -1L
    private var lastDeletedId: Long = -1
    private var isSorted = false
    private var syncDialog: MaterialDialog? = null

    companion object {

        private const val NOVEL_SECTION_ID = "novelSectionId"

        fun newInstance(novelSectionId: Long): LibraryFragment {
            val bundle = Bundle()
            bundle.putLong(NOVEL_SECTION_ID, novelSectionId)
            val fragment = LibraryFragment()
            fragment.arguments = bundle
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.content_library, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        novelSectionId = arguments!!.getLong(NOVEL_SECTION_ID)

        setRecyclerView()
        progressLayout.showLoading()
        setData()
    }

    private fun setRecyclerView() {
        adapter = GenericAdapter(ArrayList(), R.layout.listitem_library, this)
        val callback = SimpleItemTouchHelperCallback(this)
        touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(recyclerView)
        recyclerView.setDefaults(adapter)
        swipeRefreshLayout.setOnRefreshListener {
            setData()
        }
    }

    private fun setData() {
        updateOrderIds()
        adapter.updateData(ArrayList(dbHelper.getAllNovels(novelSectionId)))
        if (swipeRefreshLayout != null && progressLayout != null) {
            swipeRefreshLayout.isRefreshing = false
            progressLayout.showContent()
        }
    }


    //region Adapter Listener Methods - onItemClick(), viewBinder()

    override fun onItemClick(item: Novel) {
        if (lastDeletedId != item.id)
            activity?.startChaptersActivity(item)
    }

    override fun bind(item: Novel, itemView: View, position: Int) {
        itemView.novelImageView.setImageResource(android.R.color.transparent)

        if (item.imageUrl != null) {
            Glide.with(this)
                .load(item.imageUrl?.getGlideUrl())
                .apply(RequestOptions.circleCropTransform())
                .into(itemView.novelImageView)
        }

        itemView.novelTitleTextView.text = item.name
        if (item.rating != null) {
            var ratingText = "(N/A)"
            try {
                val rating = item.rating!!.toFloat()
                itemView.novelRatingBar.rating = rating
                ratingText = "(" + String.format("%.1f", rating) + ")"
            } catch (e: Exception) {
                Log.w("Library Activity", "Rating: " + item.rating, e)
            }
            itemView.novelRatingText.text = ratingText
        }

        itemView.reorderButton.setOnTouchListener { _, event ->
            @Suppress("DEPRECATION")
            if (MotionEventCompat.getActionMasked(event) ==
                MotionEvent.ACTION_DOWN) {
                touchHelper.startDrag(recyclerView.getChildViewHolder(itemView))
            }
            false
        }

        itemView.readChapterImage.setOnClickListener {
            startReader(item)
        }


        if (item.newReleasesCount != 0L) {
            val shape = GradientDrawable()
            shape.cornerRadius = 99f
            activity?.let { ContextCompat.getColor(it, R.color.Black) }?.let { shape.setStroke(1, it) }
            activity?.let { ContextCompat.getColor(it, R.color.DarkRed) }?.let { shape.setColor(it) }
            itemView.newChapterCount.background = shape
            itemView.newChapterCount.applyFont(activity?.assets).text = item.newReleasesCount.toString()
            itemView.newChapterCount.visibility = View.VISIBLE
        } else {
            itemView.newChapterCount.visibility = View.GONE
        }

        if (item.currentWebPageId != -1L) {
            val orderId = dbHelper.getWebPage(item.currentWebPageId)?.orderId
            if (orderId != null) {
                val progress = "${orderId + 1} / ${item.chaptersCount}"
                itemView.novelProgressText.text = progress
            }
        } else {
            itemView.novelProgressText.text = getString(R.string.no_bookmark)
        }

        itemView.infoButton.setOnClickListener {
            if (lastDeletedId != item.id)
                startNovelDetailsActivity(item, false)
        }
    }

    //endregion

    //region Sync Code
    override fun onCreateOptionsMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_library, menu)
        super.onCreateOptionsMenu(menu, menuInflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (activity != null)
            menu.getItem(0).isVisible = (syncDialog == null)
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_sync -> {
                activity?.let {
                    if (Utils.isConnectedToNetwork(it))
                        syncNovels()
                    else {
                        Alerter.clearCurrent(it)
                        Alerter.create(it).setText("No Internet Connection!").setIcon(R.drawable.ic_warning_white_vector).setBackgroundColorRes(R.color.Red).show()
                    }
                }
                return true
            }
            R.id.action_sort -> {
                sortNovelsAlphabetically()
            }
            R.id.action_import_reading_list -> {
                activity?.startImportLibraryActivity()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun sortNovelsAlphabetically() {
        if (adapter.items.isNotEmpty()) {
            val items = adapter.items
            if (!isSorted)
                adapter.updateData(ArrayList(items.sortedWith(compareBy({ it.name }))))
            else
                adapter.updateData(ArrayList(items.sortedWith(compareBy({ it.name })).reversed()))
            isSorted = !isSorted
            updateOrderIds()
        }
    }

    private fun syncNovels() {
        //activity.startSyncService()
        async syncing@{

            if (activity == null) return@syncing

            if (syncDialog != null && syncDialog!!.isShowing)
                syncDialog!!.hide()

            syncDialog = MaterialDialog.Builder(activity!!)
                .title(R.string.sync_in_progress)
                .content(R.string.please_wait)
                .progress(true, 0)
                .cancelable(false)
                .build()

            syncDialog!!.show()
            activity?.invalidateOptionsMenu()

            val totalCountMap: HashMap<Novel, Int> = HashMap()
            val novels = dbHelper.getAllNovels(novelSectionId)
            novels.forEach {
                try {
                    val totalChapters = await { NovelApi.getChapterCount(it) }
                    if (totalChapters != 0 && totalChapters > it.chaptersCount.toInt()) {
                        totalCountMap[it] = totalChapters
                    }
                } catch (e: Exception) {
                    Crashlytics.log("Novel: $it")
                    Crashlytics.logException(e)
                    return@forEach
                }
            }

            //Update DB with new chapters
            totalCountMap.forEach {
                val novel = it.key
                dbHelper.updateChaptersAndReleasesCount(novel.id, it.value.toLong(), novel.newReleasesCount + (it.value - novel.chaptersCount))

                //updateChapters(novel, dbHelper)
                if (!Utils.isConnectedToNetwork(activity) || novel.id == -1L)
                    return@forEach

                //Download latest chapters from network
                try {
                    val chapterList = await { NovelApi.getChapterUrls(novel) }?.reversed()
                    chapterList?.let {

                        //We start the insertion from the last since that is faster, instead of checking the 1st 1000 chaps.
                        for (i in it.size - 1 downTo 0) {
                            val webPage = dbHelper.getWebPage(novel.id, i.toLong())
                            if (webPage == null) {
                                it[i].orderId = i.toLong()
                                it[i].novelId = novel.id
                                dbHelper.createWebPage(it[i])
                            } else
                                return@let
                        }
                    }
                } catch (e: Exception) {
                    Crashlytics.log("Novel: $novel")
                    Crashlytics.logException(e)
                }
            }

            setData()

            syncDialog!!.hide()
            syncDialog = null
            activity?.invalidateOptionsMenu()
        }
    }

    //endregion

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onResume() {
        super.onResume()
        adapter.updateData(ArrayList(dbHelper.getAllNovels(novelSectionId)))
    }

    override fun onPause() {
        super.onPause()
        updateOrderIds()
    }

    override fun onStop() {
        EventBus.getDefault().unregister(this)
        super.onStop()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onNovelEvent(event: NovelEvent) {
        print(event.novelId)
    }

    private fun startReader(novel: Novel) {
        if (novel.currentWebPageId != -1L) {
            activity?.startReaderDBPagerActivity(novel)
        } else {
            val confirmDialog = activity?.let {
                MaterialDialog.Builder(it)
                    .title(getString(R.string.no_bookmark_found_dialog_title))
                    .content(getString(R.string.no_bookmark_found_dialog_description, novel.name))
                    .positiveText(getString(R.string.okay))
                    .negativeText(R.string.cancel)
                    .onPositive { dialog, _ -> it.startChaptersActivity(novel, false); dialog.dismiss() }
            }
            confirmDialog!!.show()
        }
    }

    private fun startNovelDetailsActivity(novel: Novel, jumpToReader: Boolean) {
        val intent = Intent(activity, NovelDetailsActivity::class.java)
        val bundle = Bundle()
        bundle.putSerializable("novel", novel)
        if (jumpToReader)
            bundle.putBoolean(Constants.JUMP, true)
        intent.putExtras(bundle)
        activity?.startActivityForResult(intent, Constants.NOVEL_DETAILS_REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Constants.NOVEL_DETAILS_RES_CODE) {
            val novelId = data?.extras?.getLong(Constants.NOVEL_ID)
            if (novelId != null) {
                lastDeletedId = novelId
                adapter.removeItemAt(adapter.items.indexOfFirst { it.id == novelId })
                Handler().postDelayed({ lastDeletedId = -1 }, 1200)
            }
            return
        } else if (requestCode == Constants.READER_ACT_REQ_CODE || requestCode == Constants.NOVEL_DETAILS_RES_CODE) {
            setData()
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onItemDismiss(viewHolderPosition: Int) {
        activity?.let {
            MaterialDialog.Builder(it)
                .title(getString(R.string.confirm_remove))
                .content(getString(R.string.confirm_remove_description_novel))
                .positiveText(R.string.remove)
                .negativeText(R.string.cancel)
                .onPositive { dialog, _ ->
                    run {
                        val novel = adapter.items[viewHolderPosition]
                        Utils.deleteNovel(it, novel.id)
                        adapter.onItemDismiss(viewHolderPosition)
                        dialog.dismiss()
                    }
                }
                .onNegative { dialog, _ ->
                    run {
                        adapter.notifyDataSetChanged()
                        dialog.dismiss()
                    }
                }
                .show()
        }
    }

    override fun onItemMove(source: Int, target: Int) {
        adapter.onItemMove(source, target)
    }

    private fun updateOrderIds() {
        if (adapter.items.isNotEmpty())
            for (i in 0 until adapter.items.size) {
                dbHelper.updateNovelOrderId(adapter.items[i].id, i.toLong())
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        async.cancelAll()
    }

}
