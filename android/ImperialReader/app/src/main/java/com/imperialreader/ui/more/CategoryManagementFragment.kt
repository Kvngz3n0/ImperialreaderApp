package com.imperialreader.ui.more

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.imperialreader.data.db.CategoryEntity
import com.imperialreader.ui.theme.AppThemeManager
import com.imperialreader.util.app
import com.imperialreader.util.dp
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun categoryNameError(name: String, categories: List<CategoryEntity>, excludedId: Long? = null): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "Enter a category name"
        trimmed.equals("Default", ignoreCase = true) -> "Default is a reserved category"
        categories.any { it.id != excludedId && it.name.trim().equals(trimmed, ignoreCase = true) } ->
            "A category with this name already exists"
        else -> null
    }
}

internal fun reorderedCategoryIds(current: List<CategoryEntity>, requested: List<Long>): List<Long>? {
    val currentIds = current.map { it.id }
    return requested.takeIf { it.size == currentIds.size && it.toSet() == currentIds.toSet() }
}

class CategoryManagementFragment : Fragment() {
    private lateinit var adapter: CategoryAdapter
    private lateinit var helper: ItemTouchHelper
    private lateinit var empty: TextView
    private var dragging = false
    private var savingOrder = false
    private var moved = false

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppThemeManager.color(ctx, android.R.attr.colorBackground))
        }
        val add = MaterialButton(ctx).apply {
            text = "Add category"
            isAllCaps = false
            setOnClickListener { showNameDialog(null) }
        }
        root.addView(add, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8)) })
        root.addView(TextView(ctx).apply {
            text = "Default"
            textSize = 16f
            setTextColor(AppThemeManager.color(ctx, com.google.android.material.R.attr.colorOnSurface))
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
            contentDescription = "Default category, fixed"
        })
        empty = TextView(ctx).apply {
            text = "No custom categories yet. Add one to organize your Library."
            textSize = 14f
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(16))
            setTextColor(AppThemeManager.color(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        root.addView(empty)
        adapter = CategoryAdapter()
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@CategoryManagementFragment.adapter
        }
        helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = false

            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = holder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (savingOrder || from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.move(from, to)
                moved = true
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(holder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(holder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && holder != null) {
                    dragging = true
                    holder.itemView.alpha = 0.75f
                    holder.itemView.elevation = ctx.dp(8).toFloat()
                }
            }

            override fun clearView(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, holder)
                holder.itemView.alpha = 1f
                holder.itemView.elevation = 0f
                dragging = false
                if (moved) {
                    moved = false
                    saveOrder(adapter.ids())
                } else {
                    refreshList()
                }
            }
        })
        helper.attachToRecyclerView(list)
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.db.categoryDao().observeAll().collect { categories ->
                    if (!dragging && !savingOrder) showCategories(categories)
                }
            }
        }
    }

    private fun showCategories(categories: List<CategoryEntity>) {
        adapter.replace(categories)
        empty.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            showCategories(requireContext().app.db.categoryDao().getAll())
        }
    }

    private fun saveOrder(ids: List<Long>) {
        savingOrder = true
        val ctx = requireContext()
        requireActivity().lifecycleScope.launch {
            try {
                val db = ctx.app.db
                val saved = db.withTransaction {
                    val ordered = reorderedCategoryIds(db.categoryDao().getAll(), ids)
                    if (ordered != null) {
                        ordered.forEachIndexed { index, id -> db.categoryDao().setSortOrder(id, index) }
                    }
                    ordered != null
                }
                ctx.toast(if (saved) "Category order saved" else "Categories changed; please reorder again")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CategoryManagement", "Could not save category order", e)
                ctx.toast("Could not save category order")
            } finally {
                savingOrder = false
            }
            if (isAdded && view != null) showCategories(ctx.app.db.categoryDao().getAll())
        }
    }

    private fun showNameDialog(existing: CategoryEntity?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "Category name"
            setSingleLine()
            setText(existing?.name.orEmpty())
            setSelection(text.length)
        }
        val box = LinearLayout(ctx).apply {
            setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0)
            addView(input, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing == null) "New category" else "Rename category")
            .setView(box)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                button.isEnabled = false
                val name = input.text.toString().trim()
                requireActivity().lifecycleScope.launch {
                    try {
                        val db = ctx.app.db
                        val error = db.withTransaction {
                            val categories = db.categoryDao().getAll()
                            val validation = categoryNameError(name, categories, existing?.id)
                            val current = existing?.let { old -> categories.firstOrNull { it.id == old.id } }
                            when {
                                validation != null -> validation
                                existing != null && current == null -> "This category no longer exists"
                                current != null -> {
                                    db.categoryDao().update(current.copy(name = name))
                                    null
                                }
                                else -> {
                                    categories.forEachIndexed { index, category ->
                                        db.categoryDao().setSortOrder(category.id, index)
                                    }
                                    db.categoryDao().insert(CategoryEntity(name = name, sortOrder = categories.size))
                                    null
                                }
                            }
                        }
                        if (error == null) {
                            dialog.dismiss()
                            ctx.toast("Category saved")
                        } else {
                            input.error = error
                            button.isEnabled = true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("CategoryManagement", "Could not save category", e)
                        ctx.toast("Could not save category")
                        button.isEnabled = true
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(category: CategoryEntity) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Delete category?")
            .setMessage("“${category.name}” will be removed. Its entries will move to Default; their content will not be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                requireActivity().lifecycleScope.launch {
                    try {
                        val db = ctx.app.db
                        db.withTransaction {
                            db.mangaDao().clearCategory(category.id)
                            db.categoryDao().delete(category.id)
                            db.categoryDao().getAll().forEachIndexed { index, remaining ->
                                db.categoryDao().setSortOrder(remaining.id, index)
                            }
                        }
                        ctx.toast("Category deleted")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("CategoryManagement", "Could not delete category", e)
                        ctx.toast("Could not delete category")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditOptions(category: CategoryEntity) {
        val index = adapter.ids().indexOf(category.id)
        if (index < 0 || savingOrder || dragging) return
        val actions = buildList {
            add("Rename")
            if (index > 0) add("Move up")
            if (index < adapter.itemCount - 1) add("Move down")
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Manage category")
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Rename" -> showNameDialog(category)
                    "Move up", "Move down" -> {
                        val current = adapter.ids().indexOf(category.id)
                        val destination = current + if (actions[which] == "Move up") -1 else 1
                        if (!savingOrder && !dragging && current >= 0 && destination in 0 until adapter.itemCount) {
                            adapter.move(current, destination)
                            saveOrder(adapter.ids())
                        }
                    }
                }
            }
            .show()
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<CategoryAdapter.Holder>() {
        private val items = mutableListOf<CategoryEntity>()

        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = items[position].id
        override fun getItemCount() = items.size
        fun ids() = items.map { it.id }

        fun replace(categories: List<CategoryEntity>) {
            items.clear()
            items.addAll(categories)
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(4))
                minimumHeight = ctx.dp(64)
            }
            val title = TextView(ctx).apply {
                textSize = 16f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(AppThemeManager.color(ctx, com.google.android.material.R.attr.colorOnSurface))
            }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            fun action(label: String) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minimumHeight = ctx.dp(48)
                insetTop = 0
                insetBottom = 0
                setPadding(ctx.dp(8), 0, ctx.dp(8), 0)
            }
            val edit = action("Edit")
            val delete = action("Delete")
            val handle = action("≡").apply {
                minWidth = ctx.dp(48)
                minimumWidth = ctx.dp(48)
                contentDescription = "Hold to reorder category"
                setTextColor(AppThemeManager.color(ctx, com.google.android.material.R.attr.colorOnSurface))
            }
            row.addView(edit)
            row.addView(delete)
            row.addView(handle)
            return Holder(row, title, edit, delete, handle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val category = items[position]
            holder.title.text = category.name
            holder.edit.contentDescription = "Edit ${category.name}"
            holder.delete.contentDescription = "Delete ${category.name}"
            holder.handle.contentDescription = "Hold to reorder ${category.name}"
            holder.edit.setOnClickListener { showEditOptions(category) }
            holder.delete.setOnClickListener { confirmDelete(category) }
            holder.handle.setOnLongClickListener {
                if (!savingOrder && holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    helper.startDrag(holder)
                    true
                } else false
            }
        }

        inner class Holder(
            row: View, val title: TextView, val edit: MaterialButton,
            val delete: MaterialButton, val handle: MaterialButton
        ) : RecyclerView.ViewHolder(row)
    }
}
