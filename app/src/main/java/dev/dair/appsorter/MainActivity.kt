package dev.dair.appsorter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.tabs.TabLayoutMediator
import dev.dair.appsorter.categorizer.AppCategorizer
import dev.dair.appsorter.categorizer.BedrockAppCategorizer
import dev.dair.appsorter.categorizer.CategorizationEngine
import dev.dair.appsorter.categorizer.CategorizerBackend
import dev.dair.appsorter.categorizer.RuleBasedAppCategorizer
import dev.dair.appsorter.data.AppCategoryRepository
import dev.dair.appsorter.data.AppSorterDatabase
import dev.dair.appsorter.databinding.ActivityMainBinding
import dev.dair.appsorter.model.CategorizedApp
import dev.dair.appsorter.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: AppCategoryRepository
    private lateinit var engine: CategorizationEngine
    private lateinit var pagerAdapter: CategoryPagerAdapter
    private lateinit var tabMediator: TabLayoutMediator
    private lateinit var categoryAdapter: CategoryGridAdapter
    private lateinit var categoryTouchHelper: ItemTouchHelper

    private var allApps: List<CategorizedApp> = emptyList()
    private var mode: ViewMode = ViewMode.CATEGORY

    /**
     * カテゴリからドリルダウン中かどうか。ドリルダウン中は back で元の CATEGORY に戻る。
     * 通常の toggle 切替で APPS に入った場合は false のまま。
     */
    private val drillDownBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // 永続化された default が CATEGORY のときだけ戻す (APPS 永続の人は素直に exit)
            if (ViewModePrefs.load(this@MainActivity) == ViewMode.CATEGORY) {
                mode = ViewMode.CATEGORY
                binding.modeToggle.check(R.id.modeCategory)
                applyMode()
            }
            isEnabled = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Android 15+ はedge-to-edge強制。古い端末も含めて明示的に有効化してから
        // 自前でシステムバーぶんの padding を root に当てる。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(binding.toolbar)

        val database = AppSorterDatabase.build(this)
        repository = AppCategoryRepository(database.appCategoryDao())
        engine = buildEngine()

        setupAppTiles()
        setupCategoryGrid()
        setupModeToggle()
        onBackPressedDispatcher.addCallback(this, drillDownBackCallback)

        binding.txtBackend.text = getString(R.string.backend_label, backendDisplay())
        binding.btnScan.setOnClickListener { scanAndCategorize() }

        // 初期はAllだけの空ページ
        pagerAdapter.submitPages(
            listOf(CategoryPagerAdapter.Page(CategoryLabel.ALL, emptyList()))
        )

        mode = ViewModePrefs.load(this)
        applyMode()

        lifecycleScope.launch { loadSavedData() }
    }

    private fun setupAppTiles() {
        pagerAdapter = CategoryPagerAdapter(
            packageManager = packageManager,
            onAppLongPress = { app -> showAppActionSheet(app) },
        )
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.offscreenPageLimit = 1

        tabMediator = TabLayoutMediator(binding.tabs, binding.viewPager) { tab, position ->
            val page = pagerAdapter.getPage(position) ?: return@TabLayoutMediator
            tab.text = getString(
                R.string.tab_label,
                CategoryLabel.displayName(this, page.category),
                page.apps.size,
            )
        }
        tabMediator.attach()
    }

    private fun setupCategoryGrid() {
        categoryAdapter = CategoryGridAdapter(
            packageManager = packageManager,
            onCategoryClick = { category -> drillDownToCategory(category) },
            onStartDrag = { vh -> categoryTouchHelper.startDrag(vh) },
        )
        binding.recyclerCategory.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerCategory.adapter = categoryAdapter

        categoryTouchHelper = ItemTouchHelper(
            CategoryDragCallback(
                onMove = { from, to -> categoryAdapter.moveItem(from, to) },
                onDropped = {
                    val newOrder = categoryAdapter.currentOrder()
                    CategoryOrder.save(this, newOrder)
                    // タブ順もこれに合わせて即時更新
                    renderApps(allApps)
                }
            )
        )
        categoryTouchHelper.attachToRecyclerView(binding.recyclerCategory)
    }

    private fun setupModeToggle() {
        // 初期チェック状態
        val initialId = if (ViewModePrefs.load(this) == ViewMode.CATEGORY) {
            R.id.modeCategory
        } else {
            R.id.modeApps
        }
        binding.modeToggle.check(initialId)

        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = if (checkedId == R.id.modeCategory) ViewMode.CATEGORY else ViewMode.APPS
            if (newMode == mode) return@addOnButtonCheckedListener
            mode = newMode
            // ユーザーが明示的にトグル切替したときだけ永続化する
            ViewModePrefs.save(this, mode)
            applyMode()
            // トグル操作で抜けたらドリルダウン状態は解消
            drillDownBackCallback.isEnabled = false
        }
    }

    private fun applyMode() {
        when (mode) {
            ViewMode.CATEGORY -> {
                binding.recyclerCategory.visibility = View.VISIBLE
                binding.tabs.visibility = View.GONE
                binding.viewPager.visibility = View.GONE
            }
            ViewMode.APPS -> {
                binding.recyclerCategory.visibility = View.GONE
                binding.tabs.visibility = View.VISIBLE
                binding.viewPager.visibility = View.VISIBLE
            }
        }
    }

    private fun showAppActionSheet(app: CategorizedApp) {
        AppActions.showActionSheet(
            context = this,
            app = app,
            onChangeCategory = { newCategory ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        repository.overrideCategory(app.packageName, newCategory)
                    }
                    // 差分適用（再スキャン無しで即時反映）
                    val updated = allApps.map {
                        if (it.packageName == app.packageName) {
                            it.copy(category = newCategory, source = "manual")
                        } else it
                    }
                    renderApps(updated)
                }
            },
            onOpenDetails = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.packageName, null)
                )
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    // ignore
                }
            },
        )
    }

    private fun drillDownToCategory(category: String) {
        // ドリルダウンは一時遷移。永続化はしない (戻るキーで CATEGORY に戻るのが期待挙動)
        mode = ViewMode.APPS
        binding.modeToggle.check(R.id.modeApps)
        applyMode()
        val index = pagerAdapter.findPageIndex(category)
        if (index >= 0) {
            binding.viewPager.setCurrentItem(index, false)
        }
        drillDownBackCallback.isEnabled = true
    }

    private suspend fun loadSavedData() {
        val apps = withContext(Dispatchers.IO) { repository.loadApps() }
        renderApps(apps)
        binding.txtStatus.text =
            if (apps.isEmpty()) getString(R.string.status_idle) else getString(
                R.string.status_loaded,
                apps.size
            )
    }

    private fun scanAndCategorize() {
        binding.btnScan.isEnabled = false
        binding.progressScan.visibility = View.VISIBLE
        binding.progressScan.progress = 0
        binding.txtStatus.text = getString(R.string.status_scanning)

        lifecycleScope.launch {
            try {
                val installedApps = withContext(Dispatchers.IO) { loadLaunchableApps() }
                if (installedApps.isEmpty()) {
                    binding.txtStatus.text = getString(R.string.status_no_apps)
                    return@launch
                }

                binding.progressScan.max = installedApps.size
                val categorized = engine.categorizeInBatches(
                    apps = installedApps,
                    batchSize = 50
                ) { processed, total, batchIndex ->
                    runOnUiThread {
                        binding.progressScan.progress = processed
                        binding.txtStatus.text = getString(
                            R.string.status_batch_progress,
                            batchIndex,
                            total,
                            processed
                        )
                    }
                }

                withContext(Dispatchers.IO) { repository.saveApps(categorized) }
                renderApps(categorized)
                binding.txtStatus.text = getString(
                    R.string.status_done,
                    categorized.size,
                    backendDisplay()
                )
            } finally {
                binding.btnScan.isEnabled = true
                binding.progressScan.visibility = View.GONE
            }
        }
    }

    private fun renderApps(apps: List<CategorizedApp>) {
        allApps = apps
        val grouped = apps.groupBy { it.category }
        val userOrder = CategoryOrder.load(this)

        // Category Tile モード: アプリがあるカテゴリだけ、ユーザー順で表示
        val tileItems = userOrder
            .filter { grouped[it]?.isNotEmpty() == true }
            .map { CategoryGridAdapter.Item(it, grouped.getValue(it)) }
        categoryAdapter.submitItems(tileItems)

        // App Tile モード: "All" を先頭にして、その後カテゴリをユーザー順で
        val pages = mutableListOf<CategoryPagerAdapter.Page>()
        pages.add(CategoryPagerAdapter.Page(CategoryLabel.ALL, apps))
        for (category in userOrder) {
            val list = grouped[category]
            if (!list.isNullOrEmpty()) {
                pages.add(CategoryPagerAdapter.Page(category, list))
            }
        }
        pagerAdapter.submitPages(pages)
    }

    private fun backendDisplay(): String = when (engine.backend) {
        CategorizerBackend.OFFLINE_RULES -> getString(R.string.backend_offline_rules)
        CategorizerBackend.CLAUDE_API -> getString(R.string.backend_bedrock)
        CategorizerBackend.LOCAL_LLM -> getString(R.string.backend_local_llm)
    }

    private fun buildEngine(): CategorizationEngine {
        val apiUrl = BuildConfig.APPSORTER_API_URL
        val poolId = BuildConfig.APPSORTER_COGNITO_IDENTITY_POOL_ID
        val region = BuildConfig.APPSORTER_AWS_REGION

        val (backend, categorizer) = if (apiUrl.isNotBlank() && poolId.isNotBlank()) {
            CategorizerBackend.CLAUDE_API to BedrockAppCategorizer(
                apiUrl = apiUrl,
                region = region,
                identityPoolId = poolId,
            ).withFallback(RuleBasedAppCategorizer())
        } else {
            CategorizerBackend.OFFLINE_RULES to RuleBasedAppCategorizer()
        }
        return CategorizationEngine(backend = backend, categorizer = categorizer)
    }

    private fun AppCategorizer.withFallback(fallback: AppCategorizer): AppCategorizer =
        object : AppCategorizer {
            override suspend fun categorize(batch: List<InstalledApp>) =
                runCatching { this@withFallback.categorize(batch) }.getOrElse {
                    android.util.Log.w("AppSorter", "remote categorize failed, falling back", it)
                    fallback.categorize(batch)
                }
        }

    private fun loadLaunchableApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }

        return resolveInfos
            .mapNotNull { resolveInfo ->
                val packageName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager)?.toString()?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                InstalledApp(
                    appName = label,
                    packageName = packageName
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }
}
