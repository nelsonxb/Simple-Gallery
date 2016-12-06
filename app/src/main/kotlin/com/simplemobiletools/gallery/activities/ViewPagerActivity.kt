package com.simplemobiletools.gallery.activities

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.database.Cursor
import android.os.Bundle
import android.provider.MediaStore
import android.support.v4.view.ViewPager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.simplemobiletools.filepicker.asynctasks.CopyMoveTask
import com.simplemobiletools.filepicker.dialogs.ConfirmationDialog
import com.simplemobiletools.filepicker.extensions.*
import com.simplemobiletools.fileproperties.dialogs.PropertiesDialog
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.adapters.MyPagerAdapter
import com.simplemobiletools.gallery.asynctasks.GetMediaAsynctask
import com.simplemobiletools.gallery.dialogs.CopyDialog
import com.simplemobiletools.gallery.dialogs.RenameFileDialog
import com.simplemobiletools.gallery.extensions.openEditor
import com.simplemobiletools.gallery.extensions.openWith
import com.simplemobiletools.gallery.extensions.setAsWallpaper
import com.simplemobiletools.gallery.extensions.shareMedium
import com.simplemobiletools.gallery.fragments.ViewPagerFragment
import com.simplemobiletools.gallery.helpers.MEDIUM
import com.simplemobiletools.gallery.helpers.REQUEST_EDIT_IMAGE
import com.simplemobiletools.gallery.helpers.REQUEST_SET_WALLPAPER
import com.simplemobiletools.gallery.models.Medium
import kotlinx.android.synthetic.main.activity_medium.*
import java.io.File
import java.util.*

class ViewPagerActivity : SimpleActivity(), ViewPager.OnPageChangeListener, View.OnSystemUiVisibilityChangeListener, ViewPagerFragment.FragmentClickListener {
    private var mMedia = ArrayList<Medium>()
    private var mPath = ""
    private var mDirectory = ""

