package com.souti.ai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** 列表页：错题本（收藏）与搜题历史共用一套界面，靠 intent 的 mode 切数据源。
 *  两份数据各存各的文件，删除互不影响。 */
class WrongBookActivity : AppCompatActivity() {

    private val items = mutableListOf<Entry>()
    private val selected = mutableSetOf<Long>()
    private var manage = false
    private var filter = "全部"
    private var history = false
    private lateinit var rv: RecyclerView
    private lateinit var adapter: Adapter

    private fun all() = if (history) HistoryStore.all(this) else WrongBookStore.all(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_book)
        history = intent.getStringExtra("mode") == "history"
        findViewById<TextView>(R.id.tv_title).text = if (history) "搜题历史" else "错题本"
        findViewById<TextView>(R.id.tv_empty).text =
            if (history) "还没搜过题，去拍一道吧" else "你还没有收藏题目哦，先去收藏吧"
        rv = findViewById(R.id.rv)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = Adapter()
        rv.adapter = adapter

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_filter).setOnClickListener { chooseFilter() }
        findViewById<TextView>(R.id.tv_manage).setOnClickListener {
            manage = !manage
            selected.clear()
            findViewById<TextView>(R.id.tv_manage).text = if (manage) "  完成" else "  管理"
            findViewById<View>(R.id.tv_delete).visibility = if (manage) View.VISIBLE else View.GONE
            refresh()
        }
        findViewById<View>(R.id.tv_delete).setOnClickListener {
            if (selected.isEmpty()) { toast("请先选中要删除的题目"); return@setOnClickListener }
            val ids = selected.toSet()
            if (history) HistoryStore.delete(this, ids) else WrongBookStore.delete(this, ids)
            selected.clear(); refresh()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun chooseFilter() {
        val subjects = mutableListOf("全部")
        all().forEach { if (it.subject !in subjects) subjects.add(it.subject) }
        AlertDialog.Builder(this).setItems(subjects.toTypedArray()) { _, i ->
            filter = subjects[i]
            findViewById<TextView>(R.id.tv_filter).text = filter
            refresh()
        }.show()
    }

    private fun refresh() {
        items.clear()
        // id 就是那一刻的毫秒数：新的排前面，翻列表不用再滑到底
        val src = if (filter == "全部") all() else all().filter { it.subject == filter }
        items.addAll(src.sortedByDescending { it.id })
        findViewById<View>(R.id.ll_empty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toast(s: String) =
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: ImageView = v.findViewById(R.id.iv_check)
            val q: TextView = v.findViewById(R.id.tv_q)
            val time: TextView = v.findViewById(R.id.tv_time)
            val subject: TextView = v.findViewById(R.id.tv_subject)
        }

        override fun onCreateViewHolder(parent: ViewGroup, type: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_wrong_book, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.q.text = MarkdownRender.plain(e.question)
            h.time.text = e.time
            h.subject.text = e.subject
            h.check.visibility = if (manage) View.VISIBLE else View.INVISIBLE
            h.check.setImageResource(if (selected.contains(e.id)) R.mipmap.icon_wc else R.mipmap.icon_wx)
            h.itemView.setOnClickListener {
                if (manage) {
                    if (!selected.remove(e.id)) selected.add(e.id)
                    notifyItemChanged(h.bindingAdapterPosition)
                } else {
                    startActivity(Intent(this@WrongBookActivity, ResultActivity::class.java)
                        .putExtra("question", e.question)
                        .putExtra("answer", e.answer)
                        .putExtra("subject", e.subject))
                }
            }
        }
    }
}
