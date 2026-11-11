package com.example.colorlauncher

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

class AppInfo(
    val label: String,
    val pkg: String,
    val icon: Drawable,
    val intent: Intent
)

class MainActivity : Activity() {

    private lateinit var pager: ViewPager
    private lateinit var dock: LinearLayout
    private lateinit var dots: LinearLayout
    private var pageCount = 1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadApps()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }

        // Страницы с иконками
        pager = ViewPager(this)
        pager.id = View.generateViewId()
        pager.setPageTransformer(false, ViewPager.PageTransformer { page, position ->
            val a = abs(position)
            val s = 1f - 0.12f * a
            page.alpha = 1f - 0.6f * a
            page.scaleX = s
            page.scaleY = s
        })
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
            }
        })
        root.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Точки-индикатор
        dots = LinearLayout(this)
        dots.gravity = Gravity.CENTER
        root.addView(dots, LinearLayout.LayoutParams(MATCH, dp(22)))

        // Док
        dock = LinearLayout(this)
        dock.gravity = Gravity.CENTER_VERTICAL
        dock.setPadding(dp(8), 0, dp(8), 0)
        dock.background = GradientDrawable().apply {
            setColor(Color.argb(70, 255, 255, 255))
            cornerRadius = dp(30).toFloat()
        }
        val dockLp = LinearLayout.LayoutParams(MATCH, dp(82))
        dockLp.setMargins(dp(14), dp(4), dp(14), dp(14))
        root.addView(dock, dockLp)

        setContentView(root)

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        registerReceiver(receiver, filter)

        loadApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) {
            pager.setCurrentItem(0, true)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Лаунчер не закрывается кнопкой «назад»
    }

    // ---------- Загрузка приложений ----------

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { ri ->
                    val pkg = ri.activityInfo.packageName
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, ri.activityInfo.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    AppInfo(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm), launch)
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { showApps(list) }
        }.start()
    }

    private fun pickDock(all: List<AppInfo>): List<AppInfo> {
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val res = mutableListOf<AppInfo>()
        for (intent in intents) {
            val pkg = packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName ?: continue
            val app = all.firstOrNull { it.pkg == pkg }
            if (app != null && !res.contains(app)) res.add(app)
        }
        for (a in all) {
            if (res.size >= 4) break
            if (!res.contains(a)) res.add(a)
        }
        return res.take(4)
    }

    private fun showApps(all: List<AppInfo>) {
        val dockApps = pickDock(all)
        val rest = all.filter { it !in dockApps }

        dock.removeAllViews()
        for (a in dockApps) {
            dock.addView(makeCell(a, false), LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        val pages = rest.chunked(24) // 4 колонки x 6 рядов
        pageCount = maxOf(1, pages.size)
        val current = pager.currentItem
        pager.adapter = PagesAdapter(pages)
        pager.setCurrentItem(minOf(current, pageCount - 1), false)

        buildDots()
        updateDots(pager.currentItem)
    }

    // ---------- Экран ----------

    private fun makePage(apps: List<AppInfo>): View {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(12), 0, 0)
        for (r in 0 until 6) {
            val row = LinearLayout(this)
            page.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
            for (c in 0 until 4) {
                val idx = r * 4 + c
                val v: View = if (idx < apps.size) makeCell(apps[idx], true) else View(this)
                row.addView(v, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
        return page
    }

    private fun makeCell(app: AppInfo, withLabel: Boolean): View {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER

        val size = dp(if (withLabel) 54 else 58)
        val icon = ImageView(this)
        icon.setImageDrawable(app.icon)
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f)
            }
        }
        icon.clipToOutline = true
        cell.addView(icon, LinearLayout.LayoutParams(size, size))

        if (withLabel) {
            val tv = TextView(this)
            tv.text = app.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 11f
            tv.maxLines = 1
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.gravity = Gravity.CENTER
            tv.setShadowLayer(4f, 0f, 1f, Color.argb(160, 0, 0, 0))
            tv.setPadding(dp(2), dp(5), dp(2), 0)
            cell.addView(tv, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Пружинная анимация нажатия
        cell.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(350)
                        .setInterpolator(OvershootInterpolator(3f)).start()
            }
            false
        }
        cell.setOnClickListener { launchApp(app, icon) }
        cell.setOnLongClickListener { showMenu(cell, app); true }
        return cell
    }

    private fun launchApp(app: AppInfo, from: View) {
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            startActivity(app.intent, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть приложение", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(anchor: View, app: AppInfo) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "О приложении")
        menu.menu.add(0, 2, 1, "Удалить")
        menu.setOnMenuItemClickListener { item ->
            val uri = Uri.parse("package:" + app.pkg)
            try {
                if (item.itemId == 1) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                } else {
                    startActivity(Intent(Intent.ACTION_DELETE, uri))
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Недоступно", Toast.LENGTH_SHORT).show()
            }
            true
        }
        menu.show()
    }

    // ---------- Точки ----------

    private fun buildDots() {
        dots.removeAllViews()
        if (pageCount < 2) return
        for (i in 0 until pageCount) {
            val d = View(this)
            d.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            val lp = LinearLayout.LayoutParams(dp(6), dp(6))
            lp.setMargins(dp(4), 0, dp(4), 0)
            dots.addView(d, lp)
        }
    }

    private fun updateDots(selected: Int) {
        for (i in 0 until dots.childCount) {
            val on = i == selected
            dots.getChildAt(i).animate()
                .alpha(if (on) 1f else 0.4f)
                .scaleX(if (on) 1.4f else 1f)
                .scaleY(if (on) 1.4f else 1f)
                .setDuration(200).start()
        }
    }

    inner class PagesAdapter(private val data: List<List<AppInfo>>) : PagerAdapter() {
        override fun getCount(): Int = maxOf(1, data.size)
        override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val v = makePage(data.getOrElse(position) { emptyList() })
            container.addView(v)
            return v
        }
        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }
    }
}