    private var mIsFullScreen = false
    private var mPos = -1
    private var mShowAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medium)

        if (!hasStoragePermission()) {
            finish()
            return
        }

        val uri = intent.data
        if (uri != null) {
            var cursor: Cursor? = null
            try {
                val proj = arrayOf(MediaStore.Images.Media.DATA)
                cursor = contentResolver.query(uri, proj, null, null, null)
                if (cursor != null) {
                    val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                    cursor.moveToFirst()
                    mPath = cursor.getString(dataIndex)
                }
            } finally {
                cursor?.close()
            }
        } else {
            mPath = intent.getStringExtra(MEDIUM)
            mShowAll = mConfig.showAll
        }

        if (mPath.isEmpty()) {
            toast(R.string.unknown_error)
            finish()
            return
        }

        mMedia = ArrayList<Medium>()
        showSystemUI()

        mDirectory = File(mPath).parent
        title = mPath.getFilenameFromPath()
        window.decorView.setOnSystemUiVisibilityChangeListener(this)
        reloadViewPager()
        scanPath(mPath) {}
    }

    override fun onResume() {
        super.onResume()
        if (!hasStoragePermission()) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.viewpager_menu, menu)

        menu.findItem(R.id.menu_set_as_wallpaper).isVisible = getCurrentMedium()?.isImage() == true
        menu.findItem(R.id.menu_edit).isVisible = getCurrentMedium()?.isImage() == true

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_set_as_wallpaper -> {
                setAsWallpaper(getCurrentFile())
                true
            }
            R.id.menu_copy_move -> {
                displayCopyDialog()
                true
            }
            R.id.menu_open_with -> {
                openWith(getCurrentFile())
                true
            }
            R.id.menu_share -> {
                shareMedium(getCurrentMedium()!!)
                true
            }
            R.id.menu_delete -> {
                askConfirmDelete()
                true
            }
            R.id.menu_rename -> {
                editMedium()
                true
            }
            R.id.menu_edit -> {
                openEditor(getCurrentFile())
                true
            }
            R.id.menu_properties -> {
                showProperties()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val adapter = view_pager.adapter as MyPagerAdapter
        adapter.updateItems(mPos)
    }

    private fun updatePagerItems() {
        val pagerAdapter = MyPagerAdapter(this, supportFragmentManager, mMedia)
        view_pager.apply {
            adapter = pagerAdapter
            currentItem = mPos
            addOnPageChangeListener(this@ViewPagerActivity)
        }
    }

    private fun displayCopyDialog() {
        val files = ArrayList<File>()
        files.add(getCurrentFile())
        CopyDialog(this, files, object : CopyMoveTask.CopyMoveListener {
            override fun copySucceeded(deleted: Boolean, copiedAll: Boolean) {
                if (deleted) {
                    reloadViewPager()
                    toast(if (copiedAll) R.string.moving_success else R.string.moving_success_partial)
                } else {
                    toast(if (copiedAll) R.string.copying_success else R.string.copying_success_partial)
                }
            }

            override fun copyFailed() {
                toast(R.string.copy_move_failed)
            }
        })
    }

    private fun showProperties() {
        PropertiesDialog(this, getCurrentFile().absolutePath, false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == REQUEST_EDIT_IMAGE) {
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                mPos = -1
                reloadViewPager()
            }
        } else if (requestCode == REQUEST_SET_WALLPAPER) {
            if (resultCode == Activity.RESULT_OK) {
                toast(R.string.wallpaper_set_successfully)
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    private fun askConfirmDelete() {
        ConfirmationDialog(this) {
            deleteFile()
        }
    }

    private fun deleteFile() {
        val file = File(mMedia[mPos].path)
        if (isShowingPermDialog(file))
            return

        if (needsStupidWritePermissions(mPath)) {
            if (!isShowingPermDialog(file)) {
                val document = getFileDocument(mPath, mConfig.treeUri)
                if (document.uri.toString().endsWith(file.absolutePath.getFilenameFromPath()) && !document.isDirectory)
                    document.delete()
            }
        } else {
            file.delete()
        }

        try {
            if (file.exists())
                file.delete()
        } catch (ignored: Exception) {

        }

        scanFile(file) {
            reloadViewPager()
        }
    }

    private fun isDirEmpty(): Boolean {
        return if (mMedia.size <= 0) {
            deleteDirectoryIfEmpty()
            finish()
            true
        } else
            false
    }

    private fun editMedium() {
        RenameFileDialog(this, getCurrentFile()) {
            mMedia[view_pager.currentItem].path = it.absolutePath
            updateActionbarTitle()
        }
    }

    private fun reloadViewPager() {
        GetMediaAsynctask(applicationContext, mDirectory, false, false, ArrayList<String>(), mShowAll) {
            mMedia = it
            if (isDirEmpty())
                return@GetMediaAsynctask

            if (mPos == -1) {
                mPos = getProperPosition()
            } else {
                mPos = Math.min(mPos, mMedia.size - 1)
            }

            updateActionbarTitle()
            updatePagerItems()
            invalidateOptionsMenu()
        }.execute()
    }

    private fun getProperPosition(): Int {
        mPos = 0
        var i = 0
        for (medium in mMedia) {
            if (medium.path == mPath) {
                return i
            }
            i++
        }
        return mPos
    }

    private fun deleteDirectoryIfEmpty() {
        val file = File(mDirectory)
        if (file.isDirectory && file.listFiles().isEmpty()) {
            file.delete()
        }

        scanPath(mDirectory) {}
    }

    override fun fragmentClicked() {
        mIsFullScreen = !mIsFullScreen
        if (mIsFullScreen) {
            hideSystemUI()
        } else {
            showSystemUI()
        }
    }

    private fun updateActionbarTitle() {
        title = mMedia[mPos].path.getFilenameFromPath()
    }

    private fun getCurrentMedium(): Medium? {
        return if (mMedia.isEmpty())
            null
        else
            mMedia[Math.min(mPos, mMedia.size - 1)]
    }

    private fun getCurrentFile() = File(getCurrentMedium()!!.path)

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {

    }

    override fun onPageSelected(position: Int) {
        mPos = position
        updateActionbarTitle()
        supportInvalidateOptionsMenu()
    }

    override fun onPageScrollStateChanged(state: Int) {
        if (state == ViewPager.SCROLL_STATE_DRAGGING) {
            val adapter = view_pager.adapter as MyPagerAdapter
            adapter.itemDragged(mPos)
        }
    }

    override fun onSystemUiVisibilityChange(visibility: Int) {
        view_pager.adapter?.apply {
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                mIsFullScreen = false
                showSystemUI()
            }

            (this as MyPagerAdapter).updateUiVisibility(mIsFullScreen, mPos)
        }
    }
}
